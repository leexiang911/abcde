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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sopcam.capture.CameraBinder
import com.sopcam.capture.CapturePipeline
import com.sopcam.capture.Optics
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
import androidx.camera.core.Camera
import com.sopcam.ui.CameraScreen
import com.sopcam.ui.FlashMode
import com.sopcam.ui.FocusSpot
import com.sopcam.ui.FocusStatus
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
    private var camera: Camera? = null
    private var zoomRatio by mutableFloatStateOf(1f)
    private var minZoom by mutableFloatStateOf(1f)
    private var maxZoom by mutableFloatStateOf(1f)
    private var flashMode by mutableStateOf(FlashMode.OFF)
    private var exposureIndex by mutableIntStateOf(0)
    private var exposureRange by mutableStateOf(0..0)
    private var evPerStep by mutableFloatStateOf(0f)
    private var previewView: PreviewView? = null
    private var focusSpot by mutableStateOf<FocusSpot?>(null)
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
                    watermarkVisible = settings.watermarkVisible,
                    edge = topEdge,
                    effectiveEdge = effectiveEdge,
                    panel = panel,
                    zoomRatio = zoomRatio,
                    minZoom = minZoom,
                    maxZoom = maxZoom,
                    flashMode = flashMode,
                    exposureIndex = exposureIndex,
                    exposureRange = exposureRange,
                    evPerStep = evPerStep,
                    focusSpot = focusSpot,
                    watermarkHeadline = watermarkHeadline(),
                    watermarkLines = watermarkLines(System.currentTimeMillis()),
                    queueDepth = queueDepth,
                    lastSaved = lastSaved,
                    onStepSelect = { stepIndex = it },
                    onPanelChange = { panel = it },
                    onAnchorPick = {
                        anchor = it
                        // 选了角就等于把水印打开，不用先去开总开关
                        if (!settings.watermarkVisible) {
                            settings = settings.copy(watermarkVisible = true)
                            SettingsStore.save(this, settings)
                        }
                    },
                    onWatermarkDisable = {
                        settings = settings.copy(watermarkVisible = !settings.watermarkVisible)
                        SettingsStore.save(this, settings)
                    },
                    onEdgePick = {
                        topEdge = it
                        orientation.topEdge = it
                    },
                    onZoomPick = { r -> camera?.let { zoomRatio = Optics.setZoom(it, r) } },
                    onZoomPinch = { f ->
                        camera?.let { zoomRatio = Optics.setZoom(it, zoomRatio * f) }
                    },
                    onFlashToggle = {
                        flashMode = flashMode.next()
                        applyFlash()
                    },
                    onExposureChange = { i ->
                        camera?.let { exposureIndex = Optics.setExposure(it, i) }
                    },
                    onFocusTap = { x, y ->
                        // 短按：已经锁着就先解锁，再在新位置做一次单次对焦
                        focusAt(x, y, persistent = false)
                    },
                    onFocusLongStart = { x, y -> focusAt(x, y, persistent = true) },
                    onFocusLongEnd = { armed ->
                        // 拖到位才留着当持久锁，没拖到位就降级成单次
                        focusSpot = focusSpot?.copy(locked = armed)
                        if (!armed && focusSpot == null) Optics.cancelFocus(camera)
                    },
                    onShutter = ::capture,
                    onExit = {
                        persist()
                        camera?.cameraControl?.enableTorch(false)
                        releaseFocus()
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

    /**
     * 预览层和烧录层共用，保证所见即所得。
     * 画面上只留时间和当前步骤 —— 工单号、序列号、型号平台是给机器读的，
     * 盖在板子上只挡视线，它们全部只进元数据。
     */
    private fun watermarkHeadline(): String? =
        if (!settings.showSopStep) null
        else currentStep?.let { "${it.order.toString().padStart(2, '0')} · ${it.label()}" }

    private fun watermarkLines(at: Long): List<String> =
        if (settings.showTimeStamp) listOf(stampFmt.format(Date(at))) else emptyList()

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
        previewView = view
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(view.surfaceProvider)
        }
        lifecycleScope.launch {
            runCatching {
                CameraBinder.bind(this@MainActivity, this@MainActivity, preview, imageCapture)
            }.onSuccess { cam ->
                withContext(Dispatchers.Main) {
                    camera = cam
                    // 能力值全部问相机要。S25+ 的超广角让 minZoom 到 0.6，
                    // 写死 1.0 的话档位条上就不会出现 .6 这一档。
                    val (lo, hi) = Optics.zoomRange(cam)
                    minZoom = lo
                    maxZoom = hi
                    zoomRatio = Optics.currentZoom(cam)
                    exposureRange = Optics.exposureRange(cam)
                    evPerStep = Optics.evPerStep(cam)
                    exposureIndex = Optics.setExposure(cam, exposureIndex)
                    applyFlash()
                }
            }.onFailure {
                withContext(Dispatchers.Main) { lastSaved = "相机启动失败：${it.message}" }
            }
        }
    }

    /**
     * 触发对焦。
     *
     * 坐标换算交给 PreviewView 自带的 meteringPointFactory ——
     * 它知道取景框的裁切和缩放方式，手算屏幕坐标转相机坐标很容易差一截。
     */
    private fun focusAt(x: Float, y: Float, persistent: Boolean) {
        val cam = camera ?: return
        val pv = previewView ?: return

        focusSpot = FocusSpot(x, y, FocusStatus.FOCUSING, persistent)
        val point = pv.meteringPointFactory.createPoint(x, y)

        Optics.startFocus(cam, point, ContextCompat.getMainExecutor(this)) { ok ->
            focusSpot = focusSpot?.copy(
                status = if (ok) FocusStatus.OK else FocusStatus.FAILED
            )
        }
    }

    private fun releaseFocus() {
        Optics.cancelFocus(camera)
        focusSpot = null
    }

    private fun applyFlash() {
        Optics.applyFlash(
            camera = camera,
            imageCapture = imageCapture,
            torch = flashMode == FlashMode.TORCH,
            fireOnShot = flashMode == FlashMode.ON,
        )
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
            hasWatermark = settings.burnsAnything,
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
                burnWatermark = settings.burnsAnything,
                keepOriginal = settings.keepOriginal,
            )
        }

        // 单次对焦到此为止，持久锁留给下一张
        if (focusSpot?.locked == false) releaseFocus()

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
        camera?.cameraControl?.enableTorch(false)
        orientation.disable()
        persist()
        super.onStop()
    }

    override fun onDestroy() {
        pipeline.shutdown()
        super.onDestroy()
    }
}

