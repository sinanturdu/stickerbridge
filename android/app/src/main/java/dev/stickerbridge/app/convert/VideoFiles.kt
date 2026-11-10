package dev.stickerbridge.app.convert

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Container checks, not a decoder or proof that Instagram will accept the video. */
object VideoFiles {
    fun validate(file: File, trace: VideoDiagnostics) {
        trace.step(VideoDiagnostics.Stage.VALIDATE, "bytes" to file.length())
        check(file.isFile && file.length() > 0)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val video = tracks.indices.single { tracks[it].getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC }
            val format = tracks[video]
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            check(width in 2..VideoPlanning.MAX_SIDE && width == height && width % 2 == 0)
            check(durationUs > 0)
            extractor.selectTrack(video)
            var samples = 0
            var firstPts = -1L
            var lastPts = -1L
            while (extractor.sampleTime >= 0) {
                val pts = extractor.sampleTime
                check(pts >= 0 && pts < durationUs)
                if (samples == 0) firstPts = pts
                lastPts = pts
                samples++
                if (!extractor.advance()) break
            }
            check(samples > 0 && firstPts == 0L)
            trace.step(VideoDiagnostics.Stage.VALIDATE, "valid" to 1, "width" to width, "height" to height,
                "duration_us" to durationUs, "samples" to samples, "first_pts_us" to firstPts,
                "last_pts_us" to lastPts, "tracks" to tracks.size)
        } finally {
            extractor.release()
        }
    }

    /** A failed move must throw; never delete a previous output to retry an unchecked rename. */
    fun publish(tmp: File, out: File) {
        check(tmp.isFile && tmp.length() > 0)
        val expected = tmp.length()
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
        check(out.isFile && out.length() == expected)
    }
}
