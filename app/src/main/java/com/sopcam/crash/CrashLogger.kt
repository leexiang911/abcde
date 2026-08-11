package com.sopcam.crash

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志。
 *
 * 这个项目全靠 CI 构建，手机上没有调试器，adb 也未必连得通 ——
 * 运行时一崩就是黑盒。所以把堆栈写下来，下次启动直接摊在屏幕上给人看。
 *
 * 写两份：
 *  · filesDir 一份，一定写得进去，用来下次启动时显示
 *  · Download/SopCam 一份，文件管理器里能直接找到，方便发出来
 */
object CrashLogger {

    private const val LAST = "last-crash.txt"
    private val stampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    private val fileFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            // 交回系统处理，该崩还是崩 —— 吞掉异常会让 App 卡在坏状态里，更难查
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(ctx: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))

        val text = buildString {
            appendLine("时间  ${stampFmt.format(Date())}")
            appendLine("线程  ${thread.name}")
            appendLine("机型  ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(sw.toString())
        }

        File(ctx.filesDir, LAST).writeText(text)

        // Download 目录走 MediaStore，Android 10 之后没法直接往那儿写文件
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "sopcam-crash-${fileFmt.format(Date())}.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/SopCam")
                }
            }
            val uri = ctx.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let { u ->
                ctx.contentResolver.openOutputStream(u)?.use { it.write(text.toByteArray()) }
            }
        }
    }

    /** 上次崩溃的堆栈，没有就返回 null */
    fun pending(ctx: Context): String? {
        val f = File(ctx.filesDir, LAST)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, LAST).delete() }
    }
}
