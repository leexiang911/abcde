package com.sopcam.archive

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.sopcam.meta.Xmp
import java.io.ByteArrayOutputStream
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
        settings: ExportSettings = ExportSettings(),
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
                            // 只有水印图压缩。原图归档是兜底数据，压了就失去意义了
                            val (bytes, ext) = transcode(f, settings)
                            putBytes(zip, "$sn/水印图/${f.nameWithoutExtension}.$ext", bytes, f)
                            onProgress(++done, total)
                        }
                    }
                    if (opt.original) {
                        originalsOf(sn).forEach { f ->
                            // .sopraw 只是为了躲开相册扫描，发给别人得能直接打开
                            putBytes(zip, "$sn/原图/${f.nameWithoutExtension}.jpg", f.readBytes(), f)
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

    /**
     * 按设置重新编码一张水印图。
     *
     * 返回字节和该用的扩展名。不压缩时原样返回，连解码都省了。
     */
    private fun transcode(src: File, st: ExportSettings): Pair<ByteArray, String> {
        if (!st.compresses) return src.readBytes() to "jpg"

        val bmp = BitmapFactory.decodeFile(src.path) ?: return src.readBytes() to "jpg"
        val fmt = if (st.format == ExportFormat.WEBP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        } else Bitmap.CompressFormat.JPEG

        val out = ByteArrayOutputStream(bmp.byteCount / 8).use { o ->
            bmp.compress(fmt, st.quality, o)
            o.toByteArray()
        }
        bmp.recycle()

        // compress() 出来的是纯图像数据，EXIF 和 XMP 都没了。
        // JPEG 能把 XMP 段重新插回去；WebP 要另写 RIFF chunk 写入器，这里不做。
        val ext = if (st.format == ExportFormat.WEBP) "webp" else "jpg"
        if (st.keepMetadata && ext == "jpg") {
            readXmp(src)?.let { return Xmp.inject(out, it) to ext }
        }
        return out to ext
    }

    /** 从原文件里把整段 XMP packet 抠出来，原样搬到压缩后的文件上 */
    private fun readXmp(src: File): String? = runCatching {
        val head = src.inputStream().use { ins ->
            ByteArray(minOf(src.length(), 512L * 1024).toInt()).also { ins.read(it) }
        }
        val text = String(head, Charsets.ISO_8859_1)
        val a = text.indexOf("<x:xmpmeta")
        if (a < 0) return null
        val b = text.indexOf("</x:xmpmeta>", a)
        if (b < 0) return null
        String(head, a, b + 12 - a, Charsets.UTF_8)
    }.getOrNull()

    /** STORED 模式要自己算好大小和 CRC，压缩模式才由 ZipOutputStream 代劳 */
    private fun putBytes(zip: ZipOutputStream, path: String, bytes: ByteArray, src: File) {
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
