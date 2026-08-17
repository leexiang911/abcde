package com.sopcam.sop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * 分类目录：控制器型号 → 该型号的平台。
 *
 * 这一版是写死的测试数据，但存储走的是 JSON 文件，
 * 以后从后台拉一份同样结构的 JSON 覆盖进来就行，界面不用改。
 * 故障类型这一维暂时没进 UI，等真要按它选 SOP 的时候再加。
 */

data class Platform(val id: String, val name: String, val customer: String = "") {
    /** 下拉框里搜索时匹配的文本 */
    fun searchText(): String = "$name $customer"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("customer", customer)

    companion object {
        fun from(o: JSONObject) = Platform(
            o.optString("id"), o.optString("name"), o.optString("customer")
        )
    }
}

data class ControllerModel(
    val id: String,
    val name: String,
    val platforms: List<Platform>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("platforms", JSONArray().apply { platforms.forEach { put(it.toJson()) } })

    companion object {
        fun from(o: JSONObject): ControllerModel {
            val arr = o.optJSONArray("platforms") ?: JSONArray()
            return ControllerModel(
                id = o.optString("id"),
                name = o.optString("name"),
                platforms = (0 until arr.length()).map { Platform.from(arr.getJSONObject(it)) },
            )
        }
    }
}

/** 故障类型：4S 店报修时的描述，SOP 三维键的第三维 */
data class FaultType(val id: String, val name: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)

    companion object {
        fun from(o: JSONObject) = FaultType(o.optString("id"), o.optString("name"))
    }
}

object Faults {
    private val seed = listOf(
        FaultType("comm", "通讯故障"),
        FaultType("fan", "风扇常转"),
        FaultType("igbt", "IGBT/NTC 故障"),
        FaultType("nostart", "无法上电"),
        FaultType("water", "水泵异常"),
        FaultType("other", "其他"),
    )

    private fun file(ctx: Context) = File(ctx.filesDir, "faults.json")

    fun load(ctx: Context): List<FaultType> {
        val f = file(ctx)
        if (!f.exists()) {
            save(ctx, seed)
            return seed
        }
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { FaultType.from(arr.getJSONObject(it)) }
        }.getOrDefault(seed)
    }

    fun save(ctx: Context, list: List<FaultType>) {
        runCatching {
            file(ctx).writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        }
    }
}

object Catalog {

    /** 写死的测试数据，首次启动时落盘，之后可被下载的目录覆盖 */
    private val seed = listOf(
        ControllerModel(
            "3in1", "三合一",
            listOf(
                Platform("3in1-a", "A平台", "小米"),
                Platform("3in1-b", "B平台", "丰田"),
            )
        ),
        ControllerModel(
            "8in1", "八合一",
            listOf(
                Platform("8in1-a", "A平台", "小米"),
                Platform("8in1-c", "C平台", "比亚迪"),
            )
        ),
        ControllerModel(
            "mcu", "电机控制器",
            listOf(Platform("mcu-std", "通用平台", ""))
        ),
    )

    private fun file(ctx: Context) = File(ctx.filesDir, "catalog.json")

    fun load(ctx: Context): List<ControllerModel> {
        val f = file(ctx)
        if (!f.exists()) {
            save(ctx, seed)
            return seed
        }
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { ControllerModel.from(arr.getJSONObject(it)) }
        }.getOrDefault(seed)
    }

    fun save(ctx: Context, list: List<ControllerModel>) {
        runCatching {
            file(ctx).writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        }
    }
}

/* ------------------------------------------------------------------
 * 应用设置
 * ------------------------------------------------------------------ */

data class AppSettings(
    /** 可见水印总开关。相机界面田字格里的 🚫 直接切它，不用进设置。 */
    val watermarkVisible: Boolean = true,
    /** 水印里的时间那一行 */
    val showTimeStamp: Boolean = true,
    /** 水印里的 SOP 步骤那一行 */
    val showSopStep: Boolean = true,
    /** 额外存一份无水印原图到 RAW 子目录，方便以后重烧水印 */
    val keepOriginal: Boolean = true,
    /** 车间多在室内，GPS 常年拿不到定位，默认关 */
    val recordGps: Boolean = false,
    /** 取景时持续识别条码。不用扫码的活儿可以关掉省电 */
    val scanInViewfinder: Boolean = true,
    /** 快门震动反馈 */
    val shutterVibrate: Boolean = true,
    /** 快门提示音。车间有噪音可能听不见，但戴手套时震动也不明显，两个都留 */
    val shutterSound: Boolean = true,
) {
    /** 两行都关等于没水印，那就别白跑一趟烧录 */
    val burnsAnything: Boolean
        get() = watermarkVisible && (showTimeStamp || showSopStep)

    fun toJson(): JSONObject = JSONObject()
        .put("watermarkVisible", watermarkVisible)
        .put("showTimeStamp", showTimeStamp)
        .put("showSopStep", showSopStep)
        .put("keepOriginal", keepOriginal)
        .put("recordGps", recordGps)
        .put("scanInViewfinder", scanInViewfinder)
        .put("shutterVibrate", shutterVibrate)
        .put("shutterSound", shutterSound)

    companion object {
        fun from(o: JSONObject): AppSettings {
            // 旧版只有一个 showSopOnPhoto，用它给新的总开关兜底，升级后设置不丢
            val legacy = o.optBoolean("showSopOnPhoto", true)
            return AppSettings(
                watermarkVisible = o.optBoolean("watermarkVisible", legacy),
                showTimeStamp = o.optBoolean("showTimeStamp", true),
                showSopStep = o.optBoolean("showSopStep", true),
                keepOriginal = o.optBoolean("keepOriginal", true),
                recordGps = o.optBoolean("recordGps", false),
                scanInViewfinder = o.optBoolean("scanInViewfinder", true),
                shutterVibrate = o.optBoolean("shutterVibrate", true),
                shutterSound = o.optBoolean("shutterSound", true),
            )
        }
    }
}

object SettingsStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "settings.json")

    fun load(ctx: Context): AppSettings {
        val f = file(ctx)
        if (!f.exists()) return AppSettings()
        return runCatching { AppSettings.from(JSONObject(f.readText())) }
            .getOrDefault(AppSettings())
    }

    fun save(ctx: Context, s: AppSettings) {
        runCatching { file(ctx).writeText(s.toJson().toString()) }
    }
}
