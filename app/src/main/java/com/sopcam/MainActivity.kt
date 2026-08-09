package com.sopcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sopcam.capture.CameraBinder
import com.sopcam.capture.CapturePipeline
import com.sopcam.capture.shoot
import com.sopcam.ui.CameraScreen
import com.sopcam.ui.PermissionGate
import com.sopcam.watermark.Anchor
import com.sopcam.watermark.OrientationController
import com.sopcam.watermark.OrientationLock
import com.sopcam.watermark.WatermarkContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 阶段一：相机 + 方向锁 + 水印烧录。
 *
 * 验收方法：锁横屏拍一张、锁竖屏拍一张，插线传到电脑，
 * 用 Windows 照片打开——水印必须在同一个角，且文字水平可读。
 * 这条过了，SOP 和语音才有意义往上叠。
 */
class MainActivity : ComponentActivity() {

    private lateinit var pipeline: CapturePipeline
    private lateinit var imageCapture: ImageCapture
    private lateinit var orientation: OrientationController

    private var hasPermission by mutableStateOf(false)
    private var anchor by mutableStateOf(Anchor.BOTTOM_LEFT)
    private var lock by mutableStateOf(OrientationLock.AUTO)
    private var queueDepth by mutableIntStateOf(0)
    private var lastSaved by mutableStateOf<String?>(null)

    /** 阶段二会换成工单号；现在先用日期分文件夹 */
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val nameFmt = SimpleDateFormat("HHmmss", Locale.US)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        pipeline = CapturePipeline(
            context = this,
            onSaved = { saved ->
                lifecycleScope.launch(Dispatchers.Main) {
                    queueDepth = (queueDepth - 1).coerceAtLeast(0)
                    lastSaved = saved.displayName
                }
            },
            onError = { err ->
                lifecycleScope.launch(Dispatchers.Main) {
                    queueDepth = (queueDepth - 1).coerceAtLeast(0)
                    lastSaved = "失败：${err.message}"
                }
            }
        )

        imageCapture = CameraBinder.buildImageCapture(android.view.Surface.ROTATION_0)

        orientation = OrientationController(this) { rotation ->
            imageCapture.targetRotation = rotation
        }

        setContent {
            if (!hasPermission) {
                PermissionGate {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            } else {
                CameraScreen(
                    anchor = anchor,
                    lock = lock,
                    queueDepth = queueDepth,
                    lastSaved = lastSaved,
                    onAnchorToggle = { anchor = anchor.next() },
                    onLockToggle = {
                        lock = lock.next()
                        orientation.lock = lock
                    },
                    onShutter = ::capture,
                    bindPreview = ::bindPreview
                )
            }
        }
    }

    private fun bindPreview(view: PreviewView) {
        val preview = Preview.Builder().build().apply {
            surfaceProvider = view.surfaceProvider
        }
        lifecycleScope.launch {
            runCatching {
                CameraBinder.bind(this@MainActivity, this@MainActivity, preview, imageCapture)
            }.onFailure {
                withContext(Dispatchers.Main) { lastSaved = "相机启动失败：${it.message}" }
            }
        }
    }

    private fun capture() {
        val now = System.currentTimeMillis()
        queueDepth += 1
        imageCapture.shoot(
            pipeline = pipeline,
            fileName = "SOP_${nameFmt.format(Date(now))}",
            relativePath = "DCIM/SopCam/${dayFmt.format(Date(now))}",
            content = WatermarkContent(
                headline = "待接入 SOP 步骤",
                lines = listOf(
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(now)),
                    "工单 —— · 序列号 ——"
                )
            ),
            anchor = anchor
        )
    }

    override fun onStart() {
        super.onStart()
        orientation.enable()
    }

    override fun onStop() {
        orientation.disable()
        super.onStop()
    }

    override fun onDestroy() {
        pipeline.shutdown()
        super.onDestroy()
    }
}

private fun Anchor.next(): Anchor = when (this) {
    Anchor.BOTTOM_LEFT -> Anchor.BOTTOM_RIGHT
    Anchor.BOTTOM_RIGHT -> Anchor.TOP_RIGHT
    Anchor.TOP_RIGHT -> Anchor.TOP_LEFT
    Anchor.TOP_LEFT -> Anchor.BOTTOM_LEFT
}

private fun OrientationLock.next(): OrientationLock = when (this) {
    OrientationLock.AUTO -> OrientationLock.PORTRAIT
    OrientationLock.PORTRAIT -> OrientationLock.LANDSCAPE
    OrientationLock.LANDSCAPE -> OrientationLock.AUTO
}
