package com.sopcam.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** 扫到的码 */
data class ScannedCode(val value: String, val format: String)

/**
 * 取景时持续识别条码 / 二维码。
 *
 * 限流：每 300ms 才真正跑一次。码不会跑，一秒扫三次足够，
 * 每帧都跑纯属浪费电，还会跟拍照抢算力。
 *
 * 这一路只负责取景提示。真正要写进元数据的码值由 StillScanner
 * 在拍完之后对全分辨率成片再扫一次拿到 —— 板子上的 Data Matrix 模块细，
 * 预览分辨率经常不够。
 */
class CodeAnalyzer(
    private val intervalMs: Long = 300L,
    private val onCode: (ScannedCode) -> Unit,
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(Codes.options())
    private var lastRunAt = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastRunAt < intervalMs) {
            proxy.close()
            return
        }
        lastRunAt = now

        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }

        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { list ->
                list.firstOrNull()?.rawValue?.let { v ->
                    onCode(ScannedCode(v, formatName(list.first().format)))
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    companion object {
        /**
          * 比例必须跟取景框一致。
          *
          * 之前这里是 16:9 而取景是 4:3 —— CameraX 里不同用例的视野各算各的，
          * 16:9 相当于把 4:3 的上下切掉一截，导致"取景框里看得见的码，
          * 识别器根本没看到"。这不是概率问题，是结构性的。
          */
        fun buildUseCase(): ImageAnalysis =
            ImageAnalysis.Builder()
                // 只留最新一帧。积压的旧帧对扫码毫无价值，只会让提示滞后
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            // 1600x1200 而不是 720p：板子上的 Data Matrix 模块很细，
                            // 720p 下一个模块只有几个像素，解码器无从下手
                            ResolutionStrategy(
                                Size(1600, 1200),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                .build()

        fun formatName(format: Int): String = when (format) {
            Barcode.FORMAT_QR_CODE -> "QR_CODE"
            Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
            Barcode.FORMAT_CODE_128 -> "CODE_128"
            Barcode.FORMAT_CODE_39 -> "CODE_39"
            Barcode.FORMAT_CODE_93 -> "CODE_93"
            Barcode.FORMAT_EAN_13 -> "EAN_13"
            Barcode.FORMAT_EAN_8 -> "EAN_8"
            Barcode.FORMAT_UPC_A -> "UPC_A"
            Barcode.FORMAT_UPC_E -> "UPC_E"
            Barcode.FORMAT_ITF -> "ITF"
            Barcode.FORMAT_CODABAR -> "CODABAR"
            Barcode.FORMAT_PDF417 -> "PDF417"
            Barcode.FORMAT_AZTEC -> "AZTEC"
            else -> "OTHER"
        }
    }
}

/**
 * 扫码的公共配置与静态图识别。
 */
object Codes {

    /**
     * 明确列出要认的格式。
     *
     * 不限定的话解码器要挨个格式假设一遍，慢且更容易误判。
     * DATA_MATRIX 是这里的重点 —— 控制器板子上打的是它，不是二维码。
     */
    fun options(): BarcodeScannerOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_CODE_93,
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_PDF417,
            Barcode.FORMAT_AZTEC,
        )
        .build()

    private val still by lazy { BarcodeScanning.getClient(options()) }

    /**
     * 对一张已解码的位图扫码。
     *
     * 用在拍完之后：成片是 4000x3000，比预览那路的 1600x1200 还多六倍像素，
     * 预览扫不出来的细密码，这一遍常常能拿下。
     *
     * ML Kit 的 Task 是异步的，这里挂起等它，好让流水线在写元数据之前拿到码值。
     */
    /**
     * 级联识别。
     *
     * 关键点：每一趟都还是喂给 ML Kit，只是喂的图不一样。
     * ML Kit 的解码本身比 ZXing 强，它的短板只在「彩色怎么变灰度」这一步 ——
     * 那一步我自己做了再给它，就补上了。ZXing 排在最后当兜底。
     *
     * thorough=false 用在拍照流水线上：只跑前两趟，不拖慢连拍。
     * thorough=true 用在手动扫描：跑全套，慢几秒无所谓。
     */
    suspend fun scan(bmp: Bitmap, thorough: Boolean = false): ScannedCode? {
        mlkit(bmp)?.let { return it }

        val variants = withContext(Dispatchers.Default) {
            buildList {
                // 红通道是这块板子的关键：铜色模块 vs 绿油底，红通道对比度翻倍
                Preprocess.redChannel(bmp, invert = false)?.let { add(it) }
                if (thorough) {
                    // 浅模块深底，跟常规黑码白底相反，取反这趟专门对付极性
                    Preprocess.redChannel(bmp, invert = true)?.let { add(it) }
                    Preprocess.luma(bmp, invert = true)?.let { add(it) }
                    Preprocess.luma(bmp, invert = false)?.let { add(it) }
                }
            }
        }

        try {
            for (v in variants) {
                mlkit(v)?.let { return it }
            }

            // 码占的像素太少时，放大能让二值化更容易找对模块边界
            if (thorough && maxOf(bmp.width, bmp.height) < 1400) {
                for (v in variants.take(2)) {
                    val big = Preprocess.upscale(v, 2) ?: continue
                    val hit = mlkit(big)
                    if (big !== v) big.recycle()
                    hit?.let { return it }
                }
            }

            // ZXing 兜底。它的 Data Matrix 解码不如 ML Kit，只当最后一根稻草
            if (thorough) {
                withContext(Dispatchers.Default) {
                    variants.firstNotNullOfOrNull { ZxingDecoder.scan(it) }
                }?.let { return it }
            }
        } finally {
            variants.forEach { if (it !== bmp) it.recycle() }
        }
        return null
    }

    private suspend fun mlkit(bmp: Bitmap): ScannedCode? = suspendCancellableCoroutine { cont ->
        fun finish(v: ScannedCode?) {
            if (cont.isActive) cont.resume(v)
        }
        runCatching {
            still.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { list ->
                    val hit = list.firstOrNull { !it.rawValue.isNullOrBlank() }
                    finish(hit?.let {
                        ScannedCode(it.rawValue!!, CodeAnalyzer.formatName(it.format))
                    })
                }
                .addOnFailureListener { finish(null) }
        }.onFailure { finish(null) }
    }
}

/**
 * 框选区域识别。
 *
 * 整张 4000x3000 里，码可能只占一小块 —— 解码器要先在满屏噪声里找定位图形，
 * 找错了后面全白搭。框出来单独解，等于把模块的有效像素数放大好几倍。
 *
 * 用 BitmapRegionDecoder 直接从文件里读那一块，不用把整张 12MP 载进内存。
 */
object RegionScan {

    suspend fun scan(path: String, left: Int, top: Int, right: Int, bottom: Int): ScannedCode? =
        withContext(Dispatchers.IO) {
            // 往外扩 18% 留静区。Data Matrix 和 QR 都要靠四周的空白定位，
            // 框得贴边反而解不出来
            val padX = ((right - left) * 0.18f).toInt()
            val padY = ((bottom - top) * 0.18f).toInt()
            val crop = decodeRegion(path, left - padX, top - padY, right + padX, bottom + padY)
                ?: return@withContext null
            val hit = Codes.scan(crop, thorough = true)
            crop.recycle()
            hit
        }

    private fun decodeRegion(path: String, l: Int, t: Int, r: Int, b: Int): Bitmap? = runCatching {
        val dec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(path)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(path, false)
        } ?: return null

        val rect = Rect(
            l.coerceAtLeast(0),
            t.coerceAtLeast(0),
            r.coerceAtMost(dec.width),
            b.coerceAtMost(dec.height)
        )
        if (rect.width() < 16 || rect.height() < 16) return null

        val out = dec.decodeRegion(rect, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888   // 红通道要原色，不能用 RGB_565
        })
        dec.recycle()
        out
    }.getOrNull()
}
