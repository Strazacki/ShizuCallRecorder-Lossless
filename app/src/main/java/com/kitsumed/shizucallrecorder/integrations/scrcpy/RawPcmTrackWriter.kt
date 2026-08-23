package com.kitsumed.shizucallrecorder.integrations.scrcpy

import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

/**
 * Writes channel 0 from scrcpy RAW stereo PCM16LE into mono PCM16LE.
 *
 * PTS is used only for initial alignment. After the first packet, samples are
 * written continuously to avoid clicks caused by packet timestamp jitter.
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
    private var started = false

    @Synchronized
    fun writePacket(packet: ScrcpyClient.AudioPacket) {
        if (packet.isConfigPacket) return

        val data = packet.data
        val frameCount = data.size / INPUT_FRAME_BYTES
        if (frameCount <= 0) return

        /*
         * Use PTS only once to align uplink and downlink against the same
         * CLOCK_MONOTONIC origin.
         */
        if (!started) {
            val startFrame =
                ((packet.pts - originUs).coerceAtLeast(0L) * SAMPLE_RATE) /
                    1_000_000L

            if (startFrame > 0) {
                writeSilence(startFrame)
            }

            started = true

            AppLogger.d(
                "RAW track initial alignment: pts=${packet.pts} " +
                    "origin=$originUs startFrame=$startFrame"
            )
        }

        /*
         * Each VOICE_CALL_UPLINK/DOWNLINK stream arrives as stereo PCM16LE.
         * Keep channel 0 from each stream.
         */
        val mono = ByteArray(frameCount * OUTPUT_FRAME_BYTES)

        var src = 0
        var dst = 0

        repeat(frameCount) {
            mono[dst] = data[src]
            mono[dst + 1] = data[src + 1]

            src += INPUT_FRAME_BYTES
            dst += OUTPUT_FRAME_BYTES
        }

        output.write(mono)
        writtenFrames += frameCount
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

        AppLogger.d(
            "RAW PCM temporary track closed: frames=$writtenFrames"
        )
    }
}
