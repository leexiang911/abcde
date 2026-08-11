package com.sopcam.meta

import android.os.Build
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一张成片的全部业务信息。
 *
 * 拆成两处落地：
 *   · 标准字段（时间 / GPS / 机型）走 EXIF —— Windows 属性面板、看图软件都认
 *   · 业务字段（工单 / 型号 / 步骤）走 XMP —— 自定义命名空间，exiftool 一条命令批量导表
 *
 * 原图和水印图写的是同一份，所以以后拿原图重烧水印，信息一个不缺。
 */
data class ImageMeta(
    val imageId: String,
    val capturedAt: Long,
    val workOrder: String = "",
    val serialNo: String = "",
    val modelName: String = "",
    val platformName: String = "",
    val stepOrder: Int = 0,
    val stepName: String = "",
    val stepRefDes: String = "",
    val codeValue: String = "",
    val codeFormat: String = "",
    val anchor: String = "",
    val topEdge: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val hasWatermark: Boolean = true,
) {
    val deviceMake: String get() = Build.MANUFACTURER
    val deviceModel: String get() = Build.MODEL
    val androidRelease: String get() = "Android ${Build.VERSION.RELEASE}"
}

object Xmp {

    private const val NS = "https://sopcam.app/ns/1.0/"
    private const val HEADER = "http://ns.adobe.com/xap/1.0/\u0000"
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    private fun esc(v: String): String = v
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** 拼一段标准 XMP packet。属性都用简写形式，够紧凑也够标准。 */
    fun build(meta: ImageMeta): String {
        val fields = buildList {
            add("sopcam:ImageId" to meta.imageId)
            add("sopcam:CapturedAt" to isoFmt.format(Date(meta.capturedAt)))
            add("sopcam:HasWatermark" to meta.hasWatermark.toString())
            add("sopcam:Device" to "${meta.deviceMake} ${meta.deviceModel} / ${meta.androidRelease}")
            if (meta.workOrder.isNotBlank()) add("sopcam:WorkOrder" to meta.workOrder)
            if (meta.serialNo.isNotBlank()) add("sopcam:SerialNo" to meta.serialNo)
            if (meta.modelName.isNotBlank()) add("sopcam:ControllerModel" to meta.modelName)
            if (meta.platformName.isNotBlank()) add("sopcam:Platform" to meta.platformName)
            if (meta.stepOrder > 0) {
                add("sopcam:StepOrder" to meta.stepOrder.toString())
                add("sopcam:StepName" to meta.stepName)
                if (meta.stepRefDes.isNotBlank()) add("sopcam:StepRefDes" to meta.stepRefDes)
            }
            if (meta.codeValue.isNotBlank()) {
                add("sopcam:CodeValue" to meta.codeValue)
                add("sopcam:CodeFormat" to meta.codeFormat)
            }
            if (meta.anchor.isNotBlank()) add("sopcam:WatermarkAnchor" to meta.anchor)
            if (meta.topEdge.isNotBlank()) add("sopcam:TopEdge" to meta.topEdge)
            meta.latitude?.let { add("sopcam:Latitude" to it.toString()) }
            meta.longitude?.let { add("sopcam:Longitude" to it.toString()) }
        }

        val attrs = fields.joinToString("\n    ") { (k, v) -> """$k="${esc(v)}"""" }

        return """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="SopCam">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:sopcam="$NS"
    $attrs/>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }

    /**
     * 把 XMP 塞进 JPEG 的 APP1 段。
     *
     * JPEG 的结构是 SOI 后面跟一串段，每段 FFxx + 两字节长度 + 内容。
     * 插入点选在所有 APPn 段之后 —— 这样 EXIF 那段还在最前面，
     * 各家解析器扫到自己认识的段就停，谁也不影响谁。
     *
     * 单段上限 65533 字节，我们这点数据连零头都不到，所以不做分段。
     */
    fun inject(jpeg: ByteArray, xmp: String): ByteArray {
        if (jpeg.size < 4 || (jpeg[0].toInt() and 0xFF) != 0xFF || (jpeg[1].toInt() and 0xFF) != 0xD8) {
            return jpeg   // 不是 JPEG，原样返回，不冒险
        }

        val payload = HEADER.toByteArray(Charsets.UTF_8) + xmp.toByteArray(Charsets.UTF_8)
        if (payload.size + 2 > 65535) return jpeg

        val insertAt = findInsertPoint(jpeg)

        return ByteArrayOutputStream(jpeg.size + payload.size + 4).use { out ->
            out.write(jpeg, 0, insertAt)
            out.write(0xFF)
            out.write(0xE1)
            val len = payload.size + 2
            out.write((len shr 8) and 0xFF)
            out.write(len and 0xFF)
            out.write(payload)
            out.write(jpeg, insertAt, jpeg.size - insertAt)
            out.toByteArray()
        }
    }

    /** 从 SOI 之后往下走，跳过所有 APPn（FFE0–FFEF），停在第一个别的段前面 */
    private fun findInsertPoint(jpeg: ByteArray): Int {
        var i = 2
        while (i + 4 <= jpeg.size) {
            if ((jpeg[i].toInt() and 0xFF) != 0xFF) break
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker !in 0xE0..0xEF) break
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (len < 2) break
            i += 2 + len
        }
        return i.coerceIn(2, jpeg.size)
    }
}
