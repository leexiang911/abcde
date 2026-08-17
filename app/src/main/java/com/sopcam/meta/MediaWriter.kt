package com.sopcam.meta

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 往相册写一张成片，带 EXIF 和 XMP。
 *
 * 拍照和「恢复水印」两条路都要走这套流程，抽出来共用 ——
 * 复制一份的话，以后改了元数据字段总会漏掉其中一边。
 */
object MediaWriter {

    private val exifDateFmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    data class Written(val uri: String, val displayName: String, val widthPx: Int, val heightPx: Int)

    fun write(
        context: Context,
        bytes: ByteArray,
        display: String,
        path: String,
        meta: ImageMeta,
        w: Int,
        h: Int,
    ): Written {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, display)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.WIDTH, w)
            put(MediaStore.Images.Media.HEIGHT, h)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert 失败：$display")

        resolver.openOutputStream(uri)!!.use { it.write(bytes) }

        // EXIF 走标准字段。图已物理正立，Orientation 必须写 NORMAL，
        // 否则电脑端看图软件会照着标记再转一次。
        resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
            ExifInterface(pfd.fileDescriptor).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString()
                )
                val stamp = exifDateFmt.format(Date(meta.capturedAt))
                setAttribute(ExifInterface.TAG_DATETIME, stamp)
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
                setAttribute(ExifInterface.TAG_MAKE, meta.deviceMake)
                setAttribute(ExifInterface.TAG_MODEL, meta.deviceModel)
                setAttribute(ExifInterface.TAG_SOFTWARE, "SopCam")
                setAttribute(
                    ExifInterface.TAG_IMAGE_DESCRIPTION,
                    listOf(meta.stepName, meta.serialNo).filter { it.isNotBlank() }
                        .joinToString(" / ")
                )
                if (meta.latitude != null && meta.longitude != null) {
                    setLatLong(meta.latitude, meta.longitude)
                }
                saveAttributes()
            }
        }

        // XMP 走自定义命名空间。必须在 ExifInterface 之后插 ——
        // saveAttributes 会重写整个 JPEG 段结构，先插进去有被丢掉的风险。
        runCatching {
            val current = resolver.openInputStream(uri)!!.use { it.readBytes() }
            val withXmp = Xmp.inject(current, Xmp.build(meta))
            resolver.openOutputStream(uri, "wt")!!.use { it.write(withXmp) }
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Written(uri.toString(), display, w, h)
    }
}
