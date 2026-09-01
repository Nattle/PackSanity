/* Execute serialized bridge commands on the main thread or a fresh pthread.
 * Used by the Python regression suite and real-archive validation. */
#include "packsanity/python_host.h"
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static PackSanityPythonState *states[8];
static const char *python_home;
static const char *module_path;

static void *execute(void *argument) {
    char *command = argument;
    char *separator = strchr(command, ':');
    if (separator == NULL) exit(3);
    *separator = '\0';
    int index = atoi(separator + 1);
    if (index < 0 || index >= 8) exit(3);
    char *request = strchr(separator + 1, ':');
    request = request == NULL ? "" : request + 1;
    char *result = NULL, *error = NULL;
    int ok = 0;
    if (strcmp(command, "create") == 0) {
        if (states[index] != NULL) exit(3);
        states[index] = packsanity_python_create(python_home, *request ? request : module_path, &error);
        ok = states[index] != NULL;
    } else if (strcmp(command, "close") == 0) {
        packsanity_python_close(states[index]);
        states[index] = NULL;
        ok = 1;
    } else if (strcmp(command, "load") == 0) {
        ok = packsanity_python_load_world(states[index], request, &result, &error);
    } else if (strcmp(command, "initialize") == 0) {
        ok = packsanity_python_initialize_tracker(states[index], request, &result, &error);
    } else if (strcmp(command, "update") == 0) {
        ok = packsanity_python_update_state(states[index], request, &result, &error);
    } else {
        exit(3);
    }
    printf("%s %s\n", ok ? "OK" : "ERROR", ok ? (result ? result : "null") : (error ? error : "unknown"));
    fflush(stdout);
    packsanity_python_free_string(result);
    packsanity_python_free_string(error);
    return NULL;
}

int main(int argc, char **argv) {
    if (argc < 4) return 3;
    python_home = argv[1];
    module_path = argv[2];
    for (int i = 3; i < argc; i++) {
        if (strncmp(argv[i], "worker:", 7) == 0) {
            pthread_t worker;
            if (pthread_create(&worker, NULL, execute, argv[i] + 7) != 0) return 3;
            if (pthread_join(worker, NULL) != 0) return 3;
        } else {
            execute(argv[i]);
        }
    }
    for (int i = 0; i < 8; i++) packsanity_python_close(states[i]);
    return 0;
}
