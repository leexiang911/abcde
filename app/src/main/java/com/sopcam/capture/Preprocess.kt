package com.sopcam.capture

import android.graphics.Bitmap

/**
 * 识别前的图像预处理。
 *
 * 核心思路：**处理完还是交给 ML Kit**。
 * ML Kit 的解码能力比 ZXing 强，它的短板只在于「怎么把彩色图变成灰度」这一步
 * 控制不了 —— 而那一步恰恰是这块板子上最关键的。喂它一张处理好的位图就行。
 */
object Preprocess {

    /**
     * 只取红色通道当灰度。
     *
     * 标准 RGB→灰度的绿色权重最高（0.587），而板子上是「铜色模块 + 绿油底」：
     *
     *   铜   R≈200 G≈150 B≈90  →  标准灰度 ≈158
     *   绿油 R≈30  G≈90  B≈60  →  标准灰度 ≈69     差 89
     *   只看红通道：            200 vs 30           差 170
     *
     * 对比度翻倍。标准灰度等于专挑了最不利的那个通道。
     */
    fun redChannel(src: Bitmap, invert: Boolean = false, stretch: Boolean = true): Bitmap? =
        channel(src, invert, stretch) { p -> (p shr 16) and 0xFF }

    /** 标准亮度，做对照的那一趟 */
    fun luma(src: Bitmap, invert: Boolean = false, stretch: Boolean = true): Bitmap? =
        channel(src, invert, stretch) { p ->
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (r * 77 + g * 150 + b * 29) shr 8
        }

    private inline fun channel(
        src: Bitmap,
        invert: Boolean,
        stretch: Boolean,
        pick: (Int) -> Int,
    ): Bitmap? = runCatching {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null

        val gray = ByteArray(w * h)
        val line = IntArray(w)
        var lo = 255
        var hi = 0

        // 逐行读，不一次性 getPixels —— 12MP 的 int 数组要 48MB，叠在位图上容易 OOM
        for (y in 0 until h) {
            src.getPixels(line, 0, w, 0, y, w, 1)
            val base = y * w
            for (x in 0 until w) {
                val v = pick(line[x])
                gray[base + x] = v.toByte()
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
        }

        // 拉伸到满量程。激光打标的码反差本来就不大，不拉伸的话
        // 二值化阈值落在哪儿全看运气
        val span = (hi - lo).coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                var v = gray[base + x].toInt() and 0xFF
                if (stretch) v = ((v - lo) * 255) / span
                if (invert) v = 255 - v
                row[x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        out
    }.getOrNull()

    /**
     * 放大。
     *
     * 码占的像素太少时，解码器分不清相邻模块的边界。
     * 双线性插值补不出信息，但能让边界过渡平滑，二值化更容易找对位置。
     */
    fun upscale(src: Bitmap, factor: Int = 2): Bitmap? = runCatching {
        if (factor <= 1) return src
        Bitmap.createScaledBitmap(src, src.width * factor, src.height * factor, true)
    }.getOrNull()

    /** 太大的图先缩一缩，控制预处理的内存和耗时 */
    fun capped(src: Bitmap, maxSide: Int): Bitmap? = runCatching {
        val long = maxOf(src.width, src.height)
        if (long <= maxSide) return src
        val s = maxSide.toFloat() / long
        Bitmap.createScaledBitmap(
            src,
            (src.width * s).toInt().coerceAtLeast(1),
            (src.height * s).toInt().coerceAtLeast(1),
            true
        )
    }.getOrNull()
}
