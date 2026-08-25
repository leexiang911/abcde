package com.sopcam.archive

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.sopcam.sop.FileNaming
import java.io.File

/**
 * 相册那一侧（水印照片）。
 *
 * 跟 Archive 分开：Archive 管归档区的原图，这里管 DCIM 下的成片。
 * 两边生命周期不一样 —— 成片可以随便删，删了还能从归档区重烧。
 */
object Gallery {

    fun root(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "SopCam")

    /**
     * 某个控制器的全部水印照片。
     *
     * 成片按日期分目录，同一个控制器跨天返修会落在不同日期下，
     * 所以得把所有日期目录翻一遍再按序列号收。
     */
    fun photosOf(serialNo: String): List<File> {
        val r = root()
        if (!r.exists()) return emptyList()
        // 空序列号落在「未命名」目录里，跟归档区和 FileNaming 保持一致。
        // 以前这里直接返回空，导致这类项目导出时一张水印图都找不到
        val folder = serialNo.ifBlank { FileNaming.UNNAMED }
        return r.listFiles { f -> f.isDirectory }
            ?.flatMap { day ->
                File(day, folder).takeIf { it.isDirectory }
                    ?.listFiles { f -> f.extension.equals("jpg", true) }?.toList() ?: emptyList()
            }
            ?.sortedBy { it.name } ?: emptyList()
    }

    /** 按相对路径和文件名定位一张成片。用来覆盖重烧前先删掉旧的 */
    fun fileAt(relativePath: String, display: String): File =
        File(Environment.getExternalStorageDirectory(), relativePath.trim('/') + "/" + display)

    /**
     * 删照片。
     *
     * 光 File.delete() 不够 —— MediaStore 里的记录还在，相册会留一堆打不开的空缩略图，
     * 所以删完要通知媒体库重扫这些路径。
     */
    fun delete(ctx: Context, files: List<File>): Int {
        if (files.isEmpty()) return 0
        val paths = files.map { it.absolutePath }
        val n = files.count { runCatching { it.delete() }.getOrDefault(false) }
        runCatching {
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
        }
        return n
    }

    /** 连空目录一起收拾掉，免得相册里剩一堆空文件夹 */
    fun pruneEmptyDirs(serialNo: String) {
        runCatching {
            root().listFiles { f -> f.isDirectory }?.forEach { day ->
                val dir = File(day, serialNo)
                if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) dir.delete()
                if (day.listFiles()?.isEmpty() == true) day.delete()
            }
        }
    }
    /**
     * 把某个序列号的成片整体搬到新序列号下。
     *
     * 相册按 日期/序列号 分目录，改名要把每个日期目录下的那一份都搬过去。
     * 搬完通知媒体库重扫，否则相册里旧路径会留一堆点不开的灰缩略图。
     */
    fun moveTo(ctx: Context, from: String, to: String): Boolean {
        val r = root()
        if (!r.exists()) return false
        val fromDir = from.ifBlank { FileNaming.UNNAMED }
        val toDir = to.ifBlank { FileNaming.UNNAMED }
        val touched = mutableListOf<String>()
        var any = false

        r.listFiles { f -> f.isDirectory }?.forEach { day ->
            val src = File(day, fromDir)
            if (!src.isDirectory) return@forEach
            val dst = File(day, toDir).apply { if (!exists()) mkdirs() }
            src.listFiles()?.forEach { f ->
                val target = File(dst, f.name)
                touched += f.absolutePath
                if (f.renameTo(target)) {
                    touched += target.absolutePath
                    any = true
                }
            }
            runCatching { if (src.listFiles().isNullOrEmpty()) src.delete() }
        }

        if (touched.isNotEmpty()) {
            runCatching {
                MediaScannerConnection.scanFile(ctx, touched.toTypedArray(), null, null)
            }
        }
        return any
    }

}
