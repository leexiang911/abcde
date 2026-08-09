package com.sopcam.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
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
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 一次待落盘的任务。快门回调只做「拿字节 + 入队」，微秒级返回，
 * 所以连拍时快门不会被烧录和编码拖住 —— 这是"性能要求高"的关键。
 */
data class PendingShot(
    val jpeg: ByteArray,
    val fileName: String,          // 不含扩展名，来自 SOP 步骤或语音备注
    val relativePath: String,      // DCIM/SopCam/20260809/WO-2317
    val content: WatermarkContent,
    val anchor: Anchor,
    val style: WatermarkStyle,
)

data class SavedShot(val uri: String, val displayName: String, val widthPx: Int, val heightPx: Int)

class CapturePipeline(
    private val context: Context,
    /** 落盘并发度。S25+ 上 2 就能吃满，再高只会抢内存 */
    concurrency: Int = 2,
    private val jpegQuality: Int = 92,
    private val maxLongSide: Int = 4032,
    private val onSaved: (SavedShot) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<PendingShot>(capacity = 16)
    private val gate = Semaphore(concurrency)

    /** 相机回调专用单线程池，别丢主线程 */
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
        // CPU 密集：解码 → 正立 → 烧录
        val bmp = WatermarkRenderer.decodeUpright(shot.jpeg, maxLongSide)
        WatermarkRenderer.burnIn(bmp, shot.content, shot.anchor, shot.style)

        val bytes = ByteArrayOutputStream(bmp.byteCount / 6).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            out.toByteArray()
        }
        val w = bmp.width; val h = bmp.height
        bmp.recycle()

        return withContext(Dispatchers.IO) { writeToMediaStore(shot, bytes, w, h) }
    }

    private fun writeToMediaStore(
        shot: PendingShot, bytes: ByteArray, w: Int, h: Int,
    ): SavedShot {
        val display = "${shot.fileName}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, display)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, shot.relativePath)
            put(MediaStore.Images.Media.WIDTH, w)
            put(MediaStore.Images.Media.HEIGHT, h)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert 失败：$display")

        resolver.openOutputStream(uri)!!.use { it.write(bytes) }

        // 图已物理正立，EXIF 必须写 NORMAL，否则电脑端会再转一次
        resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
            ExifInterface(pfd.fileDescriptor).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString()
                )
                setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, shot.fileName)
                saveAttributes()
            }
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return SavedShot(uri.toString(), display, w, h)
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
     * S25+ 上的取舍：主摄 200MP 原图单张解码就要 1s+，检修留档没必要。
     * 锁到 4:3 约 12MP（4000×3000 附近），快门到可拍下一张约 120–200ms。
     * 想要更高细节改成 ResolutionStrategy(Size(8160, 6120), FALLBACK_RULE_CLOSEST_LOWER)。
     */
    fun buildImageCapture(initialRotation: Int): ImageCapture =
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(95)                       // 原始质量拉高，压缩损失留给烧录那一次
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
        imageCapture: ImageCapture,
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
        return provider.bindToLifecycle(
            owner,
            CameraSelector.Builder().requireLensFacing(lensFacing).build(),
            UseCaseGroup.Builder().addUseCase(preview).addUseCase(imageCapture).build()
        )
    }
}

/** 快门：只取字节、只入队，不做任何图像处理 */
fun ImageCapture.shoot(
    pipeline: CapturePipeline,
    fileName: String,
    relativePath: String,
    content: WatermarkContent,
    anchor: Anchor,
    style: WatermarkStyle = WatermarkStyle(),
    onShutter: () -> Unit = {},
) {
    takePicture(pipeline.captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureStarted() = onShutter()

        override fun onCaptureSuccess(image: ImageProxy) {
            val buf = image.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            image.close()
            pipeline.submit(
                PendingShot(bytes, fileName, relativePath, content, anchor, style)
            )
        }

        override fun onError(exception: ImageCaptureException) {
            exception.printStackTrace()
        }
    })
}
