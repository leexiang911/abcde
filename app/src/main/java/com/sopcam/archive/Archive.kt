package com.sopcam.archive

import android.content.Context
import android.os.Build
import android.os.Environment
import com.sopcam.meta.ImageMeta
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 检修归档区。
 *
 * 水印照片走相册（要看、要发），原图走这里（只给 App 用，负责兜底恢复）。
 *
 * 三个设计决定：
 *  · 放公共 Documents 目录，不放 App 私有目录 —— 私有目录卸载即清空，
 *    而恢复功能的意义正是长期保底，那样自相矛盾。
 *  · 原图存成 .sopraw（就是 JPEG 字节换个扩展名），目录里再放一个 .nomedia。
 *    双保险，相册和文件管理器的图片视图都不会收录它。
 *  · 用普通文件 API，不走 MediaStore —— MediaStore 的非媒体文件只有创建它的
 *    那个 App 实例看得见，重装之后读不到自己的旧归档。
 */
object Archive {

    private const val ROOT = "SOP归档"
    const val RAW_EXT = "sopraw"

    private val fileFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val illegal = Regex("""[\\/:*?"<>|\r\n\t]""")

    /** 序列号来自扫码，可能带奇怪字符，落成目录名前先清一遍 */
    private fun safe(raw: String): String =
        raw.replace(illegal, "").trim().ifBlank { "未命名" }.take(48)

    fun root(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), ROOT)

    fun projectDir(serialNo: String): File = File(root(), safe(serialNo))

    /** 有没有拿到「所有文件访问权限」。没有的话归档功能整个是哑的 */
    fun canWrite(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true

    private fun ensureRoot(): File {
        val r = root()
        if (!r.exists()) r.mkdirs()
        // 空文件，让媒体扫描器整个跳过这棵目录树
        val marker = File(r, ".nomedia")
        if (!marker.exists()) runCatching { marker.createNewFile() }
        return r
    }

    /* ------------------------------------------------------------------
     * 写入
     * ------------------------------------------------------------------ */

    /**
     * 存一张原图和它的随行元数据。
     * 返回落盘的基名（不含扩展名），失败返回 null。
     */
    fun save(
        serialNo: String,
        jpeg: ByteArray,
        meta: ImageMeta,
        watermarkLines: List<String>,
        headline: String?,
    ): String? = runCatching {
        if (!canWrite()) return null
        ensureRoot()
        val dir = projectDir(serialNo)
        if (!dir.exists()) dir.mkdirs()

        val base = uniqueBase(dir, fileFmt.format(Date(meta.capturedAt)))
        File(dir, "$base.$RAW_EXT").writeBytes(jpeg)

        // 把当时的水印内容也记下来，恢复时才能烧出一模一样的图
        val side = JSONObject()
            .put("imageId", meta.imageId)
            .put("capturedAt", meta.capturedAt)
            .put("stepOrder", meta.stepOrder)
            .put("stepName", meta.stepName)
            .put("stepRefDes", meta.stepRefDes)
            .put("codeValue", meta.codeValue)
            .put("codeFormat", meta.codeFormat)
            .put("anchor", meta.anchor)
            .put("topEdge", meta.topEdge)
            .put("headline", headline ?: "")
            .put("lines", JSONArray().apply { watermarkLines.forEach { put(it) } })
        File(dir, "$base.json").writeText(side.toString())
        base
    }.getOrNull()

    /** 同一秒内连拍会撞名，加序号错开 */
    private fun uniqueBase(dir: File, stamp: String): String {
        if (!File(dir, "$stamp.$RAW_EXT").exists()) return stamp
        var i = 2
        while (File(dir, "${stamp}_$i.$RAW_EXT").exists()) i++
        return "${stamp}_$i"
    }

    /** 项目档案。每次拍照都刷一遍，这样型号平台改了也跟着更新 */
    fun touchProject(serialNo: String, model: String, platform: String, fault: String) {
        runCatching {
            if (!canWrite()) return
            ensureRoot()
            val dir = projectDir(serialNo)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "project.json")
            val now = System.currentTimeMillis()
            val created = if (f.exists()) {
                runCatching { JSONObject(f.readText()).optLong("createdAt", now) }.getOrDefault(now)
            } else now
            f.writeText(
                JSONObject()
                    .put("serialNo", serialNo)
                    .put("model", model)
                    .put("platform", platform)
                    .put("fault", fault)
                    .put("createdAt", created)
                    .put("updatedAt", now)
                    .toString()
            )
        }
    }

    /* ------------------------------------------------------------------
     * 读取
     * ------------------------------------------------------------------ */

    data class Project(
        val serialNo: String,
        val model: String,
        val platform: String,
        val fault: String,
        val updatedAt: Long,
        val shotCount: Int,
    )

    /** 所有项目，最近更新的排前面 */
    fun list(): List<Project> = runCatching {
        val r = root()
        if (!r.exists()) return emptyList()
        r.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                val shots = dir.listFiles { f -> f.extension == RAW_EXT }?.size ?: 0
                val meta = File(dir, "project.json")
                if (!meta.exists()) {
                    if (shots == 0) return@mapNotNull null
                    return@mapNotNull Project(dir.name, "", "", "", dir.lastModified(), shots)
                }
                val o = runCatching { JSONObject(meta.readText()) }.getOrNull()
                    ?: return@mapNotNull null
                Project(
                    serialNo = o.optString("serialNo", dir.name),
                    model = o.optString("model"),
                    platform = o.optString("platform"),
                    fault = o.optString("fault"),
                    updatedAt = o.optLong("updatedAt", dir.lastModified()),
                    shotCount = shots,
                )
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }.getOrDefault(emptyList())

    /** 一个项目里的所有原图，按时间正序 */
    fun shots(serialNo: String): List<File> =
        projectDir(serialNo).listFiles { f -> f.extension == RAW_EXT }
            ?.sortedBy { it.name } ?: emptyList()

    fun sidecar(raw: File): JSONObject? = runCatching {
        val f = File(raw.parentFile, raw.nameWithoutExtension + ".json")
        if (f.exists()) JSONObject(f.readText()) else null
    }.getOrNull()
}
