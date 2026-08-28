package com.e2spatial.mp4tots

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

sealed class ConversionResult {
    data class Success(val outputFile: File) : ConversionResult()
    data class Failure(val message: String) : ConversionResult()
}

object Converter {

    /** Copies the picked SAF Uri into cacheDir so FFmpeg has a real filesystem path. */
    fun copyToCache(context: Context, uri: Uri, displayName: String): File {
        val outFile = File(context.cacheDir, "input_${System.currentTimeMillis()}_$displayName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not open input stream for $uri")
        return outFile
    }

    private fun detectVideoCodec(inputPath: String): String? {
        val session = FFprobeKit.getMediaInformation(inputPath)
        val info = session.mediaInformation ?: return null
        return info.streams.firstOrNull { it.type == "video" }?.codec
    }

    suspend fun convertToTs(
        inputFile: File,
        outputFile: File,
        onProgress: (Double) -> Unit
    ): ConversionResult = suspendCoroutine { continuation ->
        val codec = detectVideoCodec(inputFile.absolutePath)?.lowercase()

        val remuxCommand = when (codec) {
            "h264" -> "-i \"${inputFile.absolutePath}\" -map 0 -c copy -bsf:v h264_mp4toannexb -f mpegts \"${outputFile.absolutePath}\""
            "hevc" -> "-i \"${inputFile.absolutePath}\" -map 0 -c copy -bsf:v hevc_mp4toannexb -f mpegts \"${outputFile.absolutePath}\""
            else -> null
        }

        val fallbackCommand = "-i \"${inputFile.absolutePath}\" -c:v libx264 -preset veryfast -c:a aac -f mpegts \"${outputFile.absolutePath}\""

        fun runFallback() {
            FFmpegKit.executeAsync(
                fallbackCommand,
                { session: FFmpegSession ->
                    if (ReturnCode.isSuccess(session.returnCode)) {
                        continuation.resume(ConversionResult.Success(outputFile))
                    } else {
                        continuation.resume(
                            ConversionResult.Failure(
                                "Conversion failed (fallback re-encode). FFmpeg log:\n${session.allLogsAsString}"
                            )
                        )
                    }
                },
                null,
                { statistics: Statistics ->
                    val durationMs = statistics.time
                    if (durationMs > 0) onProgress(durationMs.toDouble())
                }
            )
        }

        if (remuxCommand == null) {
            runFallback()
            return@suspendCoroutine
        }

        FFmpegKit.executeAsync(
            remuxCommand,
            { session: FFmpegSession ->
                if (ReturnCode.isSuccess(session.returnCode)) {
                    continuation.resume(ConversionResult.Success(outputFile))
                } else {
                    // Stream-copy remux failed unexpectedly; fall back to a full re-encode.
                    runFallback()
                }
            },
            null,
            { statistics: Statistics ->
                val durationMs = statistics.time
                if (durationMs > 0) onProgress(durationMs.toDouble())
            }
        )
    }
}
