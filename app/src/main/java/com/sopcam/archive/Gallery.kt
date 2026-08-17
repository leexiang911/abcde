package com.sopcam.archive

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
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
        if (!r.exists() || serialNo.isBlank()) return emptyList()
        return r.listFiles { f -> f.isDirectory }
            ?.flatMap { day ->
                File(day, serialNo).takeIf { it.isDirectory }
                    ?.listFiles { f -> f.extension.equals("jpg", true) }?.toList() ?: emptyList()
            }
            ?.sortedBy { it.name } ?: emptyList()
    }

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
}
