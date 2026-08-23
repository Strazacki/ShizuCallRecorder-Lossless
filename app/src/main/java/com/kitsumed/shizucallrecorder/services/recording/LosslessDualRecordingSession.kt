package com.kitsumed.shizucallrecorder.services.recording

import android.app.Service
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.kitsumed.shizucallrecorder.IShellService
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData
import com.kitsumed.shizucallrecorder.integrations.scrcpy.DualChannelWavWriter
import com.kitsumed.shizucallrecorder.integrations.scrcpy.RawPcmTrackWriter
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioCodec
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioSource
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyClient
import com.kitsumed.shizucallrecorder.system.storage.SafHelper
import com.kitsumed.shizucallrecorder.utils.AppLogger
import com.kitsumed.shizucallrecorder.utils.RecordingFileNameFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Lossless dual-channel recording:
 *
 * left  = VOICE_CALL_UPLINK
 * right = VOICE_CALL_DOWNLINK
 *
 * Both scrcpy-server instances provide RAW PCM16LE. Each side is first stored as
 * timestamp-aligned mono PCM, then interleaved into one stereo WAV on release.
 */
class LosslessDualRecordingSession(
    private val context: Service,
    private val service: IShellService,
    private val metadata: EnrichedCallData,
    private val folderUri: Uri,
    private val serverPath: String,
    private val debugging: Boolean,
    private val isPaused: () -> Boolean
) {

    private data class RawTrack(
        val tempFile: File,
        val writer: RawPcmTrackWriter,
        val pipe: ParcelFileDescriptor,
        val scope: CoroutineScope,
        val client: ScrcpyClient,
        var job: Job? = null
    )

    private var uplink: RawTrack? = null
    private var downlink: RawTrack? = null

    private var outputPfd: ParcelFileDescriptor? = null

    var recordingUri: Uri? = null
        private set

    val isActivelyCapturingAudio: Boolean
        get() = uplink?.job?.isActive == true

    fun start() {
        val fileName = RecordingFileNameFormatter.formatFileName(
            context,
            metadata,
            ScrcpyAudioCodec.RAW
        )

        val safResult = SafHelper.createAudioFile(
            context,
            folderUri,
            fileName,
            "audio/wav"
        ) ?: throw PipelineInitializationException(
            userFriendlyMessage = context.getString(R.string.recording_error_file_creation),
            technicalLogMessage = "Failed to create lossless WAV output file"
        )

        recordingUri = safResult.uri
        outputPfd = safResult.descriptor

        // AudioRecord timestamps and System.nanoTime use the monotonic timebase.
        val originUs = System.nanoTime() / 1000L

        try {
            uplink = startTrack(
                source = ScrcpyAudioSource.VOICE_CALL_UPLINK,
                secondary = false,
                originUs = originUs,
                prefix = "uplink"
            )

            downlink = startTrack(
                source = ScrcpyAudioSource.VOICE_CALL_DOWNLINK,
                secondary = true,
                originUs = originUs,
                prefix = "downlink"
            )

            AppLogger.i("Lossless dual-channel RAW recording started")
        } catch (e: Exception) {
            cancel()

            if (e is PipelineInitializationException) throw e

            throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_start_failed),
                technicalLogMessage = "Failed to start lossless dual-channel capture",
                cause = e
            )
        }
    }

    private fun startTrack(
        source: ScrcpyAudioSource,
        secondary: Boolean,
        originUs: Long,
        prefix: String
    ): RawTrack {
        val temp = File.createTempFile(
            "shizucall_${prefix}_",
            ".pcm",
            context.cacheDir
        )

        val writer = RawPcmTrackWriter(temp, originUs)

        val pipe = try {
            val result = if (secondary) {
                service.startSecondaryRecording(
                    source.cliKey,
                    ScrcpyAudioCodec.RAW.cliKey,
                    0,
                    serverPath,
                    debugging
                )
            } else {
                service.startRecording(
                    source.cliKey,
                    ScrcpyAudioCodec.RAW.cliKey,
                    0,
                    serverPath,
                    debugging
                )
            }

            result ?: throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_start_failed),
                technicalLogMessage = "ShellService returned null RAW audio pipe"
            )
        } catch (e: Exception) {
            runCatching { writer.close() }
            runCatching { temp.delete() }
            throw e
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val client = ScrcpyClient(
            inputPfd = pipe,
            expectedCodec = ScrcpyAudioCodec.RAW,
            listener = object : ScrcpyClient.AudioPacketListener {
                override fun onMetadataReceived(codec: ScrcpyAudioCodec) {
                    if (codec != ScrcpyAudioCodec.RAW) {
                        AppLogger.w("Expected RAW but scrcpy-server returned ${codec.cliKey}")
                    }
                }

                override fun onAudioPacket(packet: ScrcpyClient.AudioPacket) {
                    if (!isPaused()) {
                        writer.writePacket(packet)
                    }
                }

                override fun onStreamEnd(error: String?) {
                    if (error != null) {
                        AppLogger.w("RAW audio stream ended with error: $error")
                    }
                }
            }
        )

        val track = RawTrack(
            tempFile = temp,
            writer = writer,
            pipe = pipe,
            scope = scope,
            client = client
        )

        track.job = scope.launch(Dispatchers.IO) {
            try {
                client.start()
            } catch (e: Exception) {
                AppLogger.w("RAW track reader ended: ${e.message}")
            }
        }

        return track
    }

    fun release() {
        val left = uplink
        val right = downlink

        stopTrack(left, secondary = false)
        stopTrack(right, secondary = true)

        uplink = null
        downlink = null

        val out = outputPfd

        if (left != null && right != null && out != null) {
            try {
                AppLogger.i("Creating final lossless dual-channel WAV")

                DualChannelWavWriter.write(
                    uplinkFile = left.tempFile,
                    downlinkFile = right.tempFile,
                    outputFd = out.fileDescriptor
                )

                AppLogger.i("Lossless dual-channel WAV finalized")
            } catch (e: Exception) {
                AppLogger.e("Failed to create final WAV: ${e.message}", e)
            }
        }

        runCatching { out?.close() }
        outputPfd = null

        runCatching { left?.tempFile?.delete() }
        runCatching { right?.tempFile?.delete() }
    }

    fun cancel() {
        val left = uplink
        val right = downlink

        stopTrack(left, secondary = false)
        stopTrack(right, secondary = true)

        uplink = null
        downlink = null

        runCatching { outputPfd?.close() }
        outputPfd = null

        runCatching { left?.tempFile?.delete() }
        runCatching { right?.tempFile?.delete() }

        recordingUri?.let { uri ->
            runCatching {
                DocumentFile.fromSingleUri(context, uri)?.delete()
            }
        }

        recordingUri = null
    }

    private fun stopTrack(track: RawTrack?, secondary: Boolean) {
        if (track == null) return

        runCatching {
            if (secondary) {
                service.stopSecondaryRecording()
            } else {
                service.stopRecording()
            }
        }

        runCatching {
            runBlocking {
                withTimeoutOrNull(2000L) {
                    track.job?.join()
                }
            }
        }

        runCatching { track.client.stop() }
        runCatching { track.scope.cancel() }
        runCatching { track.pipe.close() }
        runCatching { track.writer.close() }
    }
}
