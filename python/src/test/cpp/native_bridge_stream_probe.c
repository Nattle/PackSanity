/* Real-archive scenarios may carry more slot data than an OS command-line argument allows.
 * Reuse the production-bridge probe's commands, states and thread switching over stdin. */
#define _POSIX_C_SOURCE 200809L
#define main argv_probe_main
#include "native_bridge_probe.c"
#undef main
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc != 3) return 3;
    python_home = argv[1];
    module_path = argv[2];
    /* CPython configures the process stdin stream during interpreter startup. Keep the
     * command reader's buffered bytes on a separate FILE so startup cannot discard them. */
    int command_fd = dup(STDIN_FILENO);
    if (command_fd < 0) return 3;
    FILE *commands = fdopen(command_fd, "r");
    if (commands == NULL) { close(command_fd); return 3; }
    char *line = NULL;
    size_t capacity = 0;
    ssize_t length;
    while ((length = getline(&line, &capacity, commands)) >= 0) {
        if (length > 32 * 1024 * 1024) return 3;
        if (length && line[length - 1] == '\n') line[--length] = '\0';
        if (!length) continue;
        if (strncmp(line, "worker:", 7) == 0) {
            pthread_t worker;
            if (pthread_create(&worker, NULL, execute, line + 7) != 0) return 3;
            if (pthread_join(worker, NULL) != 0) return 3;
        } else {
            execute(line);
        }
    }
    free(line);
    fclose(commands);
    for (int i = 0; i < 8; i++) packsanity_python_close(states[i]);
    return 0;
}
