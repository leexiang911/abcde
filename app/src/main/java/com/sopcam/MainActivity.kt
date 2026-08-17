package com.sopcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import com.sopcam.archive.Archive
import com.sopcam.archive.Exporter
import com.sopcam.crash.CrashLogger
import com.sopcam.capture.CapturePipeline
import com.sopcam.capture.CodeAnalyzer
import com.sopcam.capture.ShutterFeedback
import com.sopcam.capture.ScannedCode
import com.sopcam.capture.Optics
import com.sopcam.capture.PendingShot
import com.sopcam.capture.shoot
import com.sopcam.meta.ImageMeta
import com.sopcam.sop.AppSettings
import com.sopcam.sop.Catalog
import com.sopcam.sop.FaultType
import com.sopcam.sop.Faults
import com.sopcam.sop.ControllerModel
import com.sopcam.sop.FileNaming
import com.sopcam.sop.Session
import com.sopcam.sop.SettingsStore
import com.sopcam.sop.SopStep
import com.sopcam.sop.SopStore
import com.sopcam.sop.SopTemplate
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import com.sopcam.ui.CameraScreen
import com.sopcam.ui.ProjectsScreen
import com.sopcam.ui.CrashScreen
import com.sopcam.ui.ScanScreen
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

private enum class Screen { SETUP, TEMPLATE_EDIT, SETTINGS, CAMERA, SCAN, PROJECTS }

/** 开工页上弹出的哪个选择器 */
private enum class Sheet { NONE, MODEL, PLATFORM, FAULT, TEMPLATE }

class MainActivity : ComponentActivity() {

    private lateinit var pipeline: CapturePipeline
    private lateinit var imageCapture: ImageCapture
    private lateinit var orientation: OrientationController
    private lateinit var feedback: ShutterFeedback

    private var crashTrace by mutableStateOf<String?>(null)
    private var hasPermission by mutableStateOf(false)
    private var screen by mutableStateOf(Screen.SETUP)
    private var sheet by mutableStateOf(Sheet.NONE)

    private val templates = mutableStateListOf<SopTemplate>()
    private var catalog by mutableStateOf<List<ControllerModel>>(emptyList())
    private var settings by mutableStateOf(AppSettings())

    private var serialNo by mutableStateOf("")
    private var faultId by mutableStateOf("")
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
    private var focusNote by mutableStateOf<String?>(null)
    private var afSupported by mutableStateOf(false)
    private var archiveReady by mutableStateOf(false)
    private var projects by mutableStateOf<List<Archive.Project>>(emptyList())
    private var picked by mutableStateOf<Set<String>>(emptySet())
    private var exporting by mutableStateOf<String?>(null)
    private lateinit var analysis: ImageAnalysis
    private var scannedCode by mutableStateOf<ScannedCode?>(null)
    private var faults by mutableStateOf<List<FaultType>>(emptyList())
    private var queueDepth by mutableIntStateOf(0)
    private var lastSaved by mutableStateOf<String?>(null)

    /** 水印上的时间只到分钟 —— 秒对留档没意义，还占宽度 */
    private val stampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    private val activeFault: FaultType?
        get() = faults.firstOrNull { it.id == faultId }

    private val faultOption: PickOption?
        get() = activeFault?.let { PickOption(it.id, it.name) }

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

        // 越早装越好 —— 装之前发生的崩溃是抓不到的
        CrashLogger.install(this)
        crashTrace = CrashLogger.pending(this)

        hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        templates.addAll(SopStore.loadTemplates(this))
        catalog = Catalog.load(this)
        faults = Faults.load(this)
        settings = SettingsStore.load(this)
        SopStore.loadSession(this).let { s ->
            serialNo = s.serialNo
            modelId = s.modelId
            platformId = s.platformId
            faultId = s.faultId
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
        analysis = CodeAnalyzer.buildUseCase()
        feedback = ShutterFeedback(this)
        orientation = OrientationController(this) { rot ->
            imageCapture.targetRotation = rot
            effectiveEdge = TopEdge.of(rot)
        }

        setContent {
            // 崩溃现场优先于一切 —— 没有调试器，这一屏就是唯一的线索
            crashTrace?.let { trace ->
                CrashScreen(trace) {
                    CrashLogger.clear(this)
                    crashTrace = null
                }
                return@setContent
            }
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
                        onProjects = {
                            projects = Archive.list()
                            picked = emptySet()
                            screen = Screen.PROJECTS
                        },
                        onScanSerial = {
                            scannedCode = null
                            screen = Screen.SCAN
                        },
                        faultOption = faultOption,
                        onFaultTap = { sheet = Sheet.FAULT },
                        serialNo = serialNo,
                        templates = templates,
                        activeTemplateId = templateId,
                        onSerialChange = { serialNo = it },
                        templateOption = templates.firstOrNull { it.id == templateId }
                            ?.let { PickOption(it.id, it.name, "${it.steps.size} 个拍摄点位") },
                        activeTemplate = templates.firstOrNull { it.id == templateId },
                        onTemplateTap = { sheet = Sheet.TEMPLATE },
                        onStartFreeform = {
                            templateId = ""
                            stepIndex = 0
                            shotCounts = emptyMap()
                            persist()
                            screen = Screen.CAMERA
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
                        Sheet.FAULT -> PickerSheet(
                            title = "故障类型",
                            options = faults.map { PickOption(it.id, it.name) },
                            selectedId = faultId,
                            onPick = { opt ->
                                faultId = opt?.id ?: ""
                                sheet = Sheet.NONE
                            },
                            onDismiss = { sheet = Sheet.NONE }
                        )
                        Sheet.TEMPLATE -> PickerSheet(
                            title = "检修流程",
                            options = templates.map {
                                PickOption(it.id, it.name, "${it.steps.size} 个拍摄点位")
                            },
                            selectedId = templateId,
                            onPick = { opt ->
                                val id = opt?.id ?: ""
                                if (templateId != id) {
                                    templateId = id
                                    stepIndex = 0
                                    shotCounts = emptyMap()
                                }
                                sheet = Sheet.NONE
                            },
                            onDismiss = { sheet = Sheet.NONE }
                        )
                        Sheet.NONE -> Unit
                    }
                }

                Screen.SCAN -> ScanScreen(
                    title = "扫控制器序列号",
                    hint = "把板子上的码对进框里，识别到就自动回填",
                    lastCode = scannedCode?.value,
                    bindPreview = ::bindScanner,
                    onCancel = {
                        scannedCode = null
                        screen = Screen.SETUP
                    }
                )

                Screen.PROJECTS -> ProjectsScreen(
                    projects = projects,
                    selected = picked,
                    exporting = exporting,
                    onToggle = { sn ->
                        picked = if (sn in picked) picked - sn else picked + sn
                    },
                    onSelectAll = {
                        picked = if (picked.size == projects.size) emptySet()
                        else projects.map { it.serialNo }.toSet()
                    },
                    onExport = ::runExport,
                    onBack = {
                        picked = emptySet()
                        screen = Screen.SETUP
                    }
                )

                Screen.SETTINGS -> SettingsScreen(
                    settings = settings,
                    archiveReady = archiveReady,
                    onGrantArchive = ::openArchivePermission,
                    onChange = {
                        settings = it
                        SettingsStore.save(this, it)
                        applyScanner()
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
                    focusNote = focusNote,
                    scannedCode = scannedCode?.value,
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
                    onZoomPick = { r ->
                        // 换倍率可能换物理镜头，原来锁的焦点没意义了；
                        // 顺带把可能残留的 3A 锁放掉，免得挡住 setZoomRatio
                        releaseFocus()
                        camera?.let { zoomRatio = Optics.setZoom(it, r) }
                    },
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
                    onFocusTap = { x, y -> focusAt(x, y, persistent = false) },
                    onFocusCancel = ::releaseFocus,
                    onCodeClear = { scannedCode = null },
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

    /**
     * 去系统设置开「所有文件访问权限」。
     *
     * 这个权限没法用普通的权限弹窗要，只能跳系统页面让用户自己开。
     */
    /**
     * 打包在 IO 线程跑：几十兆的 zip 在主线程会把界面卡死。
     * 打完直接拉起分享面板，用户接着就能发出去。
     */
    private fun runExport(opt: Exporter.Options) {
        val serials = picked.toList()
        if (serials.isEmpty() || !opt.any) return
        exporting = "准备中…"
        lifecycleScope.launch(Dispatchers.IO) {
            val zip = Exporter.export(serials, opt) { done, total ->
                lifecycleScope.launch(Dispatchers.Main) { exporting = "打包中 $done / $total" }
            }
            withContext(Dispatchers.Main) {
                exporting = null
                if (zip == null) {
                    lastSaved = "导出失败"
                } else {
                    picked = emptySet()
                    Exporter.share(this@MainActivity, zip)
                }
            }
        }
    }

    private fun openArchivePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
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
            Session(serialNo, modelId, platformId, faultId, templateId, stepIndex, shotCounts)
        )
    }

    private fun bindPreview(view: PreviewView) {
        previewView = view
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(view.surfaceProvider)
        }
        lifecycleScope.launch {
            runCatching {
                CameraBinder.bind(
                    this@MainActivity, this@MainActivity, preview, imageCapture, analysis
                )
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
                    afSupported = previewView?.let {
                        Optics.isFocusSupported(cam, it.meteringPointFactory.createPoint(0.5f, 0.5f))
                    } ?: false
                    applyFlash()
                    applyScanner()
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

        val point = pv.meteringPointFactory.createPoint(x, y)
        if (!Optics.isFocusSupported(cam, point)) {
            focusNote = "这颗镜头不支持点按对焦"
            return
        }

        focusSpot = FocusSpot(x, y, FocusStatus.FOCUSING, persistent)
        focusNote = null

        Optics.startFocus(cam, point, ContextCompat.getMainExecutor(this)) { ok ->
            focusSpot = focusSpot?.copy(
                status = if (ok) FocusStatus.OK else FocusStatus.FAILED
            )
            // 超广角没有对焦马达，怎么点都是失败。
            // 直说是硬件限制，免得用户以为是自己没点准，反复戳。
            focusNote = when {
                ok -> null
                zoomRatio < 1f -> "超广角是定焦镜头，靠近拍请切回 1×"
                else -> "对焦失败，换个有反差的位置试试"
            }
        }
    }

    private fun releaseFocus() {
        Optics.cancelFocus(camera)
        focusSpot = null
        focusNote = null
    }

    /**
     * 取景扫码开关。
     *
     * 关掉时只摘掉 analyzer，不重新绑定用例 —— 重新 bind 会让预览闪一下，
     * 而 ML Kit 的识别才是耗电大头，摘掉它就够了。
     */
    private fun applyScanner() {
        if (settings.scanInViewfinder) {
            analysis.setAnalyzer(pipeline.captureExecutor, CodeAnalyzer { code ->
                // 扫到就记住，不因为码移出画面而清空 ——
                // 否则对好码再挪一下构图，按快门那一刻反而没值了
                if (code.value != scannedCode?.value) {
                    lifecycleScope.launch(Dispatchers.Main) { scannedCode = code }
                }
            })
        } else {
            analysis.clearAnalyzer()
            scannedCode = null
        }
    }

    /** 开工页的扫码界面：只绑预览和分析，不要 ImageCapture */
    private fun bindScanner(view: PreviewView) {
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(view.surfaceProvider)
        }
        analysis.setAnalyzer(pipeline.captureExecutor, CodeAnalyzer { code ->
            lifecycleScope.launch(Dispatchers.Main) {
                scannedCode = code
                serialNo = code.value
                screen = Screen.SETUP
                scannedCode = null
            }
        })
        lifecycleScope.launch {
            runCatching {
                CameraBinder.bind(this@MainActivity, this@MainActivity, preview, null, analysis)
            }.onFailure {
                withContext(Dispatchers.Main) { lastSaved = "扫码启动失败：${it.message}" }
            }
        }
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
            serialNo = serialNo,
            modelName = activeModel?.name ?: "",
            platformName = platformOption?.label ?: "",
            faultType = activeFault?.name ?: "",
            stepOrder = step?.order ?: 0,
            stepName = step?.name ?: "",
            stepRefDes = step?.refDes ?: "",
            codeValue = scannedCode?.value ?: "",
            codeFormat = scannedCode?.format ?: "",
            anchor = anchor.name,
            topEdge = (if (topEdge == TopEdge.AUTO) effectiveEdge else topEdge).name,
            latitude = gps?.first,
            longitude = gps?.second,
            hasWatermark = settings.burnsAnything,
        )

        // 这些必须在 shoot 之前快照。
        // shoot 的 lambda 要等照片真出来才跑，而下面的 stepIndex += 1 已经在主线程执行完了 ——
        // 到那时 watermarkHeadline() 读到的是下一步，水印就比实际步骤快一格。
        val shotContent = WatermarkContent(watermarkHeadline(), watermarkLines(now))
        val shotAnchor = anchor
        val shotName = FileNaming.build(step, taken + 1, now)
        val shotPath = FileNaming.relativePath(serialNo, now)
        val shotBurn = settings.burnsAnything
        val shotKeepRaw = settings.keepOriginal

        feedback.fire(settings.shutterVibrate, settings.shutterSound)
        Archive.touchProject(
            serialNo, activeModel?.name ?: "", platformOption?.label ?: "", activeFault?.name ?: ""
        )

        queueDepth += 1
        imageCapture.shoot(pipeline) { bytes ->
            PendingShot(
                jpeg = bytes,
                fileName = shotName,
                relativePath = shotPath,
                content = shotContent,
                anchor = shotAnchor,
                meta = meta,
                burnWatermark = shotBurn,
                keepOriginal = shotKeepRaw,
                headline = shotContent.headline,
                lines = shotContent.lines,
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
        // 从系统设置页回来时刷新一下，用户可能刚把权限打开
        archiveReady = Archive.canWrite()
        orientation.enable()
    }

    override fun onStop() {
        camera?.cameraControl?.enableTorch(false)
        orientation.disable()
        persist()
        super.onStop()
    }

    override fun onDestroy() {
        feedback.release()
        pipeline.shutdown()
        super.onDestroy()
    }
}

