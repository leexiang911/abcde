package com.sopcam.ai

import android.content.Context
import com.sopcam.archive.Archive
import com.sopcam.sop.SopTemplate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.File

/**
 * AI 读数跑批。
 *
 * 只做一件事：把一个项目里还没跑过的图，一张一张喂给 LiteRt，结果写回随行 json。
 *
 * 几个刻意的约束：
 *
 *  · **严格串行。** LiteRt 是单例单引擎，prepare() 还把图统一落到 cacheDir/ai-input.jpg
 *    这个固定路径上 —— 两个任务并发跑会互相覆盖输入图，拿到的答案张冠李戴。
 *
 *  · **一张一写盘。** 不攒到最后统一写。跑到一半退出、被系统杀掉、或者手机没电，
 *    已经出值的那些都还在，下次进来只跑剩下的 pending。
 *
 *  · **不在相机页跑。** 模型加载十几秒、吃一大块内存，CameraX 的预览和拍照流水线
 *    同时占着内存，一起跑容易 OOM，还会让取景掉帧。所以入口只放在项目详情页。
 */
object AiBatch {

    data class Task(val file: File, val prompt: String)

    /** 跑批结束时的交代。cancelled 是用户中途退出，不是失败 */
    data class Outcome(
        val done: Int,
        val failed: Int,
        val cancelled: Boolean,
        val error: String? = null,
    )

    /**
     * 这个项目里还等着跑的图。
     *
     * 判据是随行 json 里的 aiState == pending：
     * ok 是已经出过值的，rejected 是人工否掉的，两者都不该自动重跑。
     */
    fun pending(serialNo: String): List<Task> =
        Archive.shots(serialNo).mapNotNull { f ->
            val side = Archive.sidecar(f) ?: return@mapNotNull null
            if (side.optString("aiState") != "pending") return@mapNotNull null
            val prompt = side.optString("aiPrompt")
            if (prompt.isBlank()) return@mapNotNull null
            Task(f, prompt)
        }

    /**
     * 跑完一个项目的待办。
     *
     * 引擎没加载就先加载（十几秒，调用方记得先给个提示）。跑完**不卸载** ——
     * 用户多半会接着跑下一个项目，每次重新加载太亏。要释放内存去 AI 实验室点卸载。
     *
     * onProgress 在每张跑完后回调一次，参数是 (已完成, 总数, 这张的结果或错误)。
     * 回调在 IO 线程上，调用方自己切回主线程。
     */
    suspend fun run(
        ctx: Context,
        serialNo: String,
        modelPath: String,
        deviceName: String,
        template: SopTemplate?,
        onProgress: (Int, Int, String) -> Unit,
    ): Outcome {
        val tasks = pending(serialNo)
        // 分组只跑配了 prompt 的。只配 format 的组不用模型，报表那边现算
        val groups = template?.groups.orEmpty().filter { it.prompt.isNotBlank() }
        if (tasks.isEmpty() && groups.isEmpty()) return Outcome(0, 0, false)

        if (!LiteRt.isReady || LiteRt.loaded?.path != modelPath) {
            if (modelPath.isBlank()) {
                return Outcome(0, 0, false, "还没选模型：去 设置 → AI 实验室 加载一次")
            }
            val device = LiteRt.Device.entries.firstOrNull { it.name == deviceName }
                ?: LiteRt.Device.GPU
            val r = LiteRt.load(ctx, modelPath, device)
            r.exceptionOrNull()?.let {
                return Outcome(0, 0, false, "模型加载失败：${it.message ?: it.javaClass.simpleName}")
            }
        }

        val total = tasks.size + groups.size
        var done = 0
        var failed = 0
        for (t in tasks) {
            // 用户退出详情页时协程被取消，在这儿收手 —— 当前这张丢掉，下次重跑
            if (!currentCoroutineContext().isActive) {
                return Outcome(done, failed, true)
            }

            val r = LiteRt.askImage(ctx, t.file.absolutePath, t.prompt)
            val answer = r.getOrNull()
            if (answer == null || answer.text.isBlank()) {
                failed++
                // 失败的留在 pending，下次还能再试 —— 有可能只是这次内存紧张
                onProgress(done + failed, total, "读不出来：${t.file.name}")
            } else {
                Archive.setAiResult(t.file, answer.text, "ok")
                done++
                onProgress(done + failed, total, answer.text)
            }
        }

        // 分组放在最后：组级提示词要把成员的读数拼进去问，
        // 成员没跑完就问，等于拿一堆「—」去让模型下结论
        val results = Archive.groupAi(serialNo).toMutableMap()
        for (g in groups) {
            if (!currentCoroutineContext().isActive) return Outcome(done, failed, true)

            val shots = Placeholders.indexOf(serialNo, template)
            val (text, missing) = Placeholders.expand(g.prompt, shots, results)
            if (missing) {
                // 成员还缺读数就跳过，不硬跑 —— 拿「—」问出来的结论是错的，
                // 而且会被当成有效结果存下来，比没有更糟
                failed++
                onProgress(done + failed, total, "${g.name}：成员读数还不全，跳过")
                continue
            }

            val r = LiteRt.ask(text).getOrNull()
            if (r == null || r.text.isBlank()) {
                failed++
                onProgress(done + failed, total, "${g.name}：没出结论")
            } else {
                Archive.setGroupAi(serialNo, g.id, r.text)
                results[g.id] = r.text
                done++
                onProgress(done + failed, total, "${g.name}：${r.text}")
            }
        }
        return Outcome(done, failed, false)
    }
}
