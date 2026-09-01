#include "packsanity/lua_host.h"
#include <stdio.h>
#include <string.h>

int main(void) {
    if (packsanity_lua_create(0, 1000) != NULL) return 1;
    if (packsanity_lua_create(1, 1000) != NULL) return 2;
    PackSanityLuaState *state = packsanity_lua_create(1024 * 1024, 10000);
    if (!state) return 3;
    char *error = NULL;
    int ok = packsanity_lua_execute(state, "while true do end", "@budget", &error);
    if (ok || !error || !strstr(error, "instruction limit")) return 4;
    packsanity_lua_free_string(error); error = NULL;
    ok = packsanity_lua_execute(state, "value = string.rep('x', 2 * 1024 * 1024)", "@memory", &error);
    if (ok || !error) return 5;
    packsanity_lua_free_string(error); error = NULL;
    if (!packsanity_lua_execute(state, "value = 1", "@recovery", &error)) return 6;
    packsanity_lua_begin_refresh(state, 2000);
    ok = packsanity_lua_execute(state, "pcall(function() for i=1,10000 do end end)", "@refresh", &error);
    if (ok || !error || !strstr(error, "refresh instruction limit")) return 7;
    packsanity_lua_free_string(error); error = NULL;
    if (packsanity_lua_execute(state, "value = 2", "@latched", &error)) return 8;
    packsanity_lua_free_string(error); error = NULL;
    packsanity_lua_end_refresh(state);
    if (!packsanity_lua_execute(state, "assert(value == 1)", "@reset", &error)) return 9;
    packsanity_lua_close(state);
    return 0;
}
