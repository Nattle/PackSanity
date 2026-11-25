#include <stdint.h>

#include "../../../../native/src/jni_utf8.h"
#include "packsanity/python_host.h"

static PackSanityPythonState *from_handle(jlong handle) {
    return (PackSanityPythonState *)(intptr_t)handle;
}

static void throw_error(JNIEnv *env, char *message) {
    throw_utf8(env, message == NULL ? "Embedded CPython call failed" : message);
    packsanity_python_free_string(message);
}

JNIEXPORT jlong JNICALL
Java_dev_nattle_packsanity_ut_PythonNativeRuntime_nativeCreateUtf8(
    JNIEnv *env, jclass clazz, jbyteArray python_home, jbyteArray module_path
) {
    (void)clazz;
    char *error = NULL;
    char *home = read_utf8(env, python_home, NULL);
    char *modules = read_utf8(env, module_path, NULL);
    PackSanityPythonState *state = home == NULL || modules == NULL
        ? NULL : packsanity_python_create(home, modules, &error);
    free(home);
    free(modules);
    if (state == NULL) throw_error(env, error);
    return (jlong)(intptr_t)state;
}

JNIEXPORT void JNICALL
Java_dev_nattle_packsanity_ut_PythonNativeRuntime_nativeClose(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void)env;
    (void)clazz;
    packsanity_python_close(from_handle(handle));
}

JNIEXPORT jbyteArray JNICALL
Java_dev_nattle_packsanity_ut_PythonNativeRuntime_nativeCallUtf8(
    JNIEnv *env, jclass clazz, jlong handle, jint operation, jbyteArray request_json
) {
    (void)clazz;
    char *result = NULL;
    char *error = NULL;
    char *request = read_utf8(env, request_json, NULL);
    if (request == NULL) return NULL;
    int ok;
    switch (operation) {
        case 0: ok = packsanity_python_load_tracker_package(from_handle(handle), request, &result, &error); break;
        case 1: ok = packsanity_python_load_world(from_handle(handle), request, &result, &error); break;
        case 2: ok = packsanity_python_initialize_tracker(from_handle(handle), request, &result, &error); break;
        case 3: ok = packsanity_python_update_state(from_handle(handle), request, &result, &error); break;
        default: ok = 0; error = NULL; break;
    }
    free(request);
    if (!ok) {
        throw_error(env, error);
        return NULL;
    }
    jbyteArray converted = utf8_result(env, result, strlen(result));
    packsanity_python_free_string(result);
    return converted;
}
