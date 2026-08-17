package com.sopcam.archive

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把检修项目打包导出。
 *
 * 只复制，绝不动原数据 —— 导出失败也好，中途取消也好，归档区和相册都不受影响。
 */
object Exporter {

    /** 导出内容的选择 */
    data class Options(val watermarked: Boolean, val original: Boolean) {
        val any: Boolean get() = watermarked || original
    }

    data class Plan(val fileCount: Int, val bytes: Long)

    private val stampFmt = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)

    private fun exportDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SOP导出")

    fun watermarkedOf(serialNo: String): List<File> = Gallery.photosOf(serialNo)

    fun originalsOf(serialNo: String): List<File> = Archive.shots(serialNo)

    /** 先算一遍有多少张、多大，让人在按下导出之前心里有数 */
    fun plan(serials: List<String>, opt: Options): Plan {
        var n = 0
        var bytes = 0L
        serials.forEach { sn ->
            if (opt.watermarked) watermarkedOf(sn).forEach { n++; bytes += it.length() }
            if (opt.original) originalsOf(sn).forEach { n++; bytes += it.length() }
        }
        return Plan(n, bytes)
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes.toDouble() / (1L shl 20))
        else -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / 1024)
    }

    /**
     * 打包。onProgress 报的是已写入的文件数。
     *
     * 用 STORED 不压缩：JPEG 已经压过了，再 deflate 一遍烧半天 CPU 只省 1%，
     * zip 在这里的价值是"打成一个包方便发"，不是省空间。
     */
    fun export(
        serials: List<String>,
        opt: Options,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): File? {
        if (serials.isEmpty() || !opt.any) return null
        val total = plan(serials, opt).fileCount
        if (total == 0) return null

        val dir = exportDir().apply { if (!exists()) mkdirs() }
        val name = if (serials.size == 1) serials.first() else "${serials.size}个项目"
        val out = File(dir, "SOP_${name}_${stampFmt.format(Date())}.zip")

        var done = 0
        runCatching {
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                zip.setMethod(ZipOutputStream.STORED)
                serials.forEach { sn ->
                    if (opt.watermarked) {
                        watermarkedOf(sn).forEach { f ->
                            putStored(zip, "$sn/水印图/${f.name}", f)
                            onProgress(++done, total)
                        }
                    }
                    if (opt.original) {
                        originalsOf(sn).forEach { f ->
                            // .sopraw 只是为了躲开相册扫描，发给别人得能直接打开
                            putStored(zip, "$sn/原图/${f.nameWithoutExtension}.jpg", f)
                            onProgress(++done, total)
                        }
                    }
                }
            }
        }.onFailure {
            out.delete()
            return null
        }
        return out
    }

    /** STORED 模式要自己算好大小和 CRC，压缩模式才由 ZipOutputStream 代劳 */
    private fun putStored(zip: ZipOutputStream, path: String, src: File) {
        val bytes = src.readBytes()
        val entry = ZipEntry(path).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
            time = src.lastModified()
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    /** 丢给微信 / 企业微信 / QQ 之类。走 FileProvider，直接传 file:// 会被系统拦下 */
    fun share(ctx: Context, zip: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", zip)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(intent, "发送检修留档"))
        }
    }
}
