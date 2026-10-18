package com.garrietta.relay

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash diagnosability: Garrett's device has no logcat access from here, so
 * the app captures its own uncaught exceptions to a private file.
 *
 * - Installed first in [RelayApp.onCreate], before anything else runs.
 * - The previous default handler is chained, so the system still shows the
 *   "keeps stopping" dialog and kills the process normally.
 * - [lastCrash] returns the captured report (or null); the Settings screen
 *   shows it and MainActivity shows a one-time notice on next launch.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val FILE_NAME = "relay_last_crash.txt"
    private const val MAX_CHARS = 4000

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val trace = Log.getStackTraceString(throwable)
                File(app.filesDir, FILE_NAME).writeText(
                    "[$ts] thread=${thread.name}\n${throwable.javaClass.name}: ${throwable.message}\n$trace"
                        .take(MAX_CHARS),
                )
                Log.w(TAG, "captured crash report (${trace.length} chars)")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** The captured crash report, or null if none was recorded. */
    fun lastCrash(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText()
    }.getOrNull()

    /** Short one-line summary for Settings / dialogs. */
    fun lastCrashSummary(context: Context): String? {
        val full = lastCrash(context) ?: return null
        // Skip the timestamp line; the exception line is the useful part.
        return full.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("[") }
            ?.take(160)
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
