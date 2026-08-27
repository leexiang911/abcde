package com.sopcam.sop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 从网上拉配置。
 *
 * 复杂流程在手机上一项项点会疯掉，所以让人在电脑（或 GitHub 网页）上写 JSON，
 * 手机端只管下载。以后换成带界面的后台，下发的还是同一份格式，这边不用改。
 *
 * 提示图一起下载缓存 —— 车间可能没网，现用现拉会开不出来。
 */
object ConfigSync {

    data class Result(
        val ok: Boolean,
        val message: String,
        val templates: Int = 0,
        val images: Int = 0,
    )

    private const val TIMEOUT = 15_000

    private fun dir(ctx: Context) = File(ctx.filesDir, "remote").apply { if (!exists()) mkdirs() }
    private fun imageDir(ctx: Context) = File(dir(ctx), "images").apply { if (!exists()) mkdirs() }
    private fun stateFile(ctx: Context) = File(dir(ctx), "state.json")

    /** 上次同步的来源和版本，界面上要显示 */
    data class State(val url: String, val version: Int, val at: Long, val templates: Int)

    fun state(ctx: Context): State? = runCatching {
        val f = stateFile(ctx)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        State(o.optString("url"), o.optInt("version"), o.optLong("at"), o.optInt("templates"))
    }.getOrNull()

    /**
     * 同步。
     *
     * force=false 时先比对 index.json 里的 version，没变就不重下 ——
     * 车间用流量，没必要每次把提示图再拉一遍。
     */
    fun sync(ctx: Context, rawUrl: String, force: Boolean = false): Result {
        val url = rawUrl.trim()
        if (url.isBlank()) return Result(false, "地址是空的")

        val indexText = fetchText(url) ?: return Result(false, "拉不到 index.json，检查地址和网络")
        val index = runCatching { JSONObject(indexText) }.getOrNull()
            ?: return Result(false, "index.json 不是合法的 JSON")

        val version = index.optInt("version", 0)
        val prev = state(ctx)
        if (!force && prev != null && prev.url == url && prev.version == version) {
            return Result(true, "已经是最新的（版本 $version）", prev.templates)
        }

        // 相对路径按 index.json 的位置解析，配置里就能只写 "templates/x.json"
        val base = url.substringBeforeLast('/', "")

        var tplCount = 0
        val tplArr = index.optJSONArray("templates") ?: JSONArray()
        val saved = JSONArray()
        for (i in 0 until tplArr.length()) {
            val t = tplArr.optJSONObject(i) ?: continue
            val file = t.optString("file").ifBlank { continue }
            val body = fetchText(resolve(base, file)) ?: continue
            runCatching {
                val tpl = SopTemplate.from(JSONObject(body))
                if (tpl.steps.isEmpty()) return@runCatching
                saved.put(JSONObject(body))
                tplCount++
            }
        }
        if (tplCount == 0) return Result(false, "一个流程都没拉到，检查 templates 里的路径")

        // 目录和故障类型拉到就覆盖，拉不到保持原样 —— 别因为少一个文件就把已有的清了
        index.optString("catalog").takeIf { it.isNotBlank() }?.let { p ->
            fetchText(resolve(base, p))?.let { body ->
                runCatching {
                    val arr = JSONArray(body)
                    Catalog.save(ctx, (0 until arr.length()).map {
                        ControllerModel.from(arr.getJSONObject(it))
                    })
                }
            }
        }
        index.optString("faults").takeIf { it.isNotBlank() }?.let { p ->
            fetchText(resolve(base, p))?.let { body ->
                runCatching {
                    val arr = JSONArray(body)
                    Faults.save(ctx, (0 until arr.length()).map {
                        FaultType.from(arr.getJSONObject(it))
                    })
                }
            }
        }

        var imgCount = 0
        index.optString("hints").takeIf { it.isNotBlank() }?.let { p ->
            fetchText(resolve(base, p))?.let { body ->
                runCatching {
                    File(dir(ctx), "hints.json").writeText(body)
                    imgCount = cacheHintImages(ctx, JSONArray(body), base)
                }
            }
        }

        File(dir(ctx), "templates.json").writeText(saved.toString())
        stateFile(ctx).writeText(
            JSONObject()
                .put("url", url)
                .put("version", version)
                .put("at", System.currentTimeMillis())
                .put("templates", tplCount)
                .toString()
        )

        return Result(true, "已更新到版本 $version", tplCount, imgCount)
    }

    /** 下载好的流程。跟本地手工建的合在一起用 */
    fun templates(ctx: Context): List<SopTemplate> = runCatching {
        val f = File(dir(ctx), "templates.json")
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { SopTemplate.from(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun hints(ctx: Context): List<Hint> = runCatching {
        val f = File(dir(ctx), "hints.json")
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { Hint.from(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** 提示图在本地的落脚点。没缓存到就返回 null，界面显示文字说明 */
    fun localImage(ctx: Context, url: String): File? {
        if (url.isBlank()) return null
        val f = File(imageDir(ctx), hash(url))
        return if (f.exists() && f.length() > 0) f else null
    }

    private fun cacheHintImages(ctx: Context, arr: JSONArray, base: String): Int {
        var n = 0
        for (i in 0 until arr.length()) {
            val items = arr.optJSONObject(i)?.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val u = items.optJSONObject(j)?.optString("image") ?: continue
                if (u.isBlank()) continue
                val full = resolve(base, u)
                val target = File(imageDir(ctx), hash(full))
                if (target.exists() && target.length() > 0) { n++; continue }
                fetchBytes(full)?.let { target.writeBytes(it); n++ }
            }
        }
        return n
    }

    /** 绝对地址原样用，相对路径接到 index.json 所在目录后面 */
    private fun resolve(base: String, path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path
        else "$base/${path.trimStart('/')}"

    private fun hash(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun fetchText(url: String): String? =
        fetchBytes(url)?.toString(Charsets.UTF_8)

    private fun fetchBytes(url: String): ByteArray? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SopCam")
        }
        conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
    }.getOrNull()
}

/** 提示库：拍照时点开看点位图 */
data class Hint(
    val id: String,
    val name: String,
    val items: List<HintItem>,
) {
    companion object {
        fun from(o: JSONObject): Hint {
            val arr = o.optJSONArray("items") ?: JSONArray()
            return Hint(
                id = o.optString("id"),
                name = o.optString("name"),
                items = (0 until arr.length()).map { HintItem.from(arr.getJSONObject(it)) },
            )
        }
    }
}

data class HintItem(
    val no: String,
    val name: String,
    val image: String,
    val text: String,
) {
    companion object {
        fun from(o: JSONObject) = HintItem(
            no = o.optString("no"),
            name = o.optString("name"),
            image = o.optString("image"),
            text = o.optString("text"),
        )
    }
}
