#include <stdint.h>

#include "jni_utf8.h"
#include "packsanity/lua_host.h"

static PackSanityLuaState *from_handle(jlong handle) {
    return (PackSanityLuaState *)(intptr_t)handle;
}

static void throw_error(JNIEnv *env, char *error) {
    throw_utf8(env, error == NULL ? "Lua call failed" : error);
    packsanity_lua_free_string(error);
}

JNIEXPORT jlong JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeCreate(JNIEnv *env, jclass clazz, jlong memory, jlong instructions) {
    (void)env;
    (void)clazz;
    return (jlong)(intptr_t)packsanity_lua_create(memory, instructions);
}
JNIEXPORT void JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeBeginRefresh(JNIEnv *env, jclass clazz, jlong handle, jlong limit) {
    (void)env; (void)clazz;
    packsanity_lua_begin_refresh(from_handle(handle), limit);
}
JNIEXPORT void JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeEndRefresh(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    packsanity_lua_end_refresh(from_handle(handle));
}
JNIEXPORT void JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    packsanity_lua_close(from_handle(handle));
}
JNIEXPORT void JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeRegisterModuleUtf8(
    JNIEnv *env, jclass clazz, jlong handle, jbyteArray name, jbyteArray source, jbyteArray chunk
) {
    (void)clazz;
    char *error = NULL;
    char *n = read_utf8(env, name, NULL);
    char *s = read_utf8(env, source, NULL);
    char *c = read_utf8(env, chunk, NULL);
    if (n != NULL && s != NULL && c != NULL &&
        !packsanity_lua_register_module(from_handle(handle), n, s, c, &error)) throw_error(env, error);
    free(n);
    free(s);
    free(c);
}

JNIEXPORT void JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeRegisterPackFileUtf8(
    JNIEnv *env, jclass clazz, jlong handle, jbyteArray path, jbyteArray contents
) {
    (void)clazz;
    char *p = read_utf8(env, path, NULL);
    if (p == NULL) return;
    jsize length = (*env)->GetArrayLength(env, contents);
    jbyte *bytes = length == 0 ? NULL : (*env)->GetByteArrayElements(env, contents, NULL);
    if (length == 0 || bytes != NULL) {
        char *error = NULL;
        int ok = packsanity_lua_register_pack_file(from_handle(handle), p, bytes, (size_t)length, &error);
        if (bytes != NULL) (*env)->ReleaseByteArrayElements(env, contents, bytes, JNI_ABORT);
        if (!ok) throw_error(env, error);
    }
    free(p);
}

JNIEXPORT void JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeExecuteUtf8(
    JNIEnv *env, jclass clazz, jlong handle, jbyteArray source, jbyteArray chunk
) {
    (void)clazz;
    char *error = NULL;
    char *s = read_utf8(env, source, NULL);
    char *c = read_utf8(env, chunk, NULL);
    if (s != NULL && c != NULL && !packsanity_lua_execute(from_handle(handle), s, c, &error)) {
        throw_error(env, error);
    }
    free(s);
    free(c);
}

JNIEXPORT void JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeSetProviderCountUtf8(
    JNIEnv *env, jclass clazz, jlong handle, jbyteArray code, jint count
) {
    (void)clazz;
    char *c = read_utf8(env, code, NULL);
    if (c == NULL) return;
    char *error = NULL;
    if (!packsanity_lua_set_provider_count(from_handle(handle), c, count, &error)) throw_error(env, error);
    free(c);
}

JNIEXPORT jint JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeCallNumber(
    JNIEnv *env, jclass clazz, jlong handle, jbyteArray name, jbyteArray argument, jboolean boolean_result
) {
    (void)clazz;
    char *error = NULL;
    int64_t value = 0;
    size_t length;
    char *n = read_utf8(env, name, NULL);
    char *a = read_utf8(env, argument, &length);
    if (n != NULL && a != NULL) {
        if (boolean_result) {
            int boolean_value = 0;
            if (!packsanity_lua_call_boolean_bytes(from_handle(handle), n, a, length, &boolean_value, &error)) {
                throw_error(env, error);
            }
            value = boolean_value;
        } else if (!packsanity_lua_call_integer_bytes(from_handle(handle), n, a, length, &value, &error)) {
            throw_error(env, error);
        } else if (value < INT32_MIN || value > INT32_MAX) {
            throw_ascii(env, "java/lang/NumberFormatException", "Lua result is outside the Java int range");
        }
    }
    free(n);
    free(a);
    return (jint)value;
}

JNIEXPORT jbyteArray JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeCallStringUtf8(
    JNIEnv *env, jclass clazz, jlong handle, jbyteArray name, jbyteArray argument
) {
    (void)clazz;
    jbyteArray converted = NULL;
    size_t length;
    char *n = read_utf8(env, name, NULL);
    char *a = read_utf8(env, argument, &length);
    if (n != NULL && a != NULL) {
        char *error = NULL;
        char *result = NULL;
        size_t result_length;
        if (packsanity_lua_call_string_bytes(from_handle(handle), n, a, length, &result, &result_length, &error)) {
            converted = utf8_result(env, result, result_length);
            packsanity_lua_free_string(result);
        } else {
            throw_error(env, error);
        }
    }
    free(n);
    free(a);
    return converted;
}
