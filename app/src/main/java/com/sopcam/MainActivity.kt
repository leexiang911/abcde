package com.sopcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sopcam.capture.CameraBinder
import com.sopcam.capture.CapturePipeline
import com.sopcam.capture.shoot
import com.sopcam.sop.FileNaming
import com.sopcam.sop.Session
import com.sopcam.sop.SopStep
import com.sopcam.sop.SopStore
import com.sopcam.sop.SopTemplate
import com.sopcam.ui.CameraScreen
import com.sopcam.ui.Panel
import com.sopcam.ui.PermissionGate
import com.sopcam.ui.SetupScreen
import com.sopcam.ui.TemplateEditScreen
import com.sopcam.watermark.Anchor
import com.sopcam.watermark.OrientationController
import com.sopcam.watermark.TopEdge
import com.sopcam.watermark.WatermarkContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Screen { SETUP, TEMPLATE_EDIT, CAMERA }

class MainActivity : ComponentActivity() {

    private lateinit var pipeline: CapturePipeline
    private lateinit var imageCapture: ImageCapture
    private lateinit var orientation: OrientationController

    private var hasPermission by mutableStateOf(false)
    private var screen by mutableStateOf(Screen.SETUP)

    private val templates = mutableStateListOf<SopTemplate>()
    private var workOrder by mutableStateOf("")
    private var serialNo by mutableStateOf("")
    private var templateId by mutableStateOf("")
    private var stepIndex by mutableIntStateOf(0)
    private var shotCounts by mutableStateOf<Map<Int, Int>>(emptyMap())

    private var anchor by mutableStateOf(Anchor.BOTTOM_LEFT)
    private var topEdge by mutableStateOf(TopEdge.AUTO)
    private var effectiveEdge by mutableStateOf(TopEdge.TOP)
    private var panel by mutableStateOf(Panel.NONE)
    private var queueDepth by mutableIntStateOf(0)
    private var lastSaved by mutableStateOf<String?>(null)

    private val stampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    private val activeSteps: List<SopStep>
        get() = templates.firstOrNull { it.id == templateId }?.steps ?: emptyList()

    private val currentStep: SopStep?
        get() = activeSteps.getOrNull(stepIndex)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        templates.addAll(SopStore.loadTemplates(this))
        SopStore.loadSession(this).let { s ->
            workOrder = s.workOrder
            serialNo = s.serialNo
            templateId = s.templateId
            stepIndex = s.stepIndex
            shotCounts = s.shotCounts
        }

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

        imageCapture = CameraBinder.buildImageCapture(Surface.ROTATION_0)
        orientation = OrientationController(this) { rot ->
            imageCapture.targetRotation = rot
            effectiveEdge = TopEdge.of(rot)
        }

        setContent {
            if (!hasPermission) {
                PermissionGate { permissionLauncher.launch(Manifest.permission.CAMERA) }
                return@setContent
            }
            when (screen) {
                Screen.SETUP -> SetupScreen(
                    workOrder = workOrder,
                    serialNo = serialNo,
                    templates = templates,
                    activeTemplateId = templateId,
                    onWorkOrderChange = { workOrder = it },
                    onSerialChange = { serialNo = it },
                    onTemplatePick = { id ->
                        if (templateId != id) {
                            templateId = id
                            stepIndex = 0
                            shotCounts = emptyMap()
                        }
                    },
                    onNewTemplate = { screen = Screen.TEMPLATE_EDIT },
                    onDeleteTemplate = { id ->
                        templates.removeAll { it.id == id }
                        if (templateId == id) templateId = ""
                        SopStore.saveTemplates(this, templates.toList())
                    },
                    onStart = {
                        persist()
                        screen = Screen.CAMERA
                    }
                )

                Screen.TEMPLATE_EDIT -> TemplateEditScreen(
                    onSave = { t ->
                        templates.add(t)
                        SopStore.saveTemplates(this, templates.toList())
                        templateId = t.id
                        stepIndex = 0
                        shotCounts = emptyMap()
                        screen = Screen.SETUP
                    },
                    onCancel = { screen = Screen.SETUP }
                )

                Screen.CAMERA -> CameraScreen(
                    steps = activeSteps,
                    currentIndex = stepIndex,
                    shotCounts = shotCounts,
                    anchor = anchor,
                    edge = topEdge,
                    effectiveEdge = effectiveEdge,
                    panel = panel,
                    watermarkHeadline = watermarkHeadline(),
                    watermarkLines = watermarkLines(System.currentTimeMillis()),
                    queueDepth = queueDepth,
                    lastSaved = lastSaved,
                    onStepSelect = { stepIndex = it },
                    onPanelChange = { panel = it },
                    onAnchorPick = { anchor = it },
                    onEdgePick = {
                        topEdge = it
                        orientation.topEdge = it
                    },
                    onShutter = ::capture,
                    onExit = {
                        persist()
                        screen = Screen.SETUP
                    },
                    bindPreview = ::bindPreview
                )
            }
        }
    }

    /** 预览层和烧录层共用，保证所见即所得 */
    private fun watermarkHeadline(): String? = currentStep?.let {
        "${it.order.toString().padStart(2, '0')} · ${it.label()}"
    }

    private fun watermarkLines(at: Long): List<String> = buildList {
        add(stampFmt.format(Date(at)))
        val id = listOfNotNull(
            workOrder.takeIf { it.isNotBlank() }?.let { "工单 $it" },
            serialNo.takeIf { it.isNotBlank() }?.let { "SN $it" }
        ).joinToString("  ")
        if (id.isNotBlank()) add(id)
    }

    private fun persist() {
        SopStore.saveSession(
            this,
            Session(workOrder, serialNo, templateId, stepIndex, shotCounts)
        )
    }

    private fun bindPreview(view: PreviewView) {
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(view.surfaceProvider)
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
        val step = currentStep
        val taken = step?.let { shotCounts[it.order] ?: 0 } ?: 0

        queueDepth += 1
        imageCapture.shoot(
            pipeline = pipeline,
            fileName = FileNaming.build(step, taken + 1, now),
            relativePath = FileNaming.relativePath(workOrder, serialNo, now),
            content = WatermarkContent(watermarkHeadline(), watermarkLines(now)),
            anchor = anchor
        )

        if (step != null) {
            val next = taken + 1
            shotCounts = shotCounts + (step.order to next)
            // 拍够了自动跳下一步，省一次点击
            if (next >= step.shots && stepIndex < activeSteps.lastIndex) stepIndex += 1
            persist()
        }
    }

    override fun onStart() {
        super.onStart()
        orientation.enable()
    }

    override fun onStop() {
        orientation.disable()
        persist()
        super.onStop()
    }

    override fun onDestroy() {
        pipeline.shutdown()
        super.onDestroy()
    }
}
