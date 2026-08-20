package com.sopcam.archive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.sopcam.meta.ImageMeta
import com.sopcam.meta.MediaWriter
import com.sopcam.watermark.Anchor
import com.sopcam.watermark.WatermarkContent
import com.sopcam.watermark.WatermarkRenderer
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

/**
 * 从归档的原图重新烧一张水印照片写回相册。
 *
 * 这是整个归档机制存在的理由：相册里的成片删了、水印排版要改了、
 * 当初忘了开水印开关，都能拿原图重来一遍。
 */
object Restorer {

    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val timeFmt = SimpleDateFormat("HHmm", Locale.CHINA)

    /**
     * 恢复一张。返回是否成功。
     *
     * overwrite=false：相册里已经有同名的就跳过。批量恢复走这条，
     *   不然 MediaStore 会自动改名成「xxx (1).jpg」，越恢复越多。
     * overwrite=true：先删旧的再写。改水印位置、旋转、重拍都要走这条，
     *   否则用户点了保存却看不到相册变化。
     */
    /** 一张的结局。以前只返回 Boolean，跳过和写成功分不开，数出来的数字会撒谎 */
    enum class Outcome { WRITTEN, SKIPPED, FAILED }

    data class Result(val written: Int, val skipped: Int, val failed: Int) {
        val total: Int get() = written + skipped + failed
    }

    fun one(
        ctx: Context,
        raw: File,
        jpegQuality: Int = 92,
        overwrite: Boolean = false,
        taken: MutableSet<String>? = null,
    ): Outcome = runCatching {
        val side = Archive.sidecar(raw) ?: JSONObject()
        val serialNo = side.optString("serialNo").ifBlank { raw.parentFile?.name ?: "" }
        val at = side.optLong("capturedAt", raw.lastModified())

        // 原图落盘前已经物理转正，且是从 Bitmap 直接编码的、不带 EXIF，
        // 所以这里普通解码就行，不用再看方向标记
        val bmp = BitmapFactory.decodeFile(
            raw.path,
            BitmapFactory.Options().apply { inMutable = true }
        ) ?: return false

        val lines = side.optJSONArray("lines")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        } ?: emptyList()
        val headline = side.optString("headline").ifBlank { null }
        val anchor = runCatching { Anchor.valueOf(side.optString("anchor")) }
            .getOrDefault(Anchor.BOTTOM_LEFT)

        // 旋转在烧水印之前做：转完之后水印才会落在【成片画面】的角上，
        // 而且文字始终正立 —— 「图转水印不动」就是这么来的。
        // 归档原图本身不动，只在 json 里记角度，随时能改回去。
        val rotation = ((side.optInt("rotation", 0) % 360) + 360) % 360
        val canvas = if (rotation == 0) bmp else {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (turned !== bmp) bmp.recycle()
            turned
        }

        // 当初关着水印拍的（没记下任何水印文字），恢复出来也应该是干净的图
        if (headline != null || lines.isNotEmpty()) {
            WatermarkRenderer.burnIn(canvas, WatermarkContent(headline, lines), anchor)
        }

        val bytes = ByteArrayOutputStream(canvas.byteCount / 6).use { out ->
            canvas.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            out.toByteArray()
        }
        // 转 90/270 之后宽高互换，这两个值传错相册缩略图的比例就是歪的
        val w = canvas.width
        val h = canvas.height
        canvas.recycle()

        val meta = ImageMeta(
            imageId = side.optString("imageId").ifBlank { UUID.randomUUID().toString() },
            capturedAt = at,
            serialNo = serialNo,
            modelName = side.optString("modelName"),
            platformName = side.optString("platformName"),
            faultType = side.optString("faultType"),
            stepOrder = side.optInt("stepOrder", 0),
            stepName = side.optString("stepName"),
            stepRefDes = side.optString("stepRefDes"),
            codeValue = side.optString("codeValue"),
            codeFormat = side.optString("codeFormat"),
            anchor = side.optString("anchor"),
            topEdge = side.optString("topEdge"),
            hasWatermark = headline != null || lines.isNotEmpty(),
        )

        val display = side.optString("fileName").ifBlank { fallbackName(side, at) } + ".jpg"
        val path = side.optString("relativePath").ifBlank { fallbackPath(serialNo, at) }

        // 同一步骤同一分钟内拍的两张，算出来的名字是一样的 ——
        // 不错开的话第二张会被当成"已经有了"跳过，两张原图只落成一个成片。
        val unique = taken?.let { claim(it, path, display) } ?: display

        val existing = Gallery.fileAt(path, unique)
        if (existing.exists()) {
            if (!overwrite) return Outcome.SKIPPED
            Gallery.delete(ctx, listOf(existing))
        }

        MediaWriter.write(ctx, bytes, unique, path, meta, w, h)
        Outcome.WRITTEN
    }.getOrDefault(Outcome.FAILED)

    /** 这一轮里已经用掉的名字，撞了就加序号 */
    private fun claim(taken: MutableSet<String>, path: String, display: String): String {
        val stem = display.substringBeforeLast('.')
        val ext = display.substringAfterLast('.', "jpg")
        var name = display
        var i = 2
        while (!taken.add("$path|$name")) {
            name = stem + "_" + i + "." + ext
            i++
        }
        return name
    }

    /** 整个项目恢复。返回成功张数 */
    fun project(
        ctx: Context,
        serialNo: String,
        overwrite: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result {
        val shots = Archive.shots(serialNo)
        val taken = mutableSetOf<String>()
        var written = 0
        var skipped = 0
        var failed = 0
        shots.forEachIndexed { i, f ->
            when (one(ctx, f, overwrite = overwrite, taken = taken)) {
                Outcome.WRITTEN -> written++
                Outcome.SKIPPED -> skipped++
                Outcome.FAILED -> failed++
            }
            onProgress(i + 1, shots.size)
        }
        return Result(written, skipped, failed)
    }

    /* 老版本存的 json 缺文件名和路径，按当初的规则重新拼一个 */

    private fun fallbackName(side: JSONObject, at: Long): String {
        val order = side.optInt("stepOrder", 0)
        val name = side.optString("stepName")
        val head = if (order > 0) {
            listOf(order.toString().padStart(2, '0'), name).filter { it.isNotBlank() }
                .joinToString("_")
        } else "FREE"
        return head + "_" + timeFmt.format(Date(at))
    }

    private fun fallbackPath(serialNo: String, at: Long): String =
        "DCIM/SopCam/${dayFmt.format(Date(at))}/" + serialNo.ifBlank { "未命名" } + "/"
}
