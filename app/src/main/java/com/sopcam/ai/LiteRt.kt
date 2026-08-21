package com.sopcam.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/**
 * 端侧大模型的薄封装。
 *
 * 只用官方文档里出现过的 API，不猜签名 —— 这是第一次接这个库，
 * 出问题时要能一眼看出是「我用错了」还是「库本身不支持」。
 *
 * 模型文件两三个 GB，绝不能打进 APK，只能从存储里读。
 */
object LiteRt {

    enum class Device(val label: String, val note: String, val usable: Boolean) {
        GPU("GPU", "一般最快，要 Manifest 里声明 OpenCL 库", true),
        CPU("CPU", "最稳，慢一些", true),
        NPU("NPU", "要厂商原生库，Maven 包里没有", false),
    }

    data class Loaded(val path: String, val device: Device, val millis: Long)

    data class Answer(val text: String, val millis: Long)

    @Volatile
    private var engine: Engine? = null

    @Volatile
    var loaded: Loaded? = null
        private set

    val isReady: Boolean get() = engine != null

    /** 在存储里找模型。文件大，用户不会到处放，扫几个常见目录就够 */
    fun findModels(): List<File> {
        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            File(Environment.getExternalStorageDirectory(), "Models"),
            Environment.getExternalStorageDirectory(),
        )
        val seen = LinkedHashMap<String, File>()
        roots.forEach { root ->
            runCatching {
                root.listFiles { f -> f.isFile && f.name.endsWith(".litertlm", true) }
                    ?.forEach { seen[it.absolutePath] = it }
            }
        }
        return seen.values.toList()
    }

    /**
     * 加载模型。
     *
     * 官方说 initialize() 可能要十秒，必须在后台线程调 ——
     * 放主线程会直接 ANR。
     */
    fun load(ctx: Context, path: String, device: Device): Result<Loaded> {
        unload()
        val t0 = System.currentTimeMillis()
        return runCatching {
            val backend = if (device == Device.GPU) Backend.GPU() else Backend.CPU()
            val cfg = EngineConfig(
                modelPath = path,
                backend = backend,
                // 视觉编码器单独指定：读表计要靠它，放 GPU 比放 CPU 快得多
                visionBackend = backend,
                // 第二次加载会快很多，缓存值得给
                cacheDir = ctx.cacheDir.absolutePath,
            )
            val e = Engine(cfg)
            e.initialize()
            engine = e
            Loaded(path, device, System.currentTimeMillis() - t0).also { loaded = it }
        }
    }

    fun unload() {
        runCatching { engine?.close() }
        engine = null
        loaded = null
    }

    /** 纯文本，用来验证引擎本身通不通 —— 图片那条路出问题时好排除 */
    fun ask(prompt: String, system: String? = null): Result<Answer> = runCatching {
        val e = engine ?: error("模型还没加载")
        val t0 = System.currentTimeMillis()
        val text = conversation(e, system).use { it.sendMessage(prompt).toString() }
        Answer(text.trim(), System.currentTimeMillis() - t0)
    }

    /**
     * 带图提问。
     *
     * 两个刻意的处理：
     *  · 先缩到 896 长边 —— 视觉编码器内部本来就会缩，喂 12MP 纯属白等，
     *    而且大图更容易把内存顶爆。
     *  · 落成临时 .jpg 再传路径 —— 归档原图的扩展名是 .sopraw，
     *    库大概率按扩展名判断格式，直接给它会认不出来。
     */
    fun askImage(
        ctx: Context,
        imagePath: String,
        prompt: String,
        system: String? = null,
        maxSide: Int = 896,
    ): Result<Answer> = runCatching {
        val e = engine ?: error("模型还没加载")
        val temp = prepare(ctx, imagePath, maxSide) ?: error("图片读不出来：$imagePath")
        val t0 = System.currentTimeMillis()
        val text = conversation(e, system).use { c ->
            c.sendMessage(
                Contents.of(
                    Content.ImageFile(temp.absolutePath),
                    Content.Text(prompt),
                )
            ).toString()
        }
        Answer(text.trim(), System.currentTimeMillis() - t0)
    }

    private fun conversation(e: Engine, system: String?): Conversation =
        if (system.isNullOrBlank()) e.createConversation()
        else e.createConversation(ConversationConfig(systemInstruction = Contents.of(system)))

    private fun prepare(ctx: Context, path: String, maxSide: Int): File? = runCatching {
        val src = BitmapFactory.decodeFile(path) ?: return null
        val long = maxOf(src.width, src.height)
        val bmp = if (long <= maxSide) src else {
            val s = maxSide.toFloat() / long
            Bitmap.createScaledBitmap(
                src,
                (src.width * s).toInt().coerceAtLeast(1),
                (src.height * s).toInt().coerceAtLeast(1),
                true
            ).also { if (it !== src) src.recycle() }
        }
        val out = File(ctx.cacheDir, "ai-input.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        out
    }.getOrNull()
}
