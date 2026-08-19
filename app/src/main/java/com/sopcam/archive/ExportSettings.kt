package com.sopcam.archive

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * 导出压缩格式。
 *
 * MozJPEG 先占个位：它是 C 库，要 NDK 交叉编译 + JNI 封装 + 每个 ABI 打一份，
 * 而它比标准 JPEG 只省 10–15% —— WebP 直接省 25–35% 且系统内置。
 * 性价比不对，所以先禁用，留着以后真有需要（比如上传系统只收 JPEG
 * 又对体积卡得死）再考虑。
 */
enum class ExportFormat(val label: String, val note: String, val enabled: Boolean) {
    WEBP("WebP", "体积最小，比 JPEG 省三成", true),
    JPEG("JPEG", "兼容性最好，到哪都能开", true),
    MOZJPEG("MozJPEG", "暂未支持", false);

    companion object {
        fun of(raw: String?): ExportFormat =
            entries.firstOrNull { it.name == raw && it.enabled } ?: JPEG
    }
}

/**
 * 导出时的压缩设置。
 *
 * quality = 100 表示不压缩：原样打包，格式选择也就没意义了。
 */
data class ExportSettings(
    val quality: Int = 100,
    val format: ExportFormat = ExportFormat.JPEG,
    /** 压缩后重新注入 XMP。只有 JPEG 能做 —— WebP 要另写 RIFF chunk 写入器 */
    val keepMetadata: Boolean = true,
    /** 包里带一份 HTML 报表和 report.json */
    val htmlReport: Boolean = true,
    /** Excel 报表，下一轮做 */
    val excelReport: Boolean = false,
) {
    val compresses: Boolean get() = quality in 1..99

    /** WebP 没法注入 XMP，选了它元数据必然丢 */
    val metadataPossible: Boolean get() = !compresses || format == ExportFormat.JPEG

    fun summary(): String = when {
        !compresses -> "不压缩"
        else -> "${format.label} · $quality%"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("quality", quality)
        .put("format", format.name)
        .put("keepMetadata", keepMetadata)
        .put("htmlReport", htmlReport)
        .put("excelReport", excelReport)

    companion object {
        val LEVELS = listOf(100, 95, 80, 75, 65, 60, 50)

        fun from(o: JSONObject) = ExportSettings(
            quality = o.optInt("quality", 100).coerceIn(1, 100),
            format = ExportFormat.of(o.optString("format")),
            keepMetadata = o.optBoolean("keepMetadata", true),
            htmlReport = o.optBoolean("htmlReport", true),
            excelReport = false,   // 还没实现，读到 true 也当 false
        )
    }
}

object ExportStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "export.json")

    fun load(ctx: Context): ExportSettings {
        val f = file(ctx)
        if (!f.exists()) return ExportSettings()
        return runCatching { ExportSettings.from(JSONObject(f.readText())) }
            .getOrDefault(ExportSettings())
    }

    fun save(ctx: Context, s: ExportSettings) {
        runCatching { file(ctx).writeText(s.toJson().toString()) }
    }
}
