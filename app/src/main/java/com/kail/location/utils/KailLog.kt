package com.kail.location.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import com.kail.location.viewmodels.SettingsViewModel
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * KailLog 专门用于日志输出与保存。
 * 默认不写文件；用户开启日志后写低频日志，开启详细调试后限流写高频日志。
 * 注意：避免使用 SimpleDateFormat，防止在 Xposed/系统线程中触发 ICU 相关崩溃。
 */
object KailLog {
    private const val TAG_PREFIX = "KailLog_"
    private const val HIGH_FREQ_FILE_INTERVAL_MS = 1000L
    private const val MAX_THROTTLE_KEYS = 256
    private const val MAX_LOG_FILE_SIZE_BYTES = 2L * 1024L * 1024L
    private const val MAX_ROTATED_FILES = 3
    private const val SU_RETRY_COOLDOWN_MS = 60_000L

    private val logExecutor = Executors.newSingleThreadExecutor()
    private val highFreqLastWriteMs = ConcurrentHashMap<String, Long>()
    private val callerCache = ConcurrentHashMap<String, String>()

    @Volatile private var xposedLogMethod: Method? = null
    @Volatile private var xposedLogResolved = false
    @Volatile private var cachedContext: Context? = null

    @Volatile var fileLogEnabled = false
    @Volatile var detailedLogEnabled = false

    /**
     * 输出日志。
     *
     * 未开启日志时不写文件；W/E 仍输出到 Logcat/LSPosed 方便排查严重问题。
     * 高频日志仅在详细调试开启时输出并限流写入文件。
     */
    fun log(context: Context?, tag: String, message: String, isHighFrequency: Boolean = false, level: Char = 'd') {
        val resolvedContext = context ?: resolveContext()
        updateFlagsFromContext(resolvedContext)

        val normalizedLevel = level.lowercaseChar()
        val isWarningOrError = normalizedLevel == 'w' || normalizedLevel == 'e'
        val shouldEmit = isWarningOrError || if (isHighFrequency) detailedLogEnabled else fileLogEnabled
        if (!shouldEmit) return

        val callerFileName = getCallerFileName()
        val throttleKey = if (isHighFrequency) "$tag:$callerFileName" else null
        if (throttleKey != null && !allowHighFrequency(throttleKey)) return

        val freqIndicator = if (isHighFrequency) "[H]" else "[L]"
        val fullMessage = "$freqIndicator [$callerFileName] $message"
        val fullTag = "$TAG_PREFIX$freqIndicator$tag"

        xposedLogMethod()?.let { method ->
            kotlin.runCatching { method.invoke(null, "$fullTag: $fullMessage") }
        }

        when (normalizedLevel) {
            'i' -> Log.i(fullTag, fullMessage)
            'w' -> Log.w(fullTag, fullMessage)
            'e' -> Log.e(fullTag, fullMessage)
            else -> Log.d(fullTag, fullMessage)
        }

        val shouldWriteFile = if (isHighFrequency) detailedLogEnabled else fileLogEnabled
        if (shouldWriteFile) {
            saveLogToPrivateFile(resolvedContext, fullTag, fullMessage, normalizedLevel)
        }
    }

    fun d(context: Context?, tag: String, message: String, isHighFrequency: Boolean = false) = log(context, tag, message, isHighFrequency, 'd')
    fun i(context: Context?, tag: String, message: String, isHighFrequency: Boolean = false) = log(context, tag, message, isHighFrequency, 'i')
    fun w(context: Context?, tag: String, message: String, isHighFrequency: Boolean = false) = log(context, tag, message, isHighFrequency, 'w')
    fun e(context: Context?, tag: String, message: String, isHighFrequency: Boolean = false) = log(context, tag, message, isHighFrequency, 'e')

    private fun updateFlagsFromContext(context: Context?) {
        kotlin.runCatching {
            if (context != null && context.packageName != "android") {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                fileLogEnabled = prefs.getBoolean(SettingsViewModel.KEY_LOG_ENABLED, false)
                detailedLogEnabled = prefs.getBoolean(SettingsViewModel.KEY_DEBUG_LOG_ENABLED, false)
            }
        }
    }

    /**
     * 在 Xposed 环境下尝试获取 Application Context。
     */
    private fun resolveContext(): Context? {
        cachedContext?.let { return it }
        return kotlin.runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val m = at.getDeclaredMethod("currentApplication")
            (m.invoke(null) as? Context)?.applicationContext
        }.getOrNull()?.also {
            cachedContext = it
        }
    }

    private fun xposedLogMethod(): Method? {
        if (xposedLogResolved) return xposedLogMethod
        synchronized(this) {
            if (!xposedLogResolved) {
                xposedLogMethod = kotlin.runCatching {
                    Class.forName("de.robv.android.xposed.XposedBridge")
                        .getDeclaredMethod("log", String::class.java)
                }.getOrNull()
                xposedLogResolved = true
            }
            return xposedLogMethod
        }
    }

    private fun getCallerFileName(): String {
        val stackTrace = Thread.currentThread().stackTrace
        for (i in 2 until stackTrace.size) {
            val frame = stackTrace[i]
            if (frame.className != KailLog::class.java.name && !frame.className.contains("java.lang.Thread")) {
                return callerCache.getOrPut(frame.className) { frame.fileName ?: "Unknown" }
            }
        }
        return "Unknown"
    }

    private fun allowHighFrequency(key: String): Boolean {
        val now = System.currentTimeMillis()
        if (highFreqLastWriteMs.size > MAX_THROTTLE_KEYS) {
            highFreqLastWriteMs.clear()
        }
        val last = highFreqLastWriteMs[key] ?: 0L
        if (now - last < HIGH_FREQ_FILE_INTERVAL_MS) return false
        highFreqLastWriteMs[key] = now
        return true
    }

    private fun saveLogToPrivateFile(context: Context?, tag: String, message: String, level: Char) {
        if (context == null || context.packageName == "android") return
        val appContext = context.applicationContext ?: context
        logExecutor.execute {
            val ts = System.currentTimeMillis()
            val day = ts / 86_400_000L
            val fileName = "kail_log_${day}.txt"
            val logEntry = "${formatTime(ts)} [${level.uppercaseChar()}] [$tag]: $message\n"

            try {
                val logDir = logDir(appContext)
                if (!logDir.exists()) {
                    logDir.mkdirs()
                }

                val logFile = File(logDir, fileName)
                rotateIfNeeded(logFile)
                FileOutputStream(logFile, true).use { fos ->
                    fos.write(logEntry.toByteArray())
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun rotateIfNeeded(logFile: File) {
        if (!logFile.exists() || logFile.length() < MAX_LOG_FILE_SIZE_BYTES) return
        for (i in MAX_ROTATED_FILES downTo 1) {
            val source = if (i == 1) logFile else File(logFile.parentFile, "${logFile.name}.$i")
            if (!source.exists()) continue
            if (i == MAX_ROTATED_FILES) {
                source.delete()
            } else {
                source.renameTo(File(logFile.parentFile, "${logFile.name}.${i + 1}"))
            }
        }
    }

    private fun formatTime(ts: Long): String {
        val dayMs = 86_400_000L
        val localMs = ((ts + TimeZone.getDefault().getOffset(ts).toLong()) % dayMs + dayMs) % dayMs
        val hour = localMs / 3_600_000L
        val minute = (localMs % 3_600_000L) / 60_000L
        val second = (localMs % 60_000L) / 1000L
        val ms = localMs % 1000L
        return buildString(12) {
            appendPadded(hour, 2)
            append(':')
            appendPadded(minute, 2)
            append(':')
            appendPadded(second, 2)
            append('.')
            appendPadded(ms, 3)
        }
    }

    private fun StringBuilder.appendPadded(value: Long, width: Int) {
        val text = value.toString()
        repeat(width - text.length) { append('0') }
        append(text)
    }

    fun getLogCacheSizeBytes(context: Context? = null): Long {
        return kotlin.runCatching {
            logFiles(context).sumOf { it.length() }
        }.getOrDefault(0L)
    }

    fun clearLogCache(context: Context? = null): Boolean {
        return kotlin.runCatching {
            logFiles(context).forEach { it.delete() }
            true
        }.getOrDefault(false)
    }

    fun exportLogs(context: Context, uri: Uri): Boolean {
        return kotlin.runCatching {
            val files = logFiles(context)
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                if (files.isEmpty()) {
                    output.write("No log files found.\n".toByteArray())
                } else {
                    files.forEach { file ->
                        output.write("===== ${file.name} =====\n".toByteArray())
                        file.inputStream().use { input -> input.copyTo(output) }
                        output.write("\n".toByteArray())
                    }
                }
            } ?: return false
            true
        }.getOrDefault(false)
    }

    private fun logFiles(context: Context? = null): List<File> {
        val resolvedContext = context ?: resolveContext()
        if (resolvedContext == null || resolvedContext.packageName == "android") return emptyList()
        val dir = logDir(resolvedContext)
        return dir.listFiles { file -> file.isFile && file.name.startsWith("kail_log_") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun logDir(context: Context): File {
        return File(context.filesDir, "logs")
    }
}
