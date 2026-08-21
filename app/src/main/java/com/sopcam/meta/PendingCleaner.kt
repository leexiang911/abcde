package com.sopcam.meta

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

/**
 * 清理写到一半的孤儿条目。
 *
 * MediaWriter 先插一条 IS_PENDING=1 的记录，写完字节再改成 0。
 * 如果进程死在中间（连拍完立刻从最近任务划掉，或者系统内存紧张杀后台），
 * 就留下一条**永远待定**的记录：文件占着存储，相册里看不见，App 也不知道它存在。
 *
 * 这种垃圾不会自己消失，攒几次就是几百兆看不见的占用。
 * 所以每次启动扫一遍，把自己名下的待定条目清掉。
 *
 * 只能看到自己写的那些 —— MediaStore 的 IS_PENDING 查询天然按 owner 隔离，
 * 不会误删别的 App 正在写的东西。
 */
object PendingCleaner {

    fun sweep(ctx: Context): Int {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = ctx.contentResolver
        var removed = 0

        runCatching {
            // IS_PENDING 的条目默认查不到，要显式要求包含
            val query = MediaStore.setIncludePending(uri)
            resolver.query(
                query,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH),
                "${MediaStore.Images.Media.IS_PENDING} = 1",
                null,
                null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                while (c.moveToNext()) {
                    // 再加一道保险：只动我们自己那棵目录树
                    val path = c.getString(pathCol) ?: ""
                    if (!path.contains("SopCam")) continue
                    val id = c.getLong(idCol)
                    runCatching {
                        resolver.delete(ContentUris.withAppendedId(uri, id), null, null)
                        removed++
                    }
                }
            }
        }
        return removed
    }
}
