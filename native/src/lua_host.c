#include "packsanity/lua_host.h"

#include <stdlib.h>
#include <string.h>

#include "lauxlib.h"
#include "lualib.h"

struct PackSanityLuaState {
    lua_State *lua;
    size_t used_bytes;
    size_t memory_limit_bytes;
    int64_t instruction_limit;
    int64_t instructions_remaining;
    int64_t refresh_remaining;
    int call_exhausted;
    int refresh_active;
    int refresh_exhausted;
};

static char *copy_string(const char *value) {
    const char *resolved = value == NULL ? "Unknown Lua error" : value;
    size_t length = strlen(resolved) + 1;
    char *copy = malloc(length);
    if (copy != NULL) memcpy(copy, resolved, length);
    return copy;
}

static int fail_from_stack(lua_State *lua, char **error) {
    if (error != NULL) {
        const char *message = lua_type(lua, -1) == LUA_TSTRING
            ? lua_tostring(lua, -1) : luaL_typename(lua, -1);
        *error = copy_string(message);
    }
    lua_pop(lua, 1);
    return 0;
}

static void *packsanity_alloc(void *userdata, void *pointer, size_t old_size, size_t new_size) {
    PackSanityLuaState *state = userdata;
    if (pointer == NULL) old_size = 0; // Lua supplies a type tag for new allocations.
    if (new_size == 0) {
        if (pointer != NULL) {
            state->used_bytes = old_size > state->used_bytes ? 0 : state->used_bytes - old_size;
            free(pointer);
        }
        return NULL;
    }
    if (new_size > old_size &&
        (state->used_bytes > state->memory_limit_bytes ||
         new_size - old_size > state->memory_limit_bytes - state->used_bytes)) return NULL;
    void *next = realloc(pointer, new_size);
    if (next != NULL) state->used_bytes = new_size >= old_size ? state->used_bytes + new_size - old_size : state->used_bytes - old_size + new_size;
    return next;
}

static void instruction_hook(lua_State *lua, lua_Debug *debug) {
    (void)debug;
    PackSanityLuaState *state = *(PackSanityLuaState **)lua_getextraspace(lua);
    state->instructions_remaining -= 1000;
    if (state->refresh_active) {
        state->refresh_remaining -= 1000;
        if (state->refresh_remaining <= 0) {
            state->refresh_exhausted = 1;
            state->call_exhausted = 1;
            luaL_error(lua, "Lua refresh instruction limit exceeded");
        }
    }
    if (state->instructions_remaining <= 0) {
        state->call_exhausted = 1;
        if (state->refresh_active) state->refresh_exhausted = 1;
        luaL_error(lua, "Lua instruction limit exceeded");
    }
}

static void begin_execution(PackSanityLuaState *state) {
    state->call_exhausted = 0;
    state->instructions_remaining = state->instruction_limit;
    lua_sethook(state->lua, instruction_hook, LUA_MASKCOUNT, 1000);
}

static void end_execution(PackSanityLuaState *state) { lua_sethook(state->lua, NULL, 0, 0); }
static int refresh_failed(PackSanityLuaState *state, char **error) {
    if (!state->refresh_active || !state->refresh_exhausted) return 0;
    if (error) *error = copy_string("Lua refresh instruction limit exceeded");
    return 1;
}
static int execution_result(PackSanityLuaState *state, int status, char **error) {
    end_execution(state);
    if (state->refresh_active && state->refresh_exhausted) {
        if (status != LUA_OK) lua_pop(state->lua, 1);
        return refresh_failed(state, error), 0;
    }
    return status == LUA_OK ? 1 : fail_from_stack(state->lua, error);
}
static void remove_global(lua_State *lua, const char *name) { lua_pushnil(lua); lua_setglobal(lua, name); }

/* Keep the library functions private in C upvalues. Each protected boundary
 * must propagate the host latch after Lua has unwound its own protected call. */
static int guarded_finish(lua_State *lua, int status, lua_KContext context) {
    (void)status; (void)context;
    PackSanityLuaState *state = *(PackSanityLuaState **)lua_getextraspace(lua);
    if (state->call_exhausted) return luaL_error(lua, "Lua instruction limit exceeded");
    return lua_gettop(lua);
}

static int guarded_call(lua_State *lua) {
    int n = lua_gettop(lua);
    lua_pushvalue(lua, lua_upvalueindex(1));
    lua_insert(lua, 1);
    lua_callk(lua, n, LUA_MULTRET, 0, guarded_finish);
    return guarded_finish(lua, LUA_OK, 0);
}

static int guarded_error_handler(lua_State *lua) {
    PackSanityLuaState *state = *(PackSanityLuaState **)lua_getextraspace(lua);
    if (state->call_exhausted) return luaL_error(lua, "Lua instruction limit exceeded");
    lua_pushvalue(lua, lua_upvalueindex(1));
    lua_insert(lua, 1);
    lua_call(lua, lua_gettop(lua) - 1, 1);
    return 1;
}

static int guarded_xpcall(lua_State *lua) {
    if (lua_isfunction(lua, 2)) {
        lua_pushvalue(lua, 2);
        lua_pushcclosure(lua, guarded_error_handler, 1);
        lua_replace(lua, 2);
    }
    return guarded_call(lua);
}

static int guarded_resume(lua_State *lua) {
    lua_State *co = lua_tothread(lua, 1);
    if (co != NULL) lua_sethook(co, instruction_hook, LUA_MASKCOUNT, 1000);
    return guarded_call(lua);
}

static int guarded_wrap_call(lua_State *lua) {
    lua_State *co = lua_tothread(lua, lua_upvalueindex(1));
    int n = lua_gettop(lua), nres = 0;
    if (!lua_checkstack(co, n)) return luaL_error(lua, "too many arguments to resume");
    lua_sethook(co, instruction_hook, LUA_MASKCOUNT, 1000);
    lua_xmove(lua, co, n);
    int status = lua_resume(co, lua, n, &nres);
    PackSanityLuaState *state = *(PackSanityLuaState **)lua_getextraspace(lua);
    if (state->call_exhausted) return luaL_error(lua, "Lua instruction limit exceeded");
    if (status == LUA_OK || status == LUA_YIELD) {
        if (!lua_checkstack(lua, nres + 1)) { lua_pop(co, nres); return luaL_error(lua, "too many results to resume"); }
        lua_xmove(co, lua, nres);
        return nres;
    }
    lua_xmove(co, lua, 1);
    if (lua_status(co) != LUA_OK && lua_status(co) != LUA_YIELD) {
        status = lua_closethread(co, lua);
        lua_xmove(co, lua, 1);
        if (state->call_exhausted) return luaL_error(lua, "Lua instruction limit exceeded");
    }
    if (status != LUA_ERRMEM && lua_type(lua, -1) == LUA_TSTRING) {
        luaL_where(lua, 1); lua_insert(lua, -2); lua_concat(lua, 2);
    }
    return lua_error(lua);
}

static int guarded_wrap(lua_State *lua) {
    luaL_checktype(lua, 1, LUA_TFUNCTION);
    lua_State *co = lua_newthread(lua);
    lua_pushvalue(lua, 1); lua_xmove(lua, co, 1);
    lua_pushcclosure(lua, guarded_wrap_call, 1);
    return 1;
}

static void guard_field(lua_State *lua, const char *name, lua_CFunction guard) {
    lua_getfield(lua, -1, name);
    lua_pushcclosure(lua, guard, 1);
    lua_setfield(lua, -2, name);
}

/* Preserve pack print output while keeping CLI JSON on stdout machine-readable. */
static int pack_print(lua_State *lua) {
    int count = lua_gettop(lua);
    for (int index = 1; index <= count; ++index) {
        size_t length;
        const char *text = luaL_tolstring(lua, index, &length);
        if (index > 1) fputc('\t', stderr);
        fwrite(text, 1, length, stderr);
        lua_pop(lua, 1);
    }
    fputc('\n', stderr);
    return 0;
}

static void open_sandboxed_libraries(lua_State *lua) {
    luaL_requiref(lua, LUA_GNAME, luaopen_base, 1); lua_pop(lua, 1);
    luaL_requiref(lua, LUA_COLIBNAME, luaopen_coroutine, 1); lua_pop(lua, 1);
    luaL_requiref(lua, LUA_TABLIBNAME, luaopen_table, 1); lua_pop(lua, 1);
    luaL_requiref(lua, LUA_STRLIBNAME, luaopen_string, 1); lua_pop(lua, 1);
    luaL_requiref(lua, LUA_MATHLIBNAME, luaopen_math, 1); lua_pop(lua, 1);
    luaL_requiref(lua, LUA_UTF8LIBNAME, luaopen_utf8, 1); lua_pop(lua, 1);
    luaL_requiref(lua, LUA_LOADLIBNAME, luaopen_package, 1); lua_pop(lua, 1);
    lua_pushglobaltable(lua);
    guard_field(lua, "pcall", guarded_call); guard_field(lua, "xpcall", guarded_xpcall);
    lua_pop(lua, 1);
    lua_getglobal(lua, "coroutine");
    guard_field(lua, "resume", guarded_resume); guard_field(lua, "close", guarded_resume);
    lua_pushcfunction(lua, guarded_wrap); lua_setfield(lua, -2, "wrap");
    lua_pop(lua, 1);
    lua_pushcfunction(lua, pack_print); lua_setglobal(lua, "print");
    remove_global(lua, "dofile"); remove_global(lua, "loadfile");
    lua_getglobal(lua, "package");
    lua_pushliteral(lua, ""); lua_setfield(lua, -2, "path");
    lua_pushliteral(lua, ""); lua_setfield(lua, -2, "cpath");
    lua_pushnil(lua); lua_setfield(lua, -2, "loadlib");
    lua_pushnil(lua); lua_setfield(lua, -2, "searchpath");
    lua_getfield(lua, -1, "searchers");
    for (int index = 2; index <= 4; ++index) { lua_pushnil(lua); lua_rawseti(lua, -2, index); }
    lua_pop(lua, 2);
}

static int initialize_state(lua_State *lua) {
    open_sandboxed_libraries(lua);
    lua_pushliteral(lua, LUA_RELEASE); lua_setglobal(lua, "__packsanity_lua_release");
    lua_newtable(lua); lua_setglobal(lua, "__packsanity_provider_counts");
    return 0;
}

PackSanityLuaState *packsanity_lua_create(int64_t memory_limit_bytes, int64_t instruction_limit) {
    if (memory_limit_bytes <= 0 || instruction_limit <= 0) return NULL;
    PackSanityLuaState *state = calloc(1, sizeof(PackSanityLuaState));
    if (state == NULL) return NULL;
    state->memory_limit_bytes = (size_t)memory_limit_bytes;
    state->instruction_limit = instruction_limit;
    state->lua = lua_newstate(packsanity_alloc, state);
    if (state->lua == NULL) { free(state); return NULL; }
    *(PackSanityLuaState **)lua_getextraspace(state->lua) = state;
    lua_pushcfunction(state->lua, initialize_state);
    if (lua_pcall(state->lua, 0, 0, 0) != LUA_OK) {
        lua_close(state->lua);
        free(state);
        return NULL;
    }
    return state;
}

void packsanity_lua_begin_refresh(PackSanityLuaState *state, int64_t limit) {
    if (state != NULL) { state->refresh_active = 1; state->refresh_exhausted = 0; state->refresh_remaining = limit; }
}
void packsanity_lua_end_refresh(PackSanityLuaState *state) {
    if (state != NULL) { state->refresh_active = 0; state->refresh_exhausted = 0; }
}

void packsanity_lua_close(PackSanityLuaState *state) { if (state != NULL) { lua_close(state->lua); free(state); } }
void packsanity_lua_free_string(char *value) { free(value); }

/* Each operation enters Lua protection before looking up pack-controlled globals or
 * pushing data. The bounded allocator can fail even while preparing a call. */
static int run_operation(PackSanityLuaState *state, lua_CFunction operation, void *args,
                         int results, char **error) {
    if (error != NULL) *error = NULL;
    if (state == NULL) {
        if (error != NULL) *error = copy_string("Lua runtime is closed");
        return 0;
    }
    if (refresh_failed(state, error)) return 0;
    lua_State *lua = state->lua;
    if (!lua_checkstack(lua, 2)) {
        if (error != NULL) *error = copy_string("Could not grow Lua stack");
        return 0;
    }
    begin_execution(state);
    lua_pushcfunction(lua, operation);
    lua_pushlightuserdata(lua, args);
    int status = lua_pcall(lua, 1, results, 0);
    if (state->call_exhausted && status == LUA_OK) {
        lua_pop(lua, results);
        end_execution(state);
        if (!refresh_failed(state, error) && error != NULL) {
            *error = copy_string("Lua instruction limit exceeded");
        }
        return 0;
    }
    return execution_result(state, status, error);
}

struct source_args {
    const char *source;
    size_t length;
    const char *chunk_name;
    const char *module_name;
    int lazy;
};

static int lazy_module_loader(lua_State *lua) {
    size_t source_length = 0;
    const char *source = lua_tolstring(lua, lua_upvalueindex(1), &source_length);
    const char *chunk_name = lua_tostring(lua, lua_upvalueindex(2));
    int arguments = lua_gettop(lua);
    if (luaL_loadbufferx(lua, source, source_length, chunk_name, "t") != LUA_OK) {
        return lua_error(lua);
    }
    lua_insert(lua, 1);
    lua_call(lua, arguments, LUA_MULTRET);
    return lua_gettop(lua);
}

static int register_module(lua_State *lua) {
    struct source_args *args = lua_touserdata(lua, 1);
    lua_getglobal(lua, "package");
    lua_getfield(lua, -1, "preload");
    if (!lua_istable(lua, -1)) return luaL_error(lua, "Lua module registry is unavailable");
    if (args->lazy) {
        lua_pushlstring(lua, args->source, args->length);
        lua_pushstring(lua, args->chunk_name);
        lua_pushcclosure(lua, lazy_module_loader, 2);
    } else if (luaL_loadbufferx(lua, args->source, args->length, args->chunk_name, "t") != LUA_OK) {
        return lua_error(lua);
    }
    lua_setfield(lua, -2, args->module_name);
    return 0;
}

int packsanity_lua_register_module(PackSanityLuaState *state, const char *name, const char *source,
                                  const char *chunk_name, char **error) {
    if (name == NULL || source == NULL || chunk_name == NULL) {
        if (error != NULL) *error = copy_string("Invalid Lua module");
        return 0;
    }
    struct source_args args = {source, strlen(source), chunk_name, name, 0};
    return run_operation(state, register_module, &args, 0, error);
}

int packsanity_lua_register_lazy_module(PackSanityLuaState *state, const char *name, const char *source,
                                       const char *chunk_name, char **error) {
    if (name == NULL || source == NULL || chunk_name == NULL) {
        if (error != NULL) *error = copy_string("Invalid Lua module");
        return 0;
    }
    struct source_args args = {source, strlen(source), chunk_name, name, 1};
    return run_operation(state, register_module, &args, 0, error);
}

struct pack_file_args {
    const char *path;
    const char *contents;
    size_t length;
};

static int register_pack_file(lua_State *lua) {
    struct pack_file_args *args = lua_touserdata(lua, 1);
    lua_getglobal(lua, "PackSanityRegisterPackFile");
    if (!lua_isfunction(lua, -1)) return luaL_error(lua, "Pack file registration is unavailable");
    lua_pushstring(lua, args->path);
    lua_pushlstring(lua, args->contents, args->length);
    lua_call(lua, 2, 0);
    return 0;
}

int packsanity_lua_register_pack_file(PackSanityLuaState *state, const char *path, const void *contents,
                                     size_t contents_length, char **error) {
    if (path == NULL || (contents == NULL && contents_length != 0)) {
        if (error != NULL) *error = copy_string("Invalid pack file");
        return 0;
    }
    struct pack_file_args args = {path, contents == NULL ? "" : contents, contents_length};
    return run_operation(state, register_pack_file, &args, 0, error);
}

static int execute(lua_State *lua) {
    struct source_args *args = lua_touserdata(lua, 1);
    if (luaL_loadbufferx(lua, args->source, args->length, args->chunk_name, "t") != LUA_OK) {
        return lua_error(lua);
    }
    lua_call(lua, 0, 0);
    return 0;
}

int packsanity_lua_execute(PackSanityLuaState *state, const char *source, const char *chunk_name,
                          char **error) {
    if (source == NULL || chunk_name == NULL) {
        if (error != NULL) *error = copy_string("Invalid Lua source");
        return 0;
    }
    struct source_args args = {source, strlen(source), chunk_name, NULL, 0};
    return run_operation(state, execute, &args, 0, error);
}

struct provider_args {
    const char *code;
    int count;
};

static int set_provider_count(lua_State *lua) {
    struct provider_args *args = lua_touserdata(lua, 1);
    lua_getglobal(lua, "__packsanity_provider_counts");
    if (!lua_istable(lua, -1)) return luaL_error(lua, "Provider count table is unavailable");
    lua_pushinteger(lua, args->count);
    lua_setfield(lua, -2, args->code);
    lua_getglobal(lua, "PackSanityProviderCountChanged");
    if (lua_isfunction(lua, -1)) {
        lua_pushstring(lua, args->code);
        lua_pushinteger(lua, args->count);
        lua_call(lua, 2, 0);
    }
    return 0;
}

int packsanity_lua_set_provider_count(PackSanityLuaState *state, const char *code, int count,
                                     char **error) {
    if (code == NULL) {
        if (error != NULL) *error = copy_string("Invalid provider code");
        return 0;
    }
    struct provider_args args = {code, count};
    return run_operation(state, set_provider_count, &args, 0, error);
}

enum call_type { CALL_BOOLEAN, CALL_INTEGER, CALL_STRING };
struct call_args {
    const char *name;
    const char *argument;
    size_t length;
    enum call_type type;
};

static int call_function(lua_State *lua) {
    struct call_args *args = lua_touserdata(lua, 1);
    if (args->name == NULL) return luaL_error(lua, "Lua function name is missing");
    lua_getglobal(lua, args->name);
    if (!lua_isfunction(lua, -1)) return luaL_error(lua, "Lua function was not found");
    if (args->argument == NULL) lua_pushnil(lua);
    else lua_pushlstring(lua, args->argument, args->length);
    lua_call(lua, 1, 1);
    /* Converting a numeric result to a string may allocate. */
    if (args->type == CALL_STRING) lua_tostring(lua, -1);
    return 1;
}

int packsanity_lua_call_boolean_bytes(PackSanityLuaState *state, const char *name,
                                     const char *argument, size_t length, int *result, char **error) {
    if (result == NULL) {
        if (error != NULL) *error = copy_string("Boolean result pointer is missing");
        return 0;
    }
    struct call_args args = {name, argument, length, CALL_BOOLEAN};
    if (!run_operation(state, call_function, &args, 1, error)) return 0;
    *result = lua_toboolean(state->lua, -1);
    lua_pop(state->lua, 1);
    return 1;
}

int packsanity_lua_call_integer_bytes(PackSanityLuaState *state, const char *name,
                                     const char *argument, size_t length, int64_t *result, char **error) {
    if (result == NULL) {
        if (error != NULL) *error = copy_string("Integer result pointer is missing");
        return 0;
    }
    struct call_args args = {name, argument, length, CALL_INTEGER};
    if (!run_operation(state, call_function, &args, 1, error)) return 0;
    int is_number = 0;
    lua_Integer value = lua_tointegerx(state->lua, -1, &is_number);
    *result = is_number ? (int64_t)value
        : (lua_isboolean(state->lua, -1) && lua_toboolean(state->lua, -1) ? 1 : 0);
    lua_pop(state->lua, 1);
    return 1;
}

int packsanity_lua_call_string_bytes(PackSanityLuaState *state, const char *name,
                                    const char *argument, size_t length, char **result,
                                    size_t *result_length, char **error) {
    if (result == NULL || result_length == NULL) {
        if (error != NULL) *error = copy_string("String result pointer is missing");
        return 0;
    }
    *result = NULL;
    *result_length = 0;
    struct call_args args = {name, argument, length, CALL_STRING};
    if (!run_operation(state, call_function, &args, 1, error)) return 0;
    const char *value = lua_tolstring(state->lua, -1, result_length);
    if (value == NULL) {
        value = "Unknown Lua error";
        *result_length = strlen(value);
    }
    *result = malloc(*result_length + 1);
    if (*result != NULL) memcpy(*result, value, *result_length + 1);
    lua_pop(state->lua, 1);
    if (*result != NULL) return 1;
    if (error != NULL) *error = copy_string("Could not allocate Lua result");
    return 0;
}

int packsanity_lua_call_boolean(PackSanityLuaState *state, const char *name, const char *argument,
                               int *result, char **error) {
    return packsanity_lua_call_boolean_bytes(state, name, argument,
        argument == NULL ? 0 : strlen(argument), result, error);
}

int packsanity_lua_call_integer(PackSanityLuaState *state, const char *name, const char *argument,
                               int64_t *result, char **error) {
    return packsanity_lua_call_integer_bytes(state, name, argument,
        argument == NULL ? 0 : strlen(argument), result, error);
}

int packsanity_lua_call_string(PackSanityLuaState *state, const char *name, const char *argument,
                              char **result, char **error) {
    size_t result_length;
    return packsanity_lua_call_string_bytes(state, name, argument,
        argument == NULL ? 0 : strlen(argument), result, &result_length, error);
}
