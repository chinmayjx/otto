// JNI for Echo.kt: one Speex echo canceller with its preprocessor (which takes out what's left of the echo and
// some noise), working on frames of 16-bit mono PCM.
#include <jni.h>
#include <stdlib.h>
#include "speex/speex_echo.h"
#include "speex/speex_preprocess.h"

typedef struct {
    SpeexEchoState *echo;
    SpeexPreprocessState *pre;
    int frame;
} Echo;

JNIEXPORT jlong JNICALL Java_io_github_otto_Echo_create(JNIEnv *env, jclass c, jint frame, jint tail, jint rate) {
    Echo *e = calloc(1, sizeof(Echo));
    e->frame = frame;
    e->echo = speex_echo_state_init(frame, tail);
    speex_echo_ctl(e->echo, SPEEX_ECHO_SET_SAMPLING_RATE, &rate);
    e->pre = speex_preprocess_state_init(frame, rate);
    speex_preprocess_ctl(e->pre, SPEEX_PREPROCESS_SET_ECHO_STATE, e->echo);
    int on = 1;
    speex_preprocess_ctl(e->pre, SPEEX_PREPROCESS_SET_DENOISE, &on);
    return (jlong) (intptr_t) e;
}

// mic and ref in, the mic with the echo taken out in out; all `frame` samples.
JNIEXPORT void JNICALL Java_io_github_otto_Echo_process(JNIEnv *env, jclass c, jlong h, jshortArray mic, jshortArray ref, jshortArray out) {
    Echo *e = (Echo *) (intptr_t) h;
    jshort *m = (*env)->GetShortArrayElements(env, mic, NULL);
    jshort *r = (*env)->GetShortArrayElements(env, ref, NULL);
    jshort *o = (*env)->GetShortArrayElements(env, out, NULL);
    speex_echo_cancellation(e->echo, m, r, o);
    speex_preprocess_run(e->pre, o);
    (*env)->ReleaseShortArrayElements(env, mic, m, JNI_ABORT);
    (*env)->ReleaseShortArrayElements(env, ref, r, JNI_ABORT);
    (*env)->ReleaseShortArrayElements(env, out, o, 0);
}

JNIEXPORT void JNICALL Java_io_github_otto_Echo_destroy(JNIEnv *env, jclass c, jlong h) {
    Echo *e = (Echo *) (intptr_t) h;
    speex_preprocess_state_destroy(e->pre);
    speex_echo_state_destroy(e->echo);
    free(e);
}
