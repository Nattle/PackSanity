#include "packsanity/python_host.h"
#include <stdio.h>
#include <stdlib.h>

static char *read_json(const char *path) {
    FILE *file = fopen(path, "rb");
    if (!file) return NULL;
    if (fseek(file, 0, SEEK_END)) { fclose(file); return NULL; }
    long length = ftell(file);
    if (length < 0 || length > 16 * 1024 * 1024 || fseek(file, 0, SEEK_SET)) { fclose(file); return NULL; }
    char *value = malloc((size_t)length + 1);
    if (!value || fread(value, 1, (size_t)length, file) != (size_t)length) {
        free(value); fclose(file); return NULL;
    }
    value[length] = 0;
    fclose(file);
    return value;
}

int main(int argc, char **argv) {
    if (argc < 5) {
        fprintf(stderr, "usage: packsanity-python PYTHON_HOME MODULE_PATH LOAD_WORLD.json INITIALIZE.json [UPDATE.json ...]\n");
        return 2;
    }
    char *error = NULL;
    char *result = NULL;
    char *request = NULL;
    PackSanityPythonState *state = packsanity_python_create(argv[1], argv[2], &error);
    if (!state) goto failed;
    for (int index = 3; index < argc; ++index) {
        request = read_json(argv[index]);
        if (!request) { fprintf(stderr, "Cannot read request: %s\n", argv[index]); goto failed; }
        packsanity_python_free_string(result);
        result = NULL;
        int ok = index == 3 ? packsanity_python_load_world(state, request, &result, &error)
               : index == 4 ? packsanity_python_initialize_tracker(state, request, &result, &error)
                            : packsanity_python_update_state(state, request, &result, &error);
        free(request); request = NULL;
        if (!ok) goto failed;
    }
    puts(result);
    packsanity_python_free_string(result);
    packsanity_python_close(state);
    return 0;
failed:
    fprintf(stderr, "FAIL %s\n", error ? error : "CPython request failed");
    free(request);
    packsanity_python_free_string(result);
    packsanity_python_free_string(error);
    if (state) packsanity_python_close(state);
    return 1;
}
