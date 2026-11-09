// JNI bridge to libwebp's WebPAnimDecoder for StickerBridge.
//
// Frames are returned one at a time, fully composited on the canvas, as
// non-premultiplied ARGB ints (MODE_BGRA bytes == 0xAARRGGBB little-endian ints),
// so only one frame is held in Java memory at a time.

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "src/webp/decode.h"
#include "src/webp/demux.h"

typedef struct {
    uint8_t* data;          // copy of the WEBP file; must outlive the decoder
    size_t size;
    WebPAnimDecoder* dec;
    WebPAnimInfo info;
} Handle;

JNIEXPORT jlong JNICALL
Java_dev_stickerbridge_app_convert_WebpAnimDecoder_nativeOpen(JNIEnv* env, jclass clazz, jbyteArray bytes) {
    (void) clazz;
    jsize len = (*env)->GetArrayLength(env, bytes);
    if (len <= 0) return 0;
    Handle* h = (Handle*) calloc(1, sizeof(Handle));
    if (h == NULL) return 0;
    h->data = (uint8_t*) malloc((size_t) len);
    if (h->data == NULL) { free(h); return 0; }
    (*env)->GetByteArrayRegion(env, bytes, 0, len, (jbyte*) h->data);
    h->size = (size_t) len;

    WebPAnimDecoderOptions opts;
    if (!WebPAnimDecoderOptionsInit(&opts)) { free(h->data); free(h); return 0; }
    opts.color_mode = MODE_BGRA;
    opts.use_threads = 0;

    WebPData data = { h->data, h->size };
    h->dec = WebPAnimDecoderNew(&data, &opts);
    if (h->dec == NULL || !WebPAnimDecoderGetInfo(h->dec, &h->info)) {
        if (h->dec != NULL) WebPAnimDecoderDelete(h->dec);
        free(h->data);
        free(h);
        return 0;
    }
    return (jlong) (intptr_t) h;
}

// [canvasWidth, canvasHeight, loopCount, frameCount]
JNIEXPORT jintArray JNICALL
Java_dev_stickerbridge_app_convert_WebpAnimDecoder_nativeInfo(JNIEnv* env, jclass clazz, jlong ptr) {
    (void) clazz;
    Handle* h = (Handle*) (intptr_t) ptr;
    jint values[4] = {
        (jint) h->info.canvas_width, (jint) h->info.canvas_height,
        (jint) h->info.loop_count, (jint) h->info.frame_count,
    };
    jintArray out = (*env)->NewIntArray(env, 4);
    if (out != NULL) (*env)->SetIntArrayRegion(env, out, 0, 4, values);
    return out;
}

// Per-frame durations in ms, read from the demuxer (no decoding).
JNIEXPORT jintArray JNICALL
Java_dev_stickerbridge_app_convert_WebpAnimDecoder_nativeDurations(JNIEnv* env, jclass clazz, jlong ptr) {
    (void) clazz;
    Handle* h = (Handle*) (intptr_t) ptr;
    const WebPDemuxer* demux = WebPAnimDecoderGetDemuxer(h->dec);
    int n = (int) h->info.frame_count;
    jintArray out = (*env)->NewIntArray(env, n);
    if (out == NULL) return NULL;
    jint* tmp = (jint*) malloc(sizeof(jint) * (size_t) (n > 0 ? n : 1));
    if (tmp == NULL) return out;
    for (int i = 0; i < n; ++i) {
        WebPIterator it;
        tmp[i] = 0;
        if (WebPDemuxGetFrame(demux, i + 1, &it)) {
            tmp[i] = it.duration;
            WebPDemuxReleaseIterator(&it);
        }
    }
    (*env)->SetIntArrayRegion(env, out, 0, n, tmp);
    free(tmp);
    return out;
}

// Decodes the next frame into argbOut (canvasWidth * canvasHeight ints).
// Returns the frame's end timestamp in ms, or -1 when there are no more frames / on error.
JNIEXPORT jint JNICALL
Java_dev_stickerbridge_app_convert_WebpAnimDecoder_nativeNext(JNIEnv* env, jclass clazz, jlong ptr, jintArray argbOut) {
    (void) clazz;
    Handle* h = (Handle*) (intptr_t) ptr;
    if (!WebPAnimDecoderHasMoreFrames(h->dec)) return -1;
    uint8_t* buf = NULL;
    int timestamp = 0;
    if (!WebPAnimDecoderGetNext(h->dec, &buf, &timestamp) || buf == NULL) return -1;
    jsize pixels = (jsize) (h->info.canvas_width * h->info.canvas_height);
    if ((*env)->GetArrayLength(env, argbOut) < pixels) return -1;
    (*env)->SetIntArrayRegion(env, argbOut, 0, pixels, (const jint*) buf);
    return (jint) timestamp;
}

JNIEXPORT void JNICALL
Java_dev_stickerbridge_app_convert_WebpAnimDecoder_nativeReset(JNIEnv* env, jclass clazz, jlong ptr) {
    (void) env; (void) clazz;
    Handle* h = (Handle*) (intptr_t) ptr;
    WebPAnimDecoderReset(h->dec);
}

JNIEXPORT void JNICALL
Java_dev_stickerbridge_app_convert_WebpAnimDecoder_nativeClose(JNIEnv* env, jclass clazz, jlong ptr) {
    (void) env; (void) clazz;
    Handle* h = (Handle*) (intptr_t) ptr;
    if (h == NULL) return;
    if (h->dec != NULL) WebPAnimDecoderDelete(h->dec);
    free(h->data);
    free(h);
}
