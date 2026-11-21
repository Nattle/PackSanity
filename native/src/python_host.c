#include "packsanity/python_host.h"

#include <Python.h>

#include <pthread.h>
#include <stdlib.h>
#include <string.h>

struct PackSanityPythonState {
    PyObject *adapter;
    PyInterpreterState *interpreter;
};

// The main interpreter owns process startup only. Each runtime owns a separate interpreter.
static PyThreadState *main_thread_state = NULL;
static pthread_mutex_t startup_lock = PTHREAD_MUTEX_INITIALIZER;

static char *copy_string(const char *value) {
    const char *resolved = value == NULL ? "Unknown CPython error" : value;
    size_t length = strlen(resolved) + 1;
    char *copy = malloc(length);
    if (copy != NULL) memcpy(copy, resolved, length);
    return copy;
}

void packsanity_python_free_string(char *value) { free(value); }

static void set_python_error(char **error) {
    PyObject *type = NULL;
    PyObject *value = NULL;
    PyObject *traceback = NULL;
    PyErr_Fetch(&type, &value, &traceback);
    PyErr_NormalizeException(&type, &value, &traceback);
    PyObject *text = value == NULL ? NULL : PyObject_Str(value);
    const char *message = text == NULL ? NULL : PyUnicode_AsUTF8(text);
    if ((message == NULL || message[0] == '\0') && type != NULL && PyType_Check(type)) {
        message = ((PyTypeObject *) type)->tp_name;
    }
    if (error != NULL) *error = copy_string(message);
    // Formatting the exception can itself set an error. The host has already captured it.
    PyErr_Clear();
    Py_XDECREF(text);
    Py_XDECREF(traceback);
    Py_XDECREF(value);
    Py_XDECREF(type);
}

static int append_sys_path(const char *module_path, char **error) {
    PyObject *path = PySys_GetObject("path");
    PyObject *entry = PyUnicode_FromString(module_path);
    if (path == NULL || entry == NULL || PyList_Append(path, entry) != 0) {
        Py_XDECREF(entry);
        set_python_error(error);
        return 0;
    }
    Py_DECREF(entry);
    return 1;
}

PackSanityPythonState *packsanity_python_create(
    const char *python_home,
    const char *module_path,
    char **error
) {
    if (error != NULL) *error = NULL;
    if (python_home == NULL || module_path == NULL) {
        if (error != NULL) *error = copy_string("CPython runtime has invalid paths");
        return NULL;
    }

    pthread_mutex_lock(&startup_lock);
    if (main_thread_state == NULL) {
        PyStatus status;
        PyConfig config;
        PyConfig_InitPythonConfig(&config);
        config.parse_argv = 0;
        config.site_import = 0;
        config.install_signal_handlers = 0;
        status = PyConfig_SetBytesString(&config, &config.home, python_home);
        if (!PyStatus_Exception(status)) {
            status = PyConfig_SetBytesString(&config, &config.program_name, "PackSanity");
        }
        if (!PyStatus_Exception(status)) status = Py_InitializeFromConfig(&config);
        PyConfig_Clear(&config);
        if (PyStatus_Exception(status)) {
            if (error != NULL) *error = copy_string(status.err_msg);
            pthread_mutex_unlock(&startup_lock);
            return NULL;
        }
        main_thread_state = PyThreadState_Get();
    } else {
        PyEval_RestoreThread(main_thread_state);
    }

    PackSanityPythonState *state = calloc(1, sizeof(PackSanityPythonState));
    if (state == NULL) {
        if (error != NULL) *error = copy_string("Could not allocate CPython state");
        PyEval_SaveThread();
        pthread_mutex_unlock(&startup_lock);
        return NULL;
    }
    // APWorld registration and LogicMixin imports mutate interpreter globals. A fresh
    // subinterpreter isolates them without finalizing process-wide CPython on a worker thread.
    // Use the shared GIL and explicit thread states; PyGILState_* targets the main interpreter.
    PyInterpreterConfig interpreter_config = {
        .use_main_obmalloc = 1,
        .allow_fork = 0,
        .allow_exec = 0,
        .allow_threads = 1,
        .allow_daemon_threads = 0,
        .check_multi_interp_extensions = 0,
        .gil = PyInterpreterConfig_SHARED_GIL,
    };
    PyThreadState *thread = NULL;
    PyStatus interpreter_status = Py_NewInterpreterFromConfig(&thread, &interpreter_config);
    if (PyStatus_Exception(interpreter_status)) {
        if (error != NULL) *error = copy_string(interpreter_status.err_msg);
        free(state);
        PyEval_SaveThread();
        pthread_mutex_unlock(&startup_lock);
        return NULL;
    }
    state->interpreter = PyThreadState_GetInterpreter(thread);
    if (!append_sys_path(module_path, error)) {
        Py_EndInterpreter(thread);
        free(state);
        pthread_mutex_unlock(&startup_lock);
        return NULL;
    }
    state->adapter = PyImport_ImportModule("packsanity_ut_adapter");
    if (state->adapter == NULL) {
        set_python_error(error);
        Py_EndInterpreter(thread);
        free(state);
        pthread_mutex_unlock(&startup_lock);
        return NULL;
    }
    // GCD can move a serialized session between OS threads. Never retain a thread state
    // created on another thread: its thread-local bookkeeping can outlive a later close.
    PyThreadState_Clear(thread);
    PyThreadState_DeleteCurrent();
    pthread_mutex_unlock(&startup_lock);
    return state;
}

static int call_adapter(
    PackSanityPythonState *state,
    const char *function_name,
    const char *argument,
    char **result_json,
    char **error
) {
    if (result_json != NULL) *result_json = NULL;
    if (error != NULL) *error = NULL;
    if (state == NULL || state->adapter == NULL || argument == NULL) {
        if (error != NULL) *error = copy_string("Universal Tracker runtime is closed or request is invalid");
        return 0;
    }
    PyThreadState *thread = PyThreadState_New(state->interpreter);
    if (thread == NULL) {
        if (error != NULL) *error = copy_string("Could not allocate CPython thread state");
        return 0;
    }
    PyEval_RestoreThread(thread);
    PyObject *function = PyObject_GetAttrString(state->adapter, function_name);
    PyObject *request = PyUnicode_FromString(argument);
    PyObject *result = NULL;
    if (function == NULL || request == NULL || !PyCallable_Check(function)) {
        set_python_error(error);
        Py_XDECREF(request);
        Py_XDECREF(function);
        PyThreadState_Clear(thread);
        PyThreadState_DeleteCurrent();
        return 0;
    }
    result = PyObject_CallOneArg(function, request);
    Py_DECREF(request);
    Py_DECREF(function);
    if (result == NULL) {
        set_python_error(error);
        PyThreadState_Clear(thread);
        PyThreadState_DeleteCurrent();
        return 0;
    }
    const char *value = PyUnicode_AsUTF8(result);
    if (value == NULL) {
        set_python_error(error);
        Py_DECREF(result);
        PyThreadState_Clear(thread);
        PyThreadState_DeleteCurrent();
        return 0;
    }
    if (result_json != NULL) *result_json = copy_string(value);
    Py_DECREF(result);
    if (result_json == NULL || *result_json == NULL) {
        if (error != NULL) *error = copy_string("Could not allocate CPython result");
        PyThreadState_Clear(thread);
        PyThreadState_DeleteCurrent();
        return 0;
    }
    PyThreadState_Clear(thread);
    PyThreadState_DeleteCurrent();
    return 1;
}

int packsanity_python_load_world(
    PackSanityPythonState *state,
    const char *request_json,
    char **result_json,
    char **error
) {
    return call_adapter(state, "load_world", request_json, result_json, error);
}

int packsanity_python_load_tracker_package(
    PackSanityPythonState *state,
    const char *request_json,
    char **result_json,
    char **error
) {
    return call_adapter(state, "load_tracker_package", request_json, result_json, error);
}

int packsanity_python_initialize_tracker(
    PackSanityPythonState *state,
    const char *request_json,
    char **result_json,
    char **error
) {
    return call_adapter(state, "initialize_runtime", request_json, result_json, error);
}

int packsanity_python_update_state(
    PackSanityPythonState *state,
    const char *state_json,
    char **result_json,
    char **error
) {
    return call_adapter(state, "update_state", state_json, result_json, error);
}

void packsanity_python_close(PackSanityPythonState *state) {
    if (state == NULL) return;
    if (Py_IsInitialized()) {
        PyThreadState *thread = PyThreadState_New(state->interpreter);
        if (thread == NULL) return; // Keep the interpreter alive rather than freeing its owner.
        PyEval_RestoreThread(thread);
        PyObject *shutdown = PyObject_GetAttrString(state->adapter, "shutdown_tracker");
        if (shutdown != NULL && PyCallable_Check(shutdown)) {
            PyObject *ignored = PyObject_CallNoArgs(shutdown);
            Py_XDECREF(ignored);
        }
        Py_XDECREF(shutdown);
        PyErr_Clear();
        Py_DECREF(state->adapter);
        Py_EndInterpreter(thread);
    }
    free(state);
}
