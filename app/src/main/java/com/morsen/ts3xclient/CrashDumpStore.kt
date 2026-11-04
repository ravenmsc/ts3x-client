package com.morsen.ts3xclient

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashDumpStore {
    private const val DIR_NAME = "crash_dumps"
    private const val MAX_DUMPS = 12

    fun install(context: Context) {
        val appContext = context.applicationContext
        capturePreviousAbnormalExit(appContext)
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        if (previous is CrashHandler) return

        Thread.setDefaultUncaughtExceptionHandler(
            CrashHandler(appContext, previous)
        )
    }

    fun list(context: Context): List<File> =
        dumpDir(context)
            .listFiles { file ->
                file.isFile && file.extension.equals("txt", ignoreCase = true)
            }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun latest(context: Context): File? = list(context).firstOrNull()

    fun count(context: Context): Int = list(context).size

    fun clear(context: Context) {
        list(context).forEach { runCatching { it.delete() } }
    }

    fun exportBundle(context: Context): File? {
        val dumps = list(context)
        if (dumps.isEmpty()) return null

        val exportDir = File(context.cacheDir, "crash_exports").apply { mkdirs() }
        val out = File(exportDir, "TS3X-Crash-Reports.txt")
        out.bufferedWriter().use { writer ->
            dumps.forEachIndexed { index, file ->
                if (index > 0) {
                    writer.appendLine()
                    writer.appendLine("============================================================")
                    writer.appendLine()
                }
                writer.appendLine("Report: ${file.name}")
                writer.appendLine()
                writer.append(file.readText())
            }
        }
        return out
    }


    private fun appVersion(context: Context): String {
        return runCatching {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                0
            )

            val versionName = info.versionName ?: "unknown"
            val versionCode =
                if (Build.VERSION.SDK_INT >= 28) {
                    info.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toString()
                }

            "$versionName ($versionCode)"
        }.getOrElse {
            "unknown"
        }
    }

    private fun capturePreviousAbnormalExit(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return

        runCatching {
            val activityManager = context.getSystemService(ActivityManager::class.java)
                ?: return
            val exit = activityManager
                .getHistoricalProcessExitReasons(context.packageName, 0, 6)
                .firstOrNull {
                    it.reason == ApplicationExitInfo.REASON_CRASH ||
                        it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                        it.reason == ApplicationExitInfo.REASON_ANR
                }
                ?: return

            val prefs = context.getSharedPreferences("ts3x_crash_dumps", Context.MODE_PRIVATE)
            val lastCaptured = prefs.getLong("last_exit_timestamp", 0L)
            if (exit.timestamp <= lastCaptured) return

            val reason = when (exit.reason) {
                ApplicationExitInfo.REASON_ANR -> "ANR"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                else -> "Crash"
            }

            val trace = runCatching {
                exit.traceInputStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()

            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
                .format(Date(exit.timestamp))
            val file = File(dumpDir(context), "exit-$stamp.txt")
            file.bufferedWriter().use { writer ->
                writer.appendLine("TS3X Client abnormal exit report")
                writer.appendLine("Timestamp: ${Date(exit.timestamp)}")
                writer.appendLine("Version: ${appVersion(context)}")
                writer.appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                writer.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                writer.appendLine("Reason: $reason")
                writer.appendLine("Description: ${exit.description.orEmpty()}")
                writer.appendLine("Importance: ${exit.importance}")
                writer.appendLine()
                if (trace.isNotBlank()) writer.append(trace)
            }

            prefs.edit().putLong("last_exit_timestamp", exit.timestamp).apply()
            list(context).drop(MAX_DUMPS).forEach { old -> runCatching { old.delete() } }
        }
    }

    private fun dumpDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        runCatching {
            val dir = dumpDir(context)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val file = File(dir, "crash-$stamp.txt")
            val trace = StringWriter().also { sw ->
                throwable.printStackTrace(PrintWriter(sw))
            }.toString()

            file.bufferedWriter().use { writer ->
                writer.appendLine("TS3X Client crash report")
                writer.appendLine("Timestamp: ${Date()}")
                writer.appendLine("Version: ${appVersion(context)}")
                writer.appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                writer.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                writer.appendLine("Thread: ${thread.name} [id=${thread.id}]")
                writer.appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
                writer.appendLine()
                writer.append(trace)
            }

            list(context)
                .drop(MAX_DUMPS)
                .forEach { old -> runCatching { old.delete() } }
        }
    }

    private class CrashHandler(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            writeCrash(context, thread, throwable)
            previous?.uncaughtException(thread, throwable)
                ?: run {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
        }
    }
}
