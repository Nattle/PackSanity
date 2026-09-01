#ifndef PACKSANITY_PYTHON_H
#define PACKSANITY_PYTHON_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct PackSanityPythonState PackSanityPythonState;

/*
 * Starts a session interpreter within the process-wide CPython runtime. `python_home` is the
 * bundled CPython home and `module_path` contains PackSanity's adapter and its trimmed AP runtime.
 * Each public call returns zero on failure and stores an owned UTF-8 diagnostic in `error`.
 */
PackSanityPythonState *packsanity_python_create(
    const char *python_home,
    const char *module_path,
    char **error
);

/* JSON input/output only. The exported operations are intentionally fixed to the adapter API. */
int packsanity_python_load_world(
    PackSanityPythonState *state,
    const char *request_json,
    char **result_json,
    char **error
);
int packsanity_python_load_tracker_package(
    PackSanityPythonState *state,
    const char *request_json,
    char **result_json,
    char **error
);
int packsanity_python_initialize_tracker(
    PackSanityPythonState *state,
    const char *request_json,
    char **result_json,
    char **error
);
int packsanity_python_update_state(
    PackSanityPythonState *state,
    const char *state_json,
    char **result_json,
    char **error
);

/* Destroys the session interpreter. CPython's main interpreter stays process-lifetime. */
void packsanity_python_close(PackSanityPythonState *state);
void packsanity_python_free_string(char *value);

#ifdef __cplusplus
}
#endif

#endif
