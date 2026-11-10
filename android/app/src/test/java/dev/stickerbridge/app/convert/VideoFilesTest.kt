package dev.stickerbridge.app.convert

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class VideoFilesTest {
    @get:Rule val temp = TemporaryFolder()

    // Synthetic bytes exercise publication only, not MP4 validity or a platform codec.
    @Test fun publishesCompletedTemporaryFile() {
        val tmp = temp.newFile("test.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val out = File(temp.root, "test.mp4")
        VideoFiles.publish(tmp, out)
        assertArrayEquals(byteArrayOf(1, 2, 3), out.readBytes())
        assertFalse(tmp.exists())
    }

    @Test fun missingTemporaryFileCannotReturnSuccessOrDeletePreviousOutput() {
        val out = temp.newFile("test.mp4").apply { writeBytes(byteArrayOf(4, 5)) }
        try {
            VideoFiles.publish(File(temp.root, "missing.tmp"), out)
            throw AssertionError("publication must fail")
        } catch (_: IllegalStateException) {
            assertArrayEquals(byteArrayOf(4, 5), out.readBytes())
        }
    }

    @Test fun failedMoveCannotReturnSuccess() {
        val tmp = temp.newFile("test.tmp").apply { writeBytes(byteArrayOf(1)) }
        val out = File(temp.root, "missing/test.mp4")
        try {
            VideoFiles.publish(tmp, out)
            throw AssertionError("publication must fail")
        } catch (_: IOException) {
            assertFalse(out.exists())
            assertTrue(tmp.isFile)
        }
    }
}
