#ifndef PACKSANITY_JNI_UTF8_H
#define PACKSANITY_JNI_UTF8_H

#include <jni.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>

static void throw_ascii(JNIEnv *env, const char *class_name, const char *message) {
    jclass type = (*env)->FindClass(env, class_name);
    if (type != NULL) (*env)->ThrowNew(env, type, message);
}

static char *read_utf8(JNIEnv *env, jbyteArray array, size_t *length) {
    if ((*env)->ExceptionCheck(env)) return NULL;
    jsize count = (*env)->GetArrayLength(env, array);
    char *text = malloc((size_t)count + 1);
    if (text == NULL) {
        throw_ascii(env, "java/lang/OutOfMemoryError", "Could not copy UTF-8 input");
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, array, 0, count, (jbyte *)text);
    if ((*env)->ExceptionCheck(env)) {
        free(text);
        return NULL;
    }
    text[count] = '\0';
    if (length != NULL) *length = (size_t)count;
    return text;
}

static jbyteArray utf8_result(JNIEnv *env, const char *text, size_t length) {
    if (length > INT_MAX) {
        throw_ascii(env, "java/lang/OutOfMemoryError", "UTF-8 result is too large");
        return NULL;
    }
    jbyteArray array = (*env)->NewByteArray(env, (jsize)length);
    if (array != NULL) (*env)->SetByteArrayRegion(env, array, 0, (jsize)length, (const jbyte *)text);
    return array;
}

static void throw_utf8(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jbyteArray bytes = utf8_result(env, message, strlen(message));
    if (bytes == NULL || (*env)->ExceptionCheck(env)) return;
    jclass strings = (*env)->FindClass(env, "java/lang/String");
    if (strings == NULL) return;
    jmethodID decode = (*env)->GetMethodID(env, strings, "<init>", "([BLjava/lang/String;)V");
    if (decode == NULL) return;
    jstring encoding = (*env)->NewStringUTF(env, "UTF-8");
    if (encoding == NULL) return;
    jobject description = (*env)->NewObject(env, strings, decode, bytes, encoding);
    if (description == NULL) return;
    jclass errors = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (errors == NULL) return;
    jmethodID constructor = (*env)->GetMethodID(env, errors, "<init>", "(Ljava/lang/String;)V");
    if (constructor == NULL) return;
    jthrowable error = (jthrowable)(*env)->NewObject(env, errors, constructor, description);
    if (error != NULL) (*env)->Throw(env, error);
}

#endif
