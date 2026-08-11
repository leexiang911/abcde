package com.sopcam.capture

import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/** 扫到的码 */
data class ScannedCode(val value: String, val format: String)

/**
 * 取景时持续识别条码 / 二维码。
 *
 * 两个刻意的设计：
 *  · 限流 —— 每 300ms 才真正跑一次识别。码不会跑，一秒扫三次足够，
 *    每帧都跑纯属浪费电，还会跟拍照抢算力。
 *  · 降分辨率 —— 720p 就够解码了，全分辨率反而慢。
 */
class CodeAnalyzer(
    private val intervalMs: Long = 300L,
    private val onCode: (ScannedCode) -> Unit,
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient()
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
        fun buildUseCase(): ImageAnalysis =
            ImageAnalysis.Builder()
                // 只留最新一帧。积压的旧帧对扫码毫无价值，只会让提示滞后
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
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
