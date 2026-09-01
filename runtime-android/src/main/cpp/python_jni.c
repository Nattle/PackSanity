#include <jni.h>
#include <stdint.h>

#include "packsanity/python_host.h"

static PackSanityPythonState *from_handle(jlong handle) {
    return (PackSanityPythonState *)(intptr_t)handle;
}

static void throw_error(JNIEnv *env, char *message) {
    jclass exception = (*env)->FindClass(env, "java/lang/IllegalStateException");
    (*env)->ThrowNew(env, exception, message == NULL ? "Embedded CPython call failed" : message);
    packsanity_python_free_string(message);
}

JNIEXPORT jlong JNICALL
Java_dev_nattle_packsanity_ut_PythonNativeRuntime_nativeCreate(
    JNIEnv *env, jclass clazz, jstring python_home, jstring module_path
) {
    (void)clazz;
    char *error = NULL;
    const char *home = (*env)->GetStringUTFChars(env, python_home, NULL);
    const char *modules = (*env)->GetStringUTFChars(env, module_path, NULL);
    PackSanityPythonState *state = packsanity_python_create(home, modules, &error);
    (*env)->ReleaseStringUTFChars(env, python_home, home);
    (*env)->ReleaseStringUTFChars(env, module_path, modules);
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

JNIEXPORT jstring JNICALL
Java_dev_nattle_packsanity_ut_PythonNativeRuntime_nativeCall(
    JNIEnv *env, jclass clazz, jlong handle, jint operation, jstring request_json
) {
    (void)clazz;
    char *result = NULL;
    char *error = NULL;
    const char *request = (*env)->GetStringUTFChars(env, request_json, NULL);
    int ok;
    switch (operation) {
        case 0: ok = packsanity_python_load_tracker_package(from_handle(handle), request, &result, &error); break;
        case 1: ok = packsanity_python_load_world(from_handle(handle), request, &result, &error); break;
        case 2: ok = packsanity_python_initialize_tracker(from_handle(handle), request, &result, &error); break;
        case 3: ok = packsanity_python_update_state(from_handle(handle), request, &result, &error); break;
        default: ok = 0; error = NULL; break;
    }
    (*env)->ReleaseStringUTFChars(env, request_json, request);
    if (!ok) {
        throw_error(env, error);
        return NULL;
    }
    jstring converted = (*env)->NewStringUTF(env, result);
    packsanity_python_free_string(result);
    return converted;
}
