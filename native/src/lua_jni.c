#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "packsanity/lua_host.h"

static PackSanityLuaState *from_handle(jlong handle) {
    return (PackSanityLuaState *)(intptr_t)handle;
}

static jstring result(JNIEnv *env, char *value) {
    jstring converted = value == NULL ? NULL : (*env)->NewStringUTF(env, value);
    packsanity_lua_free_string(value);
    return converted;
}
static jstring call_error(JNIEnv *env, char *error) {
    const char *resolved = error == NULL ? "Lua call failed" : error;
    size_t length = strlen(resolved) + 7;
    char *prefixed = malloc(length);
    if (prefixed == NULL) {
        packsanity_lua_free_string(error);
        return (*env)->NewStringUTF(env, "error:Lua call failed");
    }
    snprintf(prefixed, length, "error:%s", resolved);
    packsanity_lua_free_string(error);
    return result(env, prefixed);
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
JNIEXPORT jstring JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeRegisterModule(JNIEnv *env, jclass clazz, jlong handle, jstring name, jstring source, jstring chunk) {
    (void)clazz;
    char *error = NULL;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    const char *s = (*env)->GetStringUTFChars(env, source, NULL);
    const char *c = (*env)->GetStringUTFChars(env, chunk, NULL);
    int ok = packsanity_lua_register_module(from_handle(handle), n, s, c, &error);
    (*env)->ReleaseStringUTFChars(env, name, n);
    (*env)->ReleaseStringUTFChars(env, source, s);
    (*env)->ReleaseStringUTFChars(env, chunk, c);
    return ok ? NULL : result(env, error);
}
JNIEXPORT jstring JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeRegisterPackFile(JNIEnv *env, jclass clazz, jlong handle, jstring path, jbyteArray contents) {
    (void)clazz;
    char *error = NULL;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    jbyte *bytes = (*env)->GetByteArrayElements(env, contents, NULL);
    jsize length = (*env)->GetArrayLength(env, contents);
    int ok = packsanity_lua_register_pack_file(from_handle(handle), p, (const char *)bytes, (size_t)length, &error);
    (*env)->ReleaseStringUTFChars(env, path, p);
    (*env)->ReleaseByteArrayElements(env, contents, bytes, JNI_ABORT);
    return ok ? NULL : result(env, error);
}
JNIEXPORT jstring JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeExecute(JNIEnv *env, jclass clazz, jlong handle, jstring source, jstring chunk) {
    (void)clazz;
    char *error = NULL;
    const char *s = (*env)->GetStringUTFChars(env, source, NULL);
    const char *c = (*env)->GetStringUTFChars(env, chunk, NULL);
    int ok = packsanity_lua_execute(from_handle(handle), s, c, &error);
    (*env)->ReleaseStringUTFChars(env, source, s);
    (*env)->ReleaseStringUTFChars(env, chunk, c);
    return ok ? NULL : result(env, error);
}
JNIEXPORT jstring JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeSetProviderCount(JNIEnv *env, jclass clazz, jlong handle, jstring code, jint count) {
    (void)clazz;
    char *error = NULL;
    const char *c = (*env)->GetStringUTFChars(env, code, NULL);
    int ok = packsanity_lua_set_provider_count(from_handle(handle), c, count, &error);
    (*env)->ReleaseStringUTFChars(env, code, c);
    return ok ? NULL : result(env, error);
}
JNIEXPORT jstring JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeCallBoolean(JNIEnv *env, jclass clazz, jlong handle, jstring name, jstring argument) {
    (void)clazz;
    char *error = NULL;
    int value = 0;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    const char *a = (*env)->GetStringUTFChars(env, argument, NULL);
    int ok = packsanity_lua_call_boolean(from_handle(handle), n, a, &value, &error);
    (*env)->ReleaseStringUTFChars(env, name, n);
    (*env)->ReleaseStringUTFChars(env, argument, a);
    return ok ? (*env)->NewStringUTF(env, value ? "true" : "false") : call_error(env, error);
}
JNIEXPORT jstring JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeCallInteger(JNIEnv *env, jclass clazz, jlong handle, jstring name, jstring argument) {
    (void)clazz;
    char *error = NULL;
    int64_t value = 0;
    char buffer[64];
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    const char *a = (*env)->GetStringUTFChars(env, argument, NULL);
    int ok = packsanity_lua_call_integer(from_handle(handle), n, a, &value, &error);
    (*env)->ReleaseStringUTFChars(env, name, n);
    (*env)->ReleaseStringUTFChars(env, argument, a);
    if (!ok) return call_error(env, error);
    snprintf(buffer, sizeof(buffer), "%lld", (long long)value);
    return (*env)->NewStringUTF(env, buffer);
}
JNIEXPORT jstring JNICALL Java_dev_nattle_packsanity_lua_LuaNativeRuntime_nativeCallString(JNIEnv *env, jclass clazz, jlong handle, jstring name, jstring argument) {
    (void)clazz;
    char *error = NULL;
    char *value = NULL;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    const char *a = (*env)->GetStringUTFChars(env, argument, NULL);
    int ok = packsanity_lua_call_string(from_handle(handle), n, a, &value, &error);
    (*env)->ReleaseStringUTFChars(env, name, n);
    (*env)->ReleaseStringUTFChars(env, argument, a);
    return ok ? result(env, value) : call_error(env, error);
}
