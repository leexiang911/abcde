package com.sopcam.archive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.sopcam.meta.ImageMeta
import com.sopcam.meta.MediaWriter
import com.sopcam.watermark.Anchor
import com.sopcam.watermark.WatermarkContent
import com.sopcam.watermark.WatermarkRenderer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 从归档的原图重新烧一张水印照片写回相册。
 *
 * 这是整个归档机制存在的理由：相册里的成片删了、水印排版要改了、
 * 当初忘了开水印开关，都能拿原图重来一遍。
 */
object Restorer {

    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val timeFmt = SimpleDateFormat("HHmm", Locale.CHINA)

    /** 恢复一张。返回是否成功 */
    fun one(ctx: Context, raw: File, jpegQuality: Int = 92): Boolean = runCatching {
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

        // 当初关着水印拍的（没记下任何水印文字），恢复出来也应该是干净的图
        if (headline != null || lines.isNotEmpty()) {
            WatermarkRenderer.burnIn(bmp, WatermarkContent(headline, lines), anchor)
        }

        val bytes = ByteArrayOutputStream(bmp.byteCount / 6).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            out.toByteArray()
        }
        val w = bmp.width
        val h = bmp.height
        bmp.recycle()

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

        MediaWriter.write(ctx, bytes, display, path, meta, w, h)
        true
    }.getOrDefault(false)

    /** 整个项目恢复。返回成功张数 */
    fun project(
        ctx: Context,
        serialNo: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Int {
        val shots = Archive.shots(serialNo)
        var ok = 0
        shots.forEachIndexed { i, f ->
            if (one(ctx, f)) ok++
            onProgress(i + 1, shots.size)
        }
        return ok
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
