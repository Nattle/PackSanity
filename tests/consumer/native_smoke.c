#include "packsanity/lua_host.h"
#include "packsanity/python_host.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void require(int ok, const char *error) {
    if (ok) return;
    fprintf(stderr, "PackSanity consumer failure: %s\n", error ? error : "unexpected state");
    exit(1);
}

int main(int argc, char **argv) {
    if (argc != 4) return 2;
    char *error = NULL;
    PackSanityLuaState *lua = packsanity_lua_create(8 * 1024 * 1024, 100000);
    require(lua != NULL, NULL);
    int ok = packsanity_lua_execute(lua, "function answer(_) return 42 end", "@consumer", &error);
    require(ok, error);
    int64_t answer = 0;
    ok = packsanity_lua_call_integer(lua, "answer", "", &answer, &error);
    require(ok && answer == 42, error);
    packsanity_lua_close(lua);

    char load[4096];
    int length = snprintf(load, sizeof(load), "{\"apworld_path\":\"%s\",\"package_root\":\"tiny_world\"}", argv[3]);
    require(length > 0 && length < (int)sizeof(load), NULL);
    for (int lifecycle = 0; lifecycle < 2; lifecycle++) {
        PackSanityPythonState *python = packsanity_python_create(argv[1], argv[2], &error);
        require(python != NULL, error);
        char *result = NULL;
        ok = packsanity_python_load_world(python, load, &result, &error);
        require(ok, error);
        packsanity_python_free_string(result);
        ok = packsanity_python_initialize_tracker(python,
            "{\"game\":\"PackSanity World\",\"slot\":1,\"slot_name\":\"Smoke\",\"slot_data\":{}}", &result, &error);
        require(ok, error);
        packsanity_python_free_string(result);
        ok = packsanity_python_update_state(python,
            "{\"missing_locations\":[],\"checked_locations\":[2001],"
            "\"received_items\":[{\"item_id\":1001,\"location_id\":2001,\"player\":1,\"flags\":1}]}",
            &result, &error);
        require(ok, error);
        require(strstr(result, "\"id\": 2001") && strstr(result, "\"checked\": true") &&
                strstr(result, "\"count\": 1"), result);
        packsanity_python_free_string(result);
        packsanity_python_close(python);
    }
    puts("PACKSANITY_IOS_OK");
}
