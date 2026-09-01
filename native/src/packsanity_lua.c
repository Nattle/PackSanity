#include "packsanity/lua_host.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned char *read_file(const char *path, size_t *length) {
    FILE *file = fopen(path, "rb");
    unsigned char *contents;
    long size;
    if (file == NULL) return NULL;
    if (fseek(file, 0, SEEK_END) != 0 || (size = ftell(file)) < 0 || fseek(file, 0, SEEK_SET) != 0) {
        fclose(file); return NULL;
    }
    contents = malloc((size_t)size + 1);
    if (contents == NULL || fread(contents, 1, (size_t)size, file) != (size_t)size) {
        free(contents); fclose(file); return NULL;
    }
    fclose(file);
    contents[size] = '\0';
    *length = (size_t)size;
    return contents;
}

static void print_error(const char *operation, char *error) {
    fprintf(stderr, "packsanity-lua: %s: %s\n", operation, error == NULL ? "unknown Lua error" : error);
    packsanity_lua_free_string(error);
}

static int split_spec(const char *spec, char **name, const char **path) {
    char *separator = strchr(spec, '=');
    if (separator == NULL || separator == spec || separator[1] == '\0') return 0;
    *name = malloc((size_t)(separator - spec) + 1);
    if (*name == NULL) return 0;
    memcpy(*name, spec, (size_t)(separator - spec));
    (*name)[separator - spec] = '\0';
    *path = separator + 1;
    return 1;
}

int main(int argc, char **argv) {
    PackSanityLuaState *state = packsanity_lua_create(128LL * 1024 * 1024, 20000000);
    int index;
    if (state == NULL) { fprintf(stderr, "packsanity-lua: could not create Lua state\n"); return 1; }
    for (index = 1; index < argc; ++index) {
        const char *option = argv[index];
        const char *path;
        unsigned char *contents;
        size_t length;
        char *error = NULL;
        int ok = 0;
        if ((strcmp(option, "--bootstrap") == 0 || strcmp(option, "--execute") == 0) && index + 1 < argc) {
            path = argv[++index]; contents = read_file(path, &length);
            if (contents == NULL) { fprintf(stderr, "packsanity-lua: cannot read %s\n", path); goto failed; }
            ok = packsanity_lua_execute(state, (const char *)contents, path, &error);
            free(contents);
        } else if ((strcmp(option, "--pack-file") == 0 || strcmp(option, "--module") == 0 || strcmp(option, "--lazy-module") == 0) && index + 1 < argc) {
            char *name = NULL;
            if (!split_spec(argv[++index], &name, &path)) { fprintf(stderr, "packsanity-lua: expected NAME=PATH\n"); goto failed; }
            contents = read_file(path, &length);
            if (contents == NULL) { fprintf(stderr, "packsanity-lua: cannot read %s\n", path); free(name); goto failed; }
            if (strcmp(option, "--module") == 0) ok = packsanity_lua_register_module(state, name, (const char *)contents, path, &error);
            else if (strcmp(option, "--lazy-module") == 0) ok = packsanity_lua_register_lazy_module(state, name, (const char *)contents, path, &error);
            else ok = packsanity_lua_register_pack_file(state, name, contents, length, &error);
            free(contents); free(name);
        } else {
            fprintf(stderr, "Usage: packsanity-lua --bootstrap HOST [--pack-file PATH=FILE] [--module NAME=FILE] [--lazy-module NAME=FILE] --execute INIT\n");
            goto failed;
        }
        if (!ok) { print_error(option, error); goto failed; }
    }
    packsanity_lua_close(state);
    return 0;
failed:
    packsanity_lua_close(state);
    return 1;
}
