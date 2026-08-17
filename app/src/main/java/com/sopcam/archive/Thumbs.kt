package com.sopcam.archive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 缩略图。
 *
 * .sopraw 里就是 JPEG 字节，BitmapFactory 不看扩展名，直接解就行。
 * 原图落盘前已经按 EXIF 物理转正了，所以这里不用再管方向。
 */
object Thumbs {

    // 一个项目几十张图，全尺寸解码几张就把内存吃光了，只留小图
    private const val CAP = 80
    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > CAP
    }

    @Synchronized
    private fun get(key: String): Bitmap? = cache[key]

    @Synchronized
    private fun put(key: String, bmp: Bitmap) {
        cache[key] = bmp
    }

    /** 解一张缩略图。target 是希望的短边像素，实际会是 2 的幂次降采样后的尺寸 */
    fun of(file: File, target: Int = 320): Bitmap? {
        val key = "${file.absolutePath}@$target"
        get(key)?.let { return it }

        return runCatching {
            // 先只读尺寸，算出降采样倍数，避免把整张 12MP 读进内存
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, probe)
            if (probe.outWidth <= 0) return null

            var sample = 1
            while (probe.outWidth / (sample * 2) >= target &&
                probe.outHeight / (sample * 2) >= target
            ) sample *= 2

            val bmp = BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            ) ?: return null
            put(key, bmp)
            bmp
        }.getOrNull()
    }

    /** 大图查看用，采样更轻 */
    fun full(file: File): Bitmap? = of(file, 1080)

    @Synchronized
    fun evict(file: File) {
        cache.keys.filter { it.startsWith(file.absolutePath) }.forEach { cache.remove(it) }
    }
}
