package dev.stickerbridge.app.convert

import android.media.MediaCodec
import java.io.IOException

/** Fixed stage/error codes and numeric metadata only. The caller supplies a debug-only sink. */
class VideoDiagnostics(private val log: (String) -> Unit = {}) {
    enum class Stage {
        SOURCE, CACHE, DECODE, PLAN, AUDIO, CONFIGURE, FEED, INPUT_EOS, OUTPUT_EOS,
        MUX_START, MUX_WRITE, MUX_STOP, RELEASE, VALIDATE, PUBLISH, COPY, URI, HANDOFF, CHOOSER, CANCELLED,
    }

    var stage = Stage.SOURCE
        private set

    fun mark(stage: Stage) { this.stage = stage }

    fun step(stage: Stage, vararg metrics: Pair<String, Number>) {
        this.stage = stage
        log("stage=$stage" + metrics.joinToString("") { (key, value) -> " $key=$value" })
    }

    fun failure(error: Throwable) {
        val code = when (error) {
            is MediaCodec.CodecException -> "CODEC"
            is VideoTimeoutException -> "TIMEOUT"
            is OutOfMemoryError -> "OOM"
            is SecurityException -> "SECURITY"
            is IOException -> "IO"
            is IllegalArgumentException -> "ARGUMENT"
            is IllegalStateException -> "STATE"
            else -> "OTHER"
        }
        val codecCode = if (error is MediaCodec.CodecException) error.errorCode else 0
        log("stage=$stage error=$code codec_code=$codecCode")
    }

    fun cleanupFailure() = log("stage=CLEANUP error=RELEASE")
}

class VideoTimeoutException : IllegalStateException()
