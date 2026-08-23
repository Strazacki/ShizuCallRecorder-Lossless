package com.kitsumed.shizucallrecorder.integrations.scrcpy

import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

/**
 * Writes one channel from scrcpy RAW stereo PCM16LE into a mono PCM16LE temporary file.
 * Packet PTS keeps independent uplink/downlink captures aligned to one monotonic origin.
 */
class RawPcmTrackWriter(
    file: File,
    private val originUs: Long
) : Closeable {

    companion object {
        private const val SAMPLE_RATE = 48_000L
        private const val INPUT_FRAME_BYTES = 4
        private const val OUTPUT_FRAME_BYTES = 2
    }

    private val output = BufferedOutputStream(FileOutputStream(file))
    private var writtenFrames = 0L

    @Synchronized
    fun writePacket(packet: ScrcpyClient.AudioPacket) {
        if (packet.isConfigPacket) return

        val data = packet.data
        val frameCount = data.size / INPUT_FRAME_BYTES
        if (frameCount <= 0) return

        val targetFrame =
            ((packet.pts - originUs).coerceAtLeast(0L) * SAMPLE_RATE) / 1_000_000L

        if (targetFrame > writtenFrames) {
            writeSilence(targetFrame - writtenFrames)
        }

        // Audio timestamps can overlap slightly. Do not write samples twice.
        val skipFrames = (writtenFrames - targetFrame)
            .coerceAtLeast(0L)
            .coerceAtMost(frameCount.toLong())
            .toInt()

        if (skipFrames >= frameCount) return

        val outputFrames = frameCount - skipFrames
        val mono = ByteArray(outputFrames * OUTPUT_FRAME_BYTES)

        var src = skipFrames * INPUT_FRAME_BYTES
        var dst = 0

        repeat(outputFrames) {
            // VOICE_CALL_UPLINK/DOWNLINK arrive as stereo PCM.
            // Keep channel 0 from each independent source.
            mono[dst] = data[src]
            mono[dst + 1] = data[src + 1]

            src += INPUT_FRAME_BYTES
            dst += OUTPUT_FRAME_BYTES
        }

        output.write(mono)
        writtenFrames += outputFrames
    }

    private fun writeSilence(frames: Long) {
        var bytesLeft = frames * OUTPUT_FRAME_BYTES
        val zeros = ByteArray(8192)

        while (bytesLeft > 0) {
            val count = minOf(bytesLeft, zeros.size.toLong()).toInt()
            output.write(zeros, 0, count)
            bytesLeft -= count
        }

        writtenFrames += frames
    }

    override fun close() {
        runCatching { output.flush() }
        runCatching { output.close() }
        AppLogger.d("RAW PCM temporary track closed: frames=$writtenFrames")
    }
}
