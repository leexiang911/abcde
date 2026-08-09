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
 * 手机的哪条边，是成片的「顶部」。
 *
 * 比「锁横屏 / 锁竖屏」直观：直接指定成片正立后哪边朝上，
 * 而且四个方向刚好对上 CameraX 的四个 targetRotation。
 *
 * AUTO 时跟随重力，等效方向由 OrientationController 实时算出来。
 */
enum class TopEdge(val surfaceRotation: Int) {
    AUTO(-1),
    TOP(Surface.ROTATION_0),
    LEFT(Surface.ROTATION_90),
    BOTTOM(Surface.ROTATION_180),
    RIGHT(Surface.ROTATION_270);

    companion object {
        fun of(surfaceRotation: Int): TopEdge = when (surfaceRotation) {
            Surface.ROTATION_90 -> LEFT
            Surface.ROTATION_180 -> BOTTOM
            Surface.ROTATION_270 -> RIGHT
            else -> TOP
        }
    }
}

/**
 * 预览层要把水印转多少度，才跟成片一致。
 *
 * 推导：成片顶部指向手机左边时，成片的「右」方向就是手机的「上」，
 * 文字沿成片右向排列 = 在屏幕上从下往上读 = 逆时针 90°。
 * 四个方向刚好是 0/90/180/270 的整数圈，用象限数表示最不容易写错。
 */
fun TopEdge.quarterTurns(): Int = when (this) {
    TopEdge.TOP, TopEdge.AUTO -> 0
    TopEdge.RIGHT -> 1
    TopEdge.BOTTOM -> 2
    TopEdge.LEFT -> 3
}

/**
 * 成片上的某个角，落在预览的哪个角。
 *
 * 四个角按顺时针排成环：左上 → 右上 → 右下 → 左下，
 * 成片每转一个象限，环上就走一格。返回值是环上的下标。
 */
fun Anchor.previewCornerIndex(edge: TopEdge): Int {
    val cw = listOf(Anchor.TOP_LEFT, Anchor.TOP_RIGHT, Anchor.BOTTOM_RIGHT, Anchor.BOTTOM_LEFT)
    return (cw.indexOf(this) + edge.quarterTurns()) % 4
}

/**
 * 把方向设置翻译成 CameraX 的 targetRotation。
 *
 * 锁定模式下监听器照常运行，只是解析时直接返回固定值——
 * 这样切回 AUTO 能立刻拿到当前角度，不用等下一次重力变化。
 */
class OrientationController(
    context: Context,
    private val onRotationChanged: (Int) -> Unit,
) {
    var topEdge: TopEdge = TopEdge.AUTO
        set(value) {
            field = value
            emit(resolve(lastDeviceRotation))
        }

    /** 当前实际生效的 targetRotation，AUTO 时跟着重力变 */
    var effectiveRotation: Int = Surface.ROTATION_0
        private set

    private var lastDeviceRotation = Surface.ROTATION_0

    private val listener = object : OrientationEventListener(
        context, SensorManager.SENSOR_DELAY_NORMAL
    ) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            // 45 度死区，避免临界角来回抖动
            lastDeviceRotation = when (orientation) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            emit(resolve(lastDeviceRotation))
        }
    }

    private fun emit(rotation: Int) {
        if (rotation == effectiveRotation) return
        effectiveRotation = rotation
        onRotationChanged(rotation)
    }

    private fun resolve(deviceRotation: Int): Int =
        if (topEdge == TopEdge.AUTO) deviceRotation else topEdge.surfaceRotation

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
