package com.kitsumed.shizucallrecorder.integrations.scrcpy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

object DualChannelWavWriter {

    private const val SAMPLE_RATE = 48_000
    private const val CHANNELS = 2
    private const val BITS_PER_SAMPLE = 16
    private const val MONO_FRAME_BYTES = 2
    private const val STEREO_FRAME_BYTES = 4
    private const val CHUNK_FRAMES = 16_384

    /**
     * Creates PCM16LE WAV:
     *   left  = uplink
     *   right = downlink
     */
    fun write(
        uplinkFile: File,
        downlinkFile: File,
        outputFd: FileDescriptor
    ) {
        val uplinkFrames = uplinkFile.length() / MONO_FRAME_BYTES
        val downlinkFrames = downlinkFile.length() / MONO_FRAME_BYTES
        val totalFrames = max(uplinkFrames, downlinkFrames)

        val dataSize = totalFrames * STEREO_FRAME_BYTES
        require(dataSize <= 0xffffffffL) {
            "Recording is too large for classic WAV (>4 GiB)"
        }

        BufferedInputStream(FileInputStream(uplinkFile)).use { left ->
            BufferedInputStream(FileInputStream(downlinkFile)).use { right ->
                BufferedOutputStream(FileOutputStream(outputFd)).use { output ->
                    writeHeader(output, dataSize)

                    val leftBuffer = ByteArray(CHUNK_FRAMES * MONO_FRAME_BYTES)
                    val rightBuffer = ByteArray(CHUNK_FRAMES * MONO_FRAME_BYTES)
                    val stereoBuffer = ByteArray(CHUNK_FRAMES * STEREO_FRAME_BYTES)

                    var framesDone = 0L

                    while (framesDone < totalFrames) {
                        val framesThisPass =
                            min(CHUNK_FRAMES.toLong(), totalFrames - framesDone).toInt()
                        val bytesWanted = framesThisPass * MONO_FRAME_BYTES

                        val leftRead = readUpTo(left, leftBuffer, bytesWanted)
                        val rightRead = readUpTo(right, rightBuffer, bytesWanted)

                        var outPos = 0
                        for (frame in 0 until framesThisPass) {
                            val monoPos = frame * MONO_FRAME_BYTES

                            // L = uplink
                            if (monoPos + 1 < leftRead) {
                                stereoBuffer[outPos] = leftBuffer[monoPos]
                                stereoBuffer[outPos + 1] = leftBuffer[monoPos + 1]
                            } else {
                                stereoBuffer[outPos] = 0
                                stereoBuffer[outPos + 1] = 0
                            }

                            // R = downlink
                            if (monoPos + 1 < rightRead) {
                                stereoBuffer[outPos + 2] = rightBuffer[monoPos]
                                stereoBuffer[outPos + 3] = rightBuffer[monoPos + 1]
                            } else {
                                stereoBuffer[outPos + 2] = 0
                                stereoBuffer[outPos + 3] = 0
                            }

                            outPos += STEREO_FRAME_BYTES
                        }

                        output.write(stereoBuffer, 0, framesThisPass * STEREO_FRAME_BYTES)
                        framesDone += framesThisPass
                    }
                }
            }
        }
    }

    private fun readUpTo(
        input: BufferedInputStream,
        buffer: ByteArray,
        wanted: Int
    ): Int {
        var total = 0

        while (total < wanted) {
            val count = input.read(buffer, total, wanted - total)
            if (count < 0) break
            total += count
        }

        return total
    }

    private fun writeHeader(
        output: BufferedOutputStream,
        dataSize: Long
    ) {
        val byteRate =
            SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign =
            CHANNELS * BITS_PER_SAMPLE / 8

        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLe32(output, 36L + dataSize)
        output.write("WAVE".toByteArray(Charsets.US_ASCII))

        output.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeLe32(output, 16)
        writeLe16(output, 1) // PCM
        writeLe16(output, CHANNELS)
        writeLe32(output, SAMPLE_RATE.toLong())
        writeLe32(output, byteRate.toLong())
        writeLe16(output, blockAlign)
        writeLe16(output, BITS_PER_SAMPLE)

        output.write("data".toByteArray(Charsets.US_ASCII))
        writeLe32(output, dataSize)
    }

    private fun writeLe16(
        output: BufferedOutputStream,
        value: Int
    ) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeLe32(
        output: BufferedOutputStream,
        value: Long
    ) {
        output.write((value and 0xff).toInt())
        output.write(((value ushr 8) and 0xff).toInt())
        output.write(((value ushr 16) and 0xff).toInt())
        output.write(((value ushr 24) and 0xff).toInt())
    }
}
