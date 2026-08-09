package com.sopcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
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
import com.sopcam.capture.PendingShot
import com.sopcam.capture.shoot
import com.sopcam.meta.ImageMeta
import com.sopcam.sop.AppSettings
import com.sopcam.sop.Catalog
import com.sopcam.sop.ControllerModel
import com.sopcam.sop.FileNaming
import com.sopcam.sop.Session
import com.sopcam.sop.SettingsStore
import com.sopcam.sop.SopStep
import com.sopcam.sop.SopStore
import com.sopcam.sop.SopTemplate
import com.sopcam.ui.CameraScreen
import com.sopcam.ui.OverlayPanel
import com.sopcam.ui.PermissionGate
import com.sopcam.ui.PickOption
import com.sopcam.ui.PickerSheet
import com.sopcam.ui.SettingsScreen
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
import java.util.UUID

private enum class Screen { SETUP, TEMPLATE_EDIT, SETTINGS, CAMERA }

/** 开工页上弹出的哪个选择器 */
private enum class Sheet { NONE, MODEL, PLATFORM }

class MainActivity : ComponentActivity() {

    private lateinit var pipeline: CapturePipeline
    private lateinit var imageCapture: ImageCapture
    private lateinit var orientation: OrientationController

    private var hasPermission by mutableStateOf(false)
    private var screen by mutableStateOf(Screen.SETUP)
    private var sheet by mutableStateOf(Sheet.NONE)

    private val templates = mutableStateListOf<SopTemplate>()
    private var catalog by mutableStateOf<List<ControllerModel>>(emptyList())
    private var settings by mutableStateOf(AppSettings())

    private var workOrder by mutableStateOf("")
    private var serialNo by mutableStateOf("")
    private var modelId by mutableStateOf("")
    private var platformId by mutableStateOf("")
    private var templateId by mutableStateOf("")
    private var stepIndex by mutableIntStateOf(0)
    private var shotCounts by mutableStateOf<Map<Int, Int>>(emptyMap())

    private var anchor by mutableStateOf(Anchor.BOTTOM_LEFT)
    private var topEdge by mutableStateOf(TopEdge.AUTO)
    private var effectiveEdge by mutableStateOf(TopEdge.TOP)
    private var panel by mutableStateOf(OverlayPanel.NONE)
    private var queueDepth by mutableIntStateOf(0)
    private var lastSaved by mutableStateOf<String?>(null)

    /** 水印上的时间只到分钟 —— 秒对留档没意义，还占宽度 */
    private val stampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    private val activeModel: ControllerModel?
        get() = catalog.firstOrNull { it.id == modelId }

    private val modelOption: PickOption?
        get() = activeModel?.let { PickOption(it.id, it.name) }

    private val platformOption: PickOption?
        get() = activeModel?.platforms?.firstOrNull { it.id == platformId }
            ?.let { PickOption(it.id, it.name, it.customer) }

    private val activeSteps: List<SopStep>
        get() = templates.firstOrNull { it.id == templateId }?.steps ?: emptyList()

    private val currentStep: SopStep?
        get() = activeSteps.getOrNull(stepIndex)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            hasPermission = granted[Manifest.permission.CAMERA] == true
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        templates.addAll(SopStore.loadTemplates(this))
        catalog = Catalog.load(this)
        settings = SettingsStore.load(this)
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
                PermissionGate { requestPermissions() }
                return@setContent
            }
            when (screen) {
                Screen.SETUP -> {
                    SetupScreen(
                        modelOption = modelOption,
                        platformOption = platformOption,
                        platformEnabled = activeModel != null,
                        onModelTap = { sheet = Sheet.MODEL },
                        onPlatformTap = { if (activeModel != null) sheet = Sheet.PLATFORM },
                        onSettings = { screen = Screen.SETTINGS },
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
                    when (sheet) {
                        Sheet.MODEL -> PickerSheet(
                            title = "控制器型号",
                            options = catalog.map { PickOption(it.id, it.name) },
                            selectedId = modelId,
                            onPick = { opt ->
                                modelId = opt?.id ?: ""
                                platformId = ""      // 换型号了，平台得重选
                                sheet = Sheet.NONE
                            },
                            onDismiss = { sheet = Sheet.NONE }
                        )
                        Sheet.PLATFORM -> PickerSheet(
                            title = "分类平台",
                            options = activeModel?.platforms
                                ?.map { PickOption(it.id, it.name, it.customer) } ?: emptyList(),
                            selectedId = platformId,
                            onPick = { opt ->
                                platformId = opt?.id ?: ""
                                sheet = Sheet.NONE
                            },
                            onDismiss = { sheet = Sheet.NONE }
                        )
                        Sheet.NONE -> Unit
                    }
                }

                Screen.SETTINGS -> SettingsScreen(
                    settings = settings,
                    onChange = {
                        settings = it
                        SettingsStore.save(this, it)
                        if (it.recordGps) requestPermissions()
                    },
                    onBack = { screen = Screen.SETUP }
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
                    watermarkHeadline = if (settings.showSopOnPhoto) watermarkHeadline() else null,
                    watermarkLines = if (settings.showSopOnPhoto)
                        watermarkLines(System.currentTimeMillis()) else emptyList(),
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

    private fun requestPermissions() {
        val wanted = mutableListOf(Manifest.permission.CAMERA)
        if (settings.recordGps) {
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
            wanted += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        permissionLauncher.launch(wanted.toTypedArray())
    }

    /** 预览层和烧录层共用，保证所见即所得 */
    private fun watermarkHeadline(): String? = currentStep?.let {
        "${it.order.toString().padStart(2, '0')} · ${it.label()}"
    }

    private fun watermarkLines(at: Long): List<String> = buildList {
        add(stampFmt.format(Date(at)))
        val cls = listOfNotNull(
            activeModel?.name,
            platformOption?.let { p -> p.label + (if (p.sub.isNotBlank()) "(${p.sub})" else "") }
        ).joinToString(" · ")
        if (cls.isNotBlank()) add(cls)
        val id = listOfNotNull(
            workOrder.takeIf { it.isNotBlank() }?.let { "工单 $it" },
            serialNo.takeIf { it.isNotBlank() }?.let { "SN $it" }
        ).joinToString("  ")
        if (id.isNotBlank()) add(id)
    }

    /**
     * 室内基本拿不到定位，所以只取最后一次已知位置，不主动请求更新——
     * 主动请求会一直吊着 GPS 耗电，而且大概率还是拿不到。
     */
    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Pair<Double, Double>? {
        if (!settings.recordGps) return null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstNotNullOfOrNull { lm.getLastKnownLocation(it) }
            loc?.let { it.latitude to it.longitude }
        }.getOrNull()
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
        val gps = lastKnownLocation()

        val meta = ImageMeta(
            imageId = UUID.randomUUID().toString(),
            capturedAt = now,
            workOrder = workOrder,
            serialNo = serialNo,
            modelName = activeModel?.name ?: "",
            platformName = platformOption?.label ?: "",
            stepOrder = step?.order ?: 0,
            stepName = step?.name ?: "",
            stepRefDes = step?.refDes ?: "",
            anchor = anchor.name,
            topEdge = (if (topEdge == TopEdge.AUTO) effectiveEdge else topEdge).name,
            latitude = gps?.first,
            longitude = gps?.second,
            hasWatermark = settings.showSopOnPhoto,
        )

        queueDepth += 1
        imageCapture.shoot(pipeline) { bytes ->
            PendingShot(
                jpeg = bytes,
                fileName = FileNaming.build(step, taken + 1, now),
                relativePath = FileNaming.relativePath(workOrder, serialNo, now),
                content = WatermarkContent(watermarkHeadline(), watermarkLines(now)),
                anchor = anchor,
                meta = meta,
                burnWatermark = settings.showSopOnPhoto,
                keepOriginal = settings.keepOriginal,
            )
        }

        if (step != null) {
            val next = taken + 1
            shotCounts = shotCounts + (step.order to next)
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

