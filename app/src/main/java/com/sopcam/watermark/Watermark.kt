package com.sopcam.watermark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.SensorManager
import android.view.OrientationEventListener
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import kotlin.math.max
import kotlin.math.min

/* ------------------------------------------------------------------
 * 1. 方向模型
 * ------------------------------------------------------------------ */

/** 水印贴在成片的哪个角。注意：这是"成片正立后"的角，不是手机屏幕的角。 */
enum class Anchor { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * 构图方向锁。
 * AUTO      —— 跟随手机重力，横拿出横图、竖拿出竖图
 * PORTRAIT  —— 无论怎么拿，成片永远是竖构图
 * LANDSCAPE —— 无论怎么拿，成片永远是横构图（检修台上单手斜举时最有用）
 */
enum class OrientationLock { AUTO, PORTRAIT, LANDSCAPE }

/**
 * 把方向锁翻译成 CameraX 的 targetRotation。
 *
 * 用法：
 *   val oc = OrientationController(ctx) { rot -> imageCapture.targetRotation = rot }
 *   oc.lock = OrientationLock.LANDSCAPE
 *   oc.enable()  / oc.disable()
 *
 * 锁定模式下监听器仍然运行但直接返回固定值，这样切回 AUTO 时能立刻拿到当前角度，
 * 不用等下一次重力变化。
 */
class OrientationController(
    context: Context,
    private val onRotationChanged: (Int) -> Unit,
) {
    var lock: OrientationLock = OrientationLock.AUTO
        set(value) {
            field = value
            onRotationChanged(resolve(lastDeviceRotation))
        }

    private var lastDeviceRotation = Surface.ROTATION_0
    private var lastEmitted = Int.MIN_VALUE

    private val listener = object : OrientationEventListener(
        context, SensorManager.SENSOR_DELAY_NORMAL
    ) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            // 45° 死区，避免临界角来回抖动
            lastDeviceRotation = when (orientation) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            val resolved = resolve(lastDeviceRotation)
            if (resolved != lastEmitted) {
                lastEmitted = resolved
                onRotationChanged(resolved)
            }
        }
    }

    private fun resolve(deviceRotation: Int): Int = when (lock) {
        OrientationLock.AUTO -> deviceRotation
        OrientationLock.PORTRAIT -> Surface.ROTATION_0
        // ROTATION_90 = 手机左转横拿的构图；机身右转横拿请用 ROTATION_270
        OrientationLock.LANDSCAPE ->
            if (deviceRotation == Surface.ROTATION_270) Surface.ROTATION_270
            else Surface.ROTATION_90
    }

    fun enable() { if (listener.canDetectOrientation()) listener.enable() }
    fun disable() = listener.disable()
}

/* ------------------------------------------------------------------
 * 2. 水印内容与样式
 * ------------------------------------------------------------------ */

data class WatermarkContent(
    /** 强调行，通常是当前 SOP 步骤，例如 "U7 · STM32G474 · Pin 12–15" */
    val headline: String? = null,
    /** 普通行：时间、工单号、设备编号、工位、语音备注…… */
    val lines: List<String> = emptyList(),
)

data class WatermarkStyle(
    /** 以成片"短边"为基准，保证 12MP 和 50MP 出来的水印视觉大小一致 */
    val headlineSizeRatio: Float = 0.030f,
    val bodySizeRatio: Float = 0.022f,
    val marginRatio: Float = 0.028f,
    val padRatio: Float = 0.016f,
    val lineGapRatio: Float = 0.34f,      // 相对字号
    val radiusRatio: Float = 0.010f,
    val headlineColor: Int = Color.parseColor("#FFD24A"),
    val bodyColor: Int = Color.WHITE,
    val scrimColor: Int = 0xA6101418.toInt(),
) {
    /** 现场强光下可切到这个：不透明底 + 更大字号 */
    fun highContrast() = copy(
        headlineSizeRatio = 0.034f,
        bodySizeRatio = 0.025f,
        scrimColor = 0xF0101418.toInt(),
    )
}

/* ------------------------------------------------------------------
 * 3. 渲染
 * ------------------------------------------------------------------ */

object WatermarkRenderer {

    /**
     * 把 JPEG 字节解成"已经正立"的可变 Bitmap。
     *
     * 这一步是全流程的关键：成片先物理旋转到正立，EXIF Orientation 写 NORMAL，
     * 之后无论用 Windows 照片、WPS、还是 Python PIL 打开，水印都在同一个角。
     * 否则会出现"手机上看是左下角，传到电脑变成右上角"的经典问题。
     *
     * @param maxLongSide 长边上限，超过则按 2 的幂降采样。检修留档 4000 完全够用，
     *                    比 200MP 原图快一个数量级，也省网盘空间。传 0 表示不缩。
     */
    fun decodeUpright(jpeg: ByteArray, maxLongSide: Int = 4032): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)

        val opts = BitmapFactory.Options().apply {
            inMutable = true                       // 直接解出可变位图，省一次整图拷贝
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = calcSampleSize(
                max(bounds.outWidth, bounds.outHeight), maxLongSide
            )
        }
        val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            ?: error("JPEG 解码失败")

        val orientation = ByteArrayInputStream(jpeg).use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        }
        return applyExif(raw, orientation)
    }

    private fun calcSampleSize(longSide: Int, limit: Int): Int {
        if (limit <= 0 || longSide <= limit) return 1
        var s = 1
        while (longSide / (s * 2) >= limit) s *= 2
        return s
    }

    private fun applyExif(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    /**
     * 在正立位图上原地烧录水印。src 必须是 mutable（decodeUpright 已保证）。
     * 文字永远水平可读，锚点相对成片本身，不受拍摄时手机姿态影响。
     */
    fun burnIn(
        src: Bitmap,
        content: WatermarkContent,
        anchor: Anchor,
        style: WatermarkStyle = WatermarkStyle(),
    ): Bitmap {
        val w = src.width
        val h = src.height
        val base = min(w, h).toFloat()

        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.headlineColor
            textSize = base * style.headlineSizeRatio
            isFakeBoldText = true
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.bodyColor
            textSize = base * style.bodySizeRatio
        }

        data class Row(val text: String, val paint: Paint)
        val rows = buildList {
            content.headline?.takeIf { it.isNotBlank() }?.let { add(Row(it, headPaint)) }
            content.lines.filter { it.isNotBlank() }.forEach { add(Row(it, bodyPaint)) }
        }
        if (rows.isEmpty()) return src

        val pad = base * style.padRatio
        val margin = base * style.marginRatio
        val gaps = rows.map { it.paint.textSize * style.lineGapRatio }

        val blockW = rows.maxOf { it.paint.measureText(it.text) } + pad * 2
        val blockH = rows.sumOf { it.paint.textSize.toDouble() }.toFloat() +
                gaps.dropLast(1).sum() + pad * 2

        val left = when (anchor) {
            Anchor.TOP_LEFT, Anchor.BOTTOM_LEFT -> margin
            else -> w - margin - blockW
        }
        val top = when (anchor) {
            Anchor.TOP_LEFT, Anchor.TOP_RIGHT -> margin
            else -> h - margin - blockH
        }

        val canvas = Canvas(src)
        val r = base * style.radiusRatio
        canvas.drawRoundRect(
            RectF(left, top, left + blockW, top + blockH), r, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.scrimColor }
        )

        var y = top + pad
        rows.forEachIndexed { i, row ->
            y += row.paint.textSize            // baseline ≈ 顶部 + 字号，够用且不用测 FontMetrics
            canvas.drawText(row.text, left + pad, y, row.paint)
            if (i < rows.lastIndex) y += gaps[i]
        }
        return src
    }
}

/**
 * 成片相对于预览画面的旋转角度。
 *
 * Activity 已锁竖屏，屏幕方向恒为 ROTATION_0，所以只需看 targetRotation。
 * calibrationQuarter 是现场校准量（0..3，每档 90°）：四种旋转组合的实际方向
 * 依设备而异，写死容易反向——取景器上标错角比不标更误导人，所以留一个
 * 校准按钮，拍一张对照后定死即可。
 */
fun overlayRotationDegrees(targetRotation: Int, calibrationQuarter: Int = 0): Float {
    val q = when (targetRotation) {
        Surface.ROTATION_90 -> 3
        Surface.ROTATION_180 -> 2
        Surface.ROTATION_270 -> 1
        else -> 0
    }
    return ((((q + calibrationQuarter) % 4) + 4) % 4) * 90f
}
