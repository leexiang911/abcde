package com.sopcam.capture

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.sopcam.archive.Archive
import com.sopcam.meta.ImageMeta
import com.sopcam.meta.MediaWriter
import com.sopcam.watermark.Anchor
import com.sopcam.watermark.WatermarkContent
import com.sopcam.watermark.WatermarkRenderer
import com.sopcam.watermark.WatermarkStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 一次待落盘的任务。快门回调只做「拿字节 + 入队」，微秒级返回，
 * 所以连拍时快门不会被烧录和编码拖住 —— 这是性能的关键。
 */
data class PendingShot(
    val jpeg: ByteArray,
    val fileName: String,
    val relativePath: String,
    val content: WatermarkContent,
    val anchor: Anchor,
    val meta: ImageMeta,
    val burnWatermark: Boolean,
    val keepOriginal: Boolean,
    val headline: String? = null,
    val lines: List<String> = emptyList(),
    val style: WatermarkStyle = WatermarkStyle(),
)

data class SavedShot(val uri: String, val displayName: String, val widthPx: Int, val heightPx: Int)

class CapturePipeline(
    private val context: Context,
    concurrency: Int = 2,
    private val jpegQuality: Int = 92,
    private val maxLongSide: Int = 4032,
    private val onSaved: (SavedShot) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val onArchiveIssue: (String?) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<PendingShot>(capacity = 16)
    private val gate = Semaphore(concurrency)

    val captureExecutor = Executors.newSingleThreadExecutor()

    init {
        scope.launch {
            for (shot in queue) {
                launch {
                    gate.withPermit {
                        runCatching { process(shot) }
                            .onSuccess(onSaved)
                            .onFailure(onError)
                    }
                }
            }
        }
    }

    private suspend fun process(shot: PendingShot): SavedShot {
        // 一次解码 → 正立。原图和水印图共用这张位图，只是编码两次。
        val bmp = WatermarkRenderer.decodeUpright(shot.jpeg, maxLongSide)
        val w = bmp.width
        val h = bmp.height

        // 趁位图还没被水印污染，对全分辨率成片再扫一次码。
        // 预览那路只有 1600x1200，板子上的 Data Matrix 模块细，经常扫不出；
        // 这张是 4000x3000，多六倍像素，成功率高得多。
        // 预览已经扫到的话就不重复跑 —— 那说明码足够清楚。
        val meta = if (shot.meta.codeValue.isNotBlank()) shot.meta else {
            Codes.scan(bmp)?.let { shot.meta.copy(codeValue = it.value, codeFormat = it.format) }
                ?: shot.meta
        }

        // 原图进归档区，不进相册 —— 相册里只放水印照片，
        // 原图是给"以后重烧水印"用的兜底数据，混进相册只会看着乱
        if (shot.keepOriginal) {
            val rawBytes = encode(bmp)
            val result = withContext(Dispatchers.IO) {
                Archive.save(
                    serialNo = meta.serialNo,
                    jpeg = rawBytes,
                    meta = meta,
                    watermarkLines = shot.lines,
                    headline = shot.headline,
                    fileName = shot.fileName,
                    relativePath = shot.relativePath,
                )
            }
            // 归档失败不影响这张水印照片落盘，但必须让人当场知道
            onArchiveIssue(
                when (result) {
                    is Archive.SaveResult.Ok -> null
                    is Archive.SaveResult.NoPermission -> "原图没存下来：缺少文件访问权限，去设置里开启"
                    is Archive.SaveResult.Failed -> "原图没存下来：${result.reason}"
                }
            )
        }

        val finalBytes = if (shot.burnWatermark) {
            WatermarkRenderer.burnIn(bmp, shot.content, shot.anchor, shot.style)
            encode(bmp)
        } else {
            // 关掉可见水印时不重复编码，直接复用原图那份字节
            encode(bmp)
        }
        bmp.recycle()

        return withContext(Dispatchers.IO) {
            write(finalBytes, "${shot.fileName}.jpg", shot.relativePath, meta, w, h)
        }
    }

    private fun encode(bmp: Bitmap): ByteArray =
        ByteArrayOutputStream(bmp.byteCount / 6).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            out.toByteArray()
        }

    private fun write(
        bytes: ByteArray,
        display: String,
        path: String,
        meta: ImageMeta,
        w: Int,
        h: Int,
    ): SavedShot {
        val r = MediaWriter.write(context, bytes, display, path, meta, w, h)
        return SavedShot(r.uri, r.displayName, r.widthPx, r.heightPx)
    }

    fun submit(shot: PendingShot) {
        if (queue.trySend(shot).isFailure) onError(IllegalStateException("落盘队列已满，请稍等"))
    }

    fun shutdown() {
        queue.close()
        captureExecutor.shutdown()
    }
}

/* ------------------------------------------------------------------
 * CameraX 绑定
 * ------------------------------------------------------------------ */

object CameraBinder {

    /**
     * S25+ 主摄 200MP，但单张 200MP 解码就要一秒以上，检修留档没必要。
     * 锁 4:3 约 12MP，快门到可拍下一张约 120–200ms。
     * 要更高细节就把 Size 改成 8160x6120，代价是单张 ~600ms。
     */
    fun buildImageCapture(initialRotation: Int): ImageCapture =
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(95)
            .setTargetRotation(initialRotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(4000, 3000),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .build()

    suspend fun bind(
        context: Context,
        owner: LifecycleOwner,
        preview: Preview,
        imageCapture: ImageCapture?,
        analysis: ImageAnalysis? = null,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    ): Camera {
        val provider = suspendCoroutine<ProcessCameraProvider> { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { cont.resume(future.get()) },
                ContextCompat.getMainExecutor(context)
            )
        }
        provider.unbindAll()
        val group = UseCaseGroup.Builder().addUseCase(preview)
        imageCapture?.let { group.addUseCase(it) }
        analysis?.let { group.addUseCase(it) }
        return provider.bindToLifecycle(
            owner,
            CameraSelector.Builder().requireLensFacing(lensFacing).build(),
            group.build()
        )
    }
}

/**
 * 光学控制的薄封装。
 *
 * CameraX 把这几样分在两处：变焦和常亮在 CameraControl，
 * 拍照瞬间的闪光在 ImageCapture。这里统一收口，界面层不用管这个区别。
 */
object Optics {

    fun zoomRange(camera: Camera): Pair<Float, Float> {
        val z = camera.cameraInfo.zoomState.value
        return (z?.minZoomRatio ?: 1f) to (z?.maxZoomRatio ?: 1f)
    }

    fun currentZoom(camera: Camera): Float =
        camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f

    /** 夹在设备真实范围内，超出会被 CameraX 直接拒绝 */
    fun setZoom(camera: Camera, ratio: Float): Float {
        val (lo, hi) = zoomRange(camera)
        val target = ratio.coerceIn(lo, hi)
        camera.cameraControl.setZoomRatio(target)
        return target
    }

    fun exposureRange(camera: Camera): IntRange {
        val st = camera.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) return 0..0
        return st.exposureCompensationRange.lower..st.exposureCompensationRange.upper
    }

    /** 一档等于多少 EV，各家不一样，得问相机要，不能写死 */
    fun evPerStep(camera: Camera): Float {
        val st = camera.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) return 0f
        return st.exposureCompensationStep.toFloat()
    }

    fun setExposure(camera: Camera, index: Int): Int {
        val r = exposureRange(camera)
        val target = index.coerceIn(r.first, r.last)
        camera.cameraControl.setExposureCompensationIndex(target)
        return target
    }

    /**
     * 触发一次对焦并测光。
     *
     * 两种模式都关掉 CameraX 的自动取消（默认 5 秒后自己解锁）——
     * 单次由拍照后手动取消，持久锁一直留着，什么时候解锁由界面说了算。
     * 不这么做的话，用户锁好焦点等着构图，5 秒一到焦点就悄悄跑了。
     */
    fun startFocus(
        camera: Camera,
        point: MeteringPoint,
        executor: Executor,
        onResult: (Boolean) -> Unit,
    ) {
        val action = buildAction(point)
        val future = camera.cameraControl.startFocusAndMetering(action)
        future.addListener({
            // 对焦被新的对焦请求打断时 get() 会抛，按失败处理
            val ok = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)

            // 失败必须立刻放开 3A 锁。
            // disableAutoCancel 的本意是"焦点别自己跑掉"，但对焦失败后锁还扣着，
            // 三星在 AF 锁未释放时会直接忽略 setZoomRatio —— 表现就是变焦点不动，
            // 退出相机重新 bind 换了 CameraControl 才恢复。
            if (!ok) camera.cameraControl.cancelFocusAndMetering()
            onResult(ok)
        }, executor)
    }

    private fun buildAction(point: MeteringPoint) = FocusMeteringAction.Builder(
        point,
        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
    ).disableAutoCancel().build()

    fun isFocusSupported(camera: Camera, point: MeteringPoint): Boolean =
        runCatching { camera.cameraInfo.isFocusMeteringSupported(buildAction(point)) }
            .getOrDefault(false)

    fun cancelFocus(camera: Camera?) {
        camera?.cameraControl?.cancelFocusAndMetering()
    }

    fun applyFlash(camera: Camera?, imageCapture: ImageCapture, torch: Boolean, fireOnShot: Boolean) {
        camera?.cameraControl?.enableTorch(torch)
        imageCapture.flashMode =
            if (fireOnShot) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }
}

/** 快门：只取字节、只入队，不做任何图像处理 */
fun ImageCapture.shoot(pipeline: CapturePipeline, shot: (ByteArray) -> PendingShot) {
    takePicture(pipeline.captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val buf = image.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            image.close()
            pipeline.submit(shot(bytes))
        }

        override fun onError(exception: ImageCaptureException) {
            exception.printStackTrace()
        }
    })
}
