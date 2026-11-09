#include <jni.h>
#include <dlfcn.h>
#include <stdbool.h>

extern jbyte blob[];
extern int blob_size;

JNIEXPORT jbyteArray JNICALL Java_com_termux_app_TermuxInstaller_getZip(JNIEnv *env, __attribute__((__unused__)) jobject This)
{
    jbyteArray ret = (*env)->NewByteArray(env, blob_size);
    (*env)->SetByteArrayRegion(env, ret, 0, blob_size, blob);
    return ret;
}

JNIEXPORT jboolean JNICALL Java_com_termux_app_serverhub_ServerManager_nativeChangePassword(JNIEnv *env, __attribute__((__unused__)) jclass clazz, jstring jpass)
{
    if (!jpass) return JNI_FALSE;
    const char *pass = (*env)->GetStringUTFChars(env, jpass, 0);
    if (!pass) return JNI_FALSE;

    void *handle = dlopen("/data/data/com.serverhub/files/usr/lib/libtermux-auth.so", RTLD_NOW);
    if (!handle) handle = dlopen("/data/data/com.termux/files/usr/lib/libtermux-auth.so", RTLD_NOW);
    if (!handle) {
        (*env)->ReleaseStringUTFChars(env, jpass, pass);
        return JNI_FALSE;
    }

    bool (*change_pw)(const char *) = (bool (*)(const char *))dlsym(handle, "termux_change_passwd");
    jboolean result = JNI_FALSE;
    if (change_pw) {
        result = change_pw(pass) ? JNI_TRUE : JNI_FALSE;
    }
    dlclose(handle);
    (*env)->ReleaseStringUTFChars(env, jpass, pass);
    return result;
}
