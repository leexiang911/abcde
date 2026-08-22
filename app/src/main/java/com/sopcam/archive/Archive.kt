package com.sopcam.archive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import com.sopcam.meta.ImageMeta
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

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
     * 归档写入的结果。
     *
     * 以前这里返回 String? 并且整个包在 runCatching 里，写失败悄无声息 ——
     * 用户开着"保存原图"拍了一整天，回头才发现项目页是空的。
     * 现在把失败的原因带出来，让界面能当场报警。
     */
    sealed interface SaveResult {
        data class Ok(val base: String) : SaveResult
        /** 没拿到"所有文件访问权限" */
        data object NoPermission : SaveResult
        data class Failed(val reason: String) : SaveResult
    }

    /** 存一张原图和它的随行元数据 */
    fun save(
        serialNo: String,
        jpeg: ByteArray,
        meta: ImageMeta,
        watermarkLines: List<String>,
        headline: String?,
        fileName: String = "",
        relativePath: String = "",
    ): SaveResult {
        if (!canWrite()) return SaveResult.NoPermission
        return runCatching {
            ensureRoot()
            val dir = projectDir(serialNo)
            if (!dir.exists()) dir.mkdirs()

            val base = uniqueBase(dir, fileFmt.format(Date(meta.capturedAt)))
            File(dir, "$base.$RAW_EXT").writeBytes(jpeg)

            // 把当时的水印内容也记下来，恢复时才能烧出一模一样的图
            val side = JSONObject()
                .put("imageId", meta.imageId)
                .put("capturedAt", meta.capturedAt)
                .put("serialNo", meta.serialNo)
                .put("modelName", meta.modelName)
                .put("platformName", meta.platformName)
                .put("faultType", meta.faultType)
                .put("stepOrder", meta.stepOrder)
                .put("stepName", meta.stepName)
                .put("stepRefDes", meta.stepRefDes)
                .put("stepPoint", meta.stepPoint)
                .put("stepGroup", meta.stepGroup)
                .put("codeValue", meta.codeValue)
                .put("codeFormat", meta.codeFormat)
                .put("anchor", meta.anchor)
                .put("topEdge", meta.topEdge)
                .put("headline", headline ?: "")
                .put("lines", JSONArray().apply { watermarkLines.forEach { put(it) } })
                // 这两个是恢复水印时要用的：往哪个相册目录写、叫什么名字
                .put("fileName", fileName)
                .put("relativePath", relativePath)
            File(dir, "$base.json").writeText(side.toString())
            SaveResult.Ok(base) as SaveResult
        }.getOrElse { SaveResult.Failed(it.message ?: it.javaClass.simpleName) }
    }

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
            // 状态和备注是用户手填的，拍照刷新档案时不能冲掉
            val prev = if (f.exists()) {
                runCatching { JSONObject(f.readText()) }.getOrNull()
            } else null
            f.writeText(
                JSONObject()
                    .put("serialNo", serialNo)
                    .put("model", model)
                    .put("platform", platform)
                    .put("fault", fault)
                    .put("status", prev?.optString("status") ?: "")
                    .put("note", prev?.optString("note") ?: "")
                    .put("createdAt", prev?.optLong("createdAt", now) ?: now)
                    .put("updatedAt", now)
                    .toString()
            )
        }
    }

    /* ------------------------------------------------------------------
     * 读取
     * ------------------------------------------------------------------ */

    /** 项目进度。检修台上一眼要能扫出哪些还没弄完、哪些出了问题 */
    enum class Status(val key: String, val label: String) {
        NONE("", "未标记"),
        DOING("doing", "进行中"),
        DONE("done", "完成"),
        ERROR("error", "异常");

        companion object {
            fun of(key: String?): Status =
                entries.firstOrNull { it.key == key } ?: NONE
        }
    }

    data class Project(
        val serialNo: String,
        val model: String,
        val platform: String,
        val fault: String,
        val updatedAt: Long,
        val shotCount: Int,
        val status: Status = Status.NONE,
        val note: String = "",
    ) {
        /** 搜索时拿来匹配的文本 */
        fun haystack(): String = "$serialNo $model $platform $fault $note".lowercase()
    }

    /** 备注。写完立刻回列表可见，不用等下次拍照 */
    fun setNote(serialNo: String, note: String) {
        runCatching {
            val f = File(projectDir(serialNo), "project.json")
            f.parentFile?.mkdirs()
            val o = if (f.exists()) JSONObject(f.readText()) else JSONObject().put("serialNo", serialNo)
            f.writeText(o.put("note", note).put("updatedAt", System.currentTimeMillis()).toString())
        }
    }

    fun setStatus(serialNo: String, status: Status) {
        runCatching {
            val f = File(projectDir(serialNo), "project.json")
            val o = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            f.parentFile?.mkdirs()
            f.writeText(o.put("status", status.key).put("updatedAt", System.currentTimeMillis()).toString())
        }
    }

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
                    status = Status.of(o.optString("status")),
                    note = o.optString("note"),
                )
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }.getOrDefault(emptyList())

    /** 一个项目里的所有原图，按时间正序 */
    fun shots(serialNo: String): List<File> =
        projectDir(serialNo).listFiles { f -> f.extension == RAW_EXT }
            ?.sortedBy { it.name } ?: emptyList()

    /** 删原图，随行的 json 一起删掉，不留孤儿文件 */
    fun deleteShots(files: List<File>): Int = files.count { f ->
        runCatching {
            File(f.parentFile, f.nameWithoutExtension + ".json").delete()
            f.delete()
        }.getOrDefault(false)
    }

    /** 删整个项目的归档。原图没了就再也恢复不出水印图，调用方必须先确认过 */
    fun deleteProject(serialNo: String): Boolean = runCatching {
        projectDir(serialNo).deleteRecursively()
    }.getOrDefault(false)

    /** 事后补扫出来的码值，写回随行 json。恢复水印时就能带上它 */
    fun updateSidecarCode(raw: File, value: String, format: String): Boolean {
        val f = File(raw.parentFile, raw.nameWithoutExtension + ".json")
        if (!f.exists()) return false
        return runCatching {
            val o = JSONObject(f.readText())
                .put("codeValue", value)
                .put("codeFormat", format)
            f.writeText(o.toString())
            true
        }.getOrDefault(false)
    }

    /** 清掉误带上的码值。批量清除用的就是它 */
    fun clearSidecarCode(raw: File): Boolean = updateSidecarCode(raw, "", "")

    /** 改水印文字和目标文件名，改完再"重烧回相册"才会生效 */
    fun updateSidecarWatermark(
        raw: File,
        headline: String,
        lines: List<String>,
        fileName: String,
    ): Boolean {
        val f = File(raw.parentFile, raw.nameWithoutExtension + ".json")
        if (!f.exists()) return false
        return runCatching {
            val o = JSONObject(f.readText())
                .put("headline", headline)
                .put("lines", JSONArray().apply { lines.forEach { put(it) } })
                .put("fileName", fileName)
            f.writeText(o.toString())
            true
        }.getOrDefault(false)
    }

    /** 改随行 json 的某几个字段，其余原样保留 */
    fun patchSidecar(raw: File, block: JSONObject.() -> Unit): Boolean {
        val f = File(raw.parentFile, raw.nameWithoutExtension + ".json")
        if (!f.exists()) return false
        return runCatching {
            val o = JSONObject(f.readText())
            o.block()
            f.writeText(o.toString())
            true
        }.getOrDefault(false)
    }

    /**
     * 重拍替换。
     *
     * 旧的原图和随行 json 移到 replaced/ 留一份保险 —— 它不参与列表、
     * 不参与导出、不参与恢复，只是拍砸了能回头。
     * 新的接管旧的文件名，这样成片名和排序都不变。
     */
    fun replaceShot(old: File, new: File): Boolean = runCatching {
        val dir = old.parentFile ?: return false
        val bak = File(dir, "replaced").apply { if (!exists()) mkdirs() }
        val stamp = System.currentTimeMillis()
        val oldSide = File(dir, old.nameWithoutExtension + ".json")
        val newSide = File(dir, new.nameWithoutExtension + ".json")

        // 新的随行 json 要接管旧的成片文件名和目录，否则相册里会多出一张
        val keep = runCatching { JSONObject(oldSide.readText()) }.getOrNull()
        if (keep != null && newSide.exists()) {
            runCatching {
                val o = JSONObject(newSide.readText())
                o.put("fileName", keep.optString("fileName"))
                o.put("relativePath", keep.optString("relativePath"))
                o.put("anchor", keep.optString("anchor", o.optString("anchor")))
                o.put("rotation", keep.optInt("rotation", 0))
                o.put("retakenAt", stamp)
                newSide.writeText(o.toString())
            }
        }

        old.renameTo(File(bak, "$stamp-" + old.name))
        if (oldSide.exists()) oldSide.renameTo(File(bak, "$stamp-" + oldSide.name))

        // 新的改名接管旧名字，缩略图网格里的位置和排序就不会跳
        val target = File(dir, old.name)
        val targetSide = File(dir, old.nameWithoutExtension + ".json")
        new.renameTo(target) && (!newSide.exists() || newSide.renameTo(targetSide))
    }.getOrDefault(false)

    /**
     * 把归档原图真正转过来。
     *
     * 早先的做法是只在 json 里记角度、重烧时才应用 —— 无损、可反悔，
     * 但缩略图、导出的原图、以后任何读这张图的地方都得各自记得应用一次，
     * 漏一处就出现"大图是正的、列表里还是歪的"。
     *
     * 所以改成落到实处：转完写回原图，角度归零。
     * 代价是一次重编码（质量 95，肉眼看不出），换来的是下游全都不用管。
     */
    fun rotateRaw(raw: File, degrees: Int): Boolean {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return true
        return runCatching {
            val src = BitmapFactory.decodeFile(raw.path) ?: return false
            val m = Matrix().apply { postRotate(d.toFloat()) }
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (out !== src) src.recycle()
            raw.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            out.recycle()
            patchSidecar(raw) { put("rotation", 0) }
            true
        }.getOrDefault(false)
    }

    fun sidecar(raw: File): JSONObject? = runCatching {
        val f = File(raw.parentFile, raw.nameWithoutExtension + ".json")
        if (f.exists()) JSONObject(f.readText()) else null
    }.getOrNull()
}

/* ------------------------------------------------------------------
 * 删除
 *
 * 归档和相册是两份独立的数据，删除时必须分开决定：
 * 删归档 = 失去重烧水印的兜底能力；删相册 = 只是删掉能看能发的成片。
 * ------------------------------------------------------------------ */

object Purge {

    /** 删一张原图连同它的随行 json */
    fun shot(raw: File): Boolean = runCatching {
        File(raw.parentFile, raw.nameWithoutExtension + ".json").delete()
        raw.delete()
    }.getOrDefault(false)

    /** 删整个项目的归档（原图 + 档案），相册里的水印图不动 */
    fun archiveOf(serialNo: String): Boolean = runCatching {
        Archive.projectDir(serialNo).deleteRecursively()
    }.getOrDefault(false)

    /**
     * 删相册里该序列号的全部水印照片。
     *
     * 直接删文件会在 MediaStore 里留下失效条目，相册可能还显示着灰色缩略图，
     * 所以删完要通知媒体库重新扫一遍这些路径。
     */
    fun galleryOf(ctx: Context, serialNo: String): Int {
        val files = Exporter.watermarkedOf(serialNo)
        val paths = files.map { it.absolutePath }
        var n = 0
        files.forEach { if (runCatching { it.delete() }.getOrDefault(false)) n++ }
        // 清掉空的日期/序列号目录，免得相册里留一堆空相簿
        files.map { it.parentFile }.distinct().forEach { dir ->
            runCatching { if (dir != null && dir.listFiles().isNullOrEmpty()) dir.delete() }
        }
        runCatching {
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
        }
        return n
    }
}
