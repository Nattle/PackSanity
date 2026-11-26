#ifndef PACKSANITY_LUA_HOST_H
#define PACKSANITY_LUA_HOST_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Opaque bounded state. Create one state per serialized pack session and close it. */
typedef struct PackSanityLuaState PackSanityLuaState;

/** Returns NULL when limits are invalid or the Lua state cannot be allocated. */
PackSanityLuaState *packsanity_lua_create(int64_t memory_limit_bytes, int64_t instruction_limit);
void packsanity_lua_begin_refresh(PackSanityLuaState *state, int64_t limit);
void packsanity_lua_end_refresh(PackSanityLuaState *state);
void packsanity_lua_close(PackSanityLuaState *state);
/** All fallible calls return 1 on success. If supplied, *error is heap allocated and must be freed. */
int packsanity_lua_register_module(PackSanityLuaState *state, const char *name, const char *source, const char *chunk_name, char **error);
/** Registers source that is compiled only when Lua's require() loads the module. */
int packsanity_lua_register_lazy_module(PackSanityLuaState *state, const char *name, const char *source, const char *chunk_name, char **error);
int packsanity_lua_register_pack_file(PackSanityLuaState *state, const char *path, const void *contents, size_t contents_length, char **error);
int packsanity_lua_execute(PackSanityLuaState *state, const char *source, const char *chunk_name, char **error);
int packsanity_lua_set_provider_count(PackSanityLuaState *state, const char *code, int count, char **error);
int packsanity_lua_call_boolean(PackSanityLuaState *state, const char *function_name, const char *argument, int *result, char **error);
int packsanity_lua_call_integer(PackSanityLuaState *state, const char *function_name, const char *argument, int64_t *result, char **error);
int packsanity_lua_call_string(PackSanityLuaState *state, const char *function_name, const char *argument, char **result, char **error);
/* Lengths count bytes and exclude the terminating NUL. These variants also accept NULs in the
 * argument and report their presence in the string result by returning its full length. */
int packsanity_lua_call_boolean_bytes(PackSanityLuaState *state, const char *function_name, const char *argument, size_t length, int *result, char **error);
int packsanity_lua_call_integer_bytes(PackSanityLuaState *state, const char *function_name, const char *argument, size_t length, int64_t *result, char **error);
int packsanity_lua_call_string_bytes(PackSanityLuaState *state, const char *function_name, const char *argument, size_t length, char **result, size_t *result_length, char **error);
void packsanity_lua_free_string(char *value);

#ifdef __cplusplus
}
#endif

#endif
