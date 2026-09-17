package com.sopcam.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 印刷体文字识别（ML Kit）。
 *
 * 跟 LiteRt 是互补关系，不是替代：
 *
 *  · **它不缩图。** LiteRt 要先把图压到 896 长边才喂得进视觉编码器，
 *    4000×3000 的铭牌照片缩完，型号那行字只剩二十来像素高，
 *    0/O、2/Z、E/F 就开始靠猜。ML Kit 直接吃原始分辨率，同一行字是四十像素。
 *    铭牌、丝印、标签这类**印刷体**归它。
 *
 *  · **但它只认标准字体。** 七段数码管的笔画是断开的，检测器常常整块都框不出来；
 *    波形、指示灯状态更不是文字。那些还得交给 LiteRt。
 *
 * 只装拉丁文字模型：要读的全是字母数字（EM2E-2142700E、0104215NLN22053007、
 * 万用表读数），一个中文都没有，中文包要大一个数量级。
 */
object Ocr {

    /**
     * 一行文字和它在图上的位置。
     *
     * 只取官方文档里明确列出的 text / boundingBox —— Line 上是否有 confidence
     * 文档没写，猜签名的代价是一轮三分钟的 CI。位置留着以后按区域挑用。
     */
    data class Line(val text: String, val left: Int, val top: Int)

    /**
     * 一次识别的结果。
     *
     * 刻意不叫 Result —— 那会跟 kotlin.Result 撞名，逼得返回类型只能写全限定，
     * 而全限定名在这个项目里是明令避开的坏味道。
     */
    data class Readout(val lines: List<Line>, val millis: Long) {
        /** 所有行拼起来，行序是 ML Kit 给的阅读顺序 */
        val text: String get() = lines.joinToString("\n") { it.text }
    }

    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /**
     * 读一个图片文件。
     *
     * 归档原图的扩展名是 .sopraw，但内容就是 JPEG 字节，
     * BitmapFactory 按内容解码，不看扩展名 —— 不像 LiteRt 那边要先转存临时 .jpg。
     */
    suspend fun read(path: String): Result<Readout> = runCatching {
        val t0 = System.currentTimeMillis()
        // 解码放 IO 线程：一张 12MP 的图解出来要几百毫秒，放主线程会卡
        val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
            ?: error("图片读不出来：$path")
        try {
            val lines = recognize(bmp)
            Readout(lines, System.currentTimeMillis() - t0)
        } finally {
            bmp.recycle()
        }
    }

    private suspend fun recognize(bmp: Bitmap): List<Line> =
        suspendCancellableCoroutine { cont ->
            fun finish(v: List<Line>) {
                if (cont.isActive) cont.resume(v)
            }
            runCatching {
                // 归档原图落盘前已经物理转正，所以旋转角固定传 0
                client.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { text ->
                        finish(
                            text.textBlocks
                                .flatMap { it.lines }
                                .mapNotNull { l ->
                                    val body = l.text.trim()
                                    if (body.isBlank()) null
                                    else Line(
                                        text = body,
                                        left = l.boundingBox?.left ?: 0,
                                        top = l.boundingBox?.top ?: 0,
                                    )
                                }
                        )
                    }
                    .addOnFailureListener { finish(emptyList()) }
            }.onFailure { finish(emptyList()) }
        }
}
