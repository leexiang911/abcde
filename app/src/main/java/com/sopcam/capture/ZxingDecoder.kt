package com.sopcam.capture

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.BarcodeFormat as ZFormat

/**
 * 只取红色通道当灰度。
 *
 * 这是整件事的关键。标准 RGB→灰度用亮度公式，绿色权重最高（0.587），
 * 而板子上的码恰恰是「铜色模块 + 绿油底」：
 *
 *   铜   R≈200 G≈150 B≈90  →  标准亮度 ≈158
 *   绿油 R≈30  G≈90  B≈60  →  标准亮度 ≈69     差 89
 *   只看红通道：            200 vs 30           差 170
 *
 * 对比度直接翻倍。标准灰度等于专挑了最不利的那个通道。
 *
 * 逐行取像素而不是一次 getPixels：4000x3000 的 int 数组要 48MB，
 * 叠在位图上很容易 OOM。
 */
class RedLuminanceSource private constructor(
    width: Int,
    height: Int,
    private val lum: ByteArray,
) : LuminanceSource(width, height) {

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        val out = if (row != null && row.size >= width) row else ByteArray(width)
        System.arraycopy(lum, y * width, out, 0, width)
        return out
    }

    override fun getMatrix(): ByteArray = lum

    companion object {
        fun of(bmp: Bitmap): RedLuminanceSource {
            val w = bmp.width
            val h = bmp.height
            val lum = ByteArray(w * h)
            val line = IntArray(w)
            for (y in 0 until h) {
                bmp.getPixels(line, 0, w, 0, y, w, 1)
                val base = y * w
                for (x in 0 until w) {
                    lum[base + x] = ((line[x] shr 16) and 0xFF).toByte()
                }
            }
            return RedLuminanceSource(w, h, lum)
        }
    }
}

/**
 * ZXing 侧的解码。
 *
 * 用 ZXing 不是因为它比 ML Kit 强，而是因为**它允许我自己提供灰度数据**——
 * ML Kit 只收 Bitmap，内部怎么转灰度我控制不了。
 */
object ZxingDecoder {

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(
            ZFormat.DATA_MATRIX,
            ZFormat.QR_CODE,
            ZFormat.CODE_128,
            ZFormat.CODE_39,
            ZFormat.CODE_93,
            ZFormat.EAN_13,
            ZFormat.PDF_417,
            ZFormat.AZTEC,
        ),
        DecodeHintType.TRY_HARDER to true,
    )

    private fun decode(src: LuminanceSource): Result? = runCatching {
        MultiFormatReader().apply { setHints(hints) }
            .decodeWithState(BinaryBitmap(HybridBinarizer(src)))
    }.getOrNull()

    /**
     * 三趟：红通道 → 红通道取反 → 标准灰度。
     *
     * 取反那趟是为了极性：板子上的码是浅模块深底，跟常规的黑码白底相反，
     * 解码器按标准极性做二值化就会失败。
     */
    fun scan(bmp: Bitmap): ScannedCode? {
        val red = RedLuminanceSource.of(bmp)

        decode(red)?.let { return it.toCode() }
        decode(red.invert())?.let { return it.toCode() }

        val gray = GrayLuminanceSource.of(bmp)
        decode(gray)?.let { return it.toCode() }
        decode(gray.invert())?.let { return it.toCode() }

        return null
    }

    private fun Result.toCode() = ScannedCode(text, barcodeFormat.name)
}

/** 标准亮度公式，做兜底的那一趟 */
class GrayLuminanceSource private constructor(
    width: Int,
    height: Int,
    private val lum: ByteArray,
) : LuminanceSource(width, height) {

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        val out = if (row != null && row.size >= width) row else ByteArray(width)
        System.arraycopy(lum, y * width, out, 0, width)
        return out
    }

    override fun getMatrix(): ByteArray = lum

    companion object {
        fun of(bmp: Bitmap): GrayLuminanceSource {
            val w = bmp.width
            val h = bmp.height
            val lum = ByteArray(w * h)
            val line = IntArray(w)
            for (y in 0 until h) {
                bmp.getPixels(line, 0, w, 0, y, w, 1)
                val base = y * w
                for (x in 0 until w) {
                    val p = line[x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    lum[base + x] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
                }
            }
            return GrayLuminanceSource(w, h, lum)
        }
    }
}
