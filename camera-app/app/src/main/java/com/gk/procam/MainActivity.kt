package com.gk.procam

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ExifInterface
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class MainActivity : AppCompatActivity() {

    private enum class Mode { PHOTO, VIDEO, PRO }

    /** A rear lens: its field of view relative to the main lens (0.5 = ultrawide, 3 = tele). */
    private class Lens(val ratio: Float, val selector: CameraSelector)

    private class Filter(
        val name: String, val sat: Float, val contrast: Float, val warmth: Float,
        val bright: Float = 0f, val fade: Float = 0f
    )

    // ---- views ----
    private lateinit var root: View
    private lateinit var previewView: PreviewView
    private lateinit var gridView: GridOverlayView
    private lateinit var focusRing: View
    private lateinit var flashOverlay: View
    private lateinit var countdownText: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var recTime: TextView
    private lateinit var infoText: TextView
    private lateinit var proPanel: LinearLayout
    private lateinit var filterScroll: View
    private lateinit var filterRow: LinearLayout
    private lateinit var evRow: View
    private lateinit var evSeek: SeekBar
    private lateinit var evValue: TextView
    private lateinit var zoomRow: LinearLayout
    private lateinit var zoomDial: ZoomDial
    private lateinit var modeRow: LinearLayout
    private lateinit var thumb: ImageView
    private lateinit var pauseBtn: TextView
    private lateinit var shutter: ShutterButton
    private lateinit var switchCam: ImageView

    // ---- persisted settings ----
    private lateinit var prefs: SharedPreferences

    private fun pref(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = prefs.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            prefs.edit().putInt(key, value).apply()
        }
    }

    private var modeIdx by pref("mode", 0)
    private var flash by pref("flash", 0)          // 0 off, 1 auto, 2 on
    private var timer by pref("timer", 0)          // seconds
    private var grid by pref("grid", 0)
    private var ratio by pref("ratio", 0)          // 0 = 4:3, 1 = 16:9
    private var hiRes by pref("hires", 0)          // full-sensor 50MP readout
    private var process by pref("process", 0)      // -1 standard, 0 Ultra HDR, else ExtensionMode
    private var raw by pref("raw", 0)
    private var vq by pref("vq", 0)                // 0 4K, 1 FHD, 2 HD
    private var fps by pref("fps", 30)
    private var hdrVideo by pref("hdrVideo", 0)
    private var stab by pref("stab", 1)
    private var mic by pref("mic", 1)
    private var front by pref("front", 0)
    private var filterIdx by pref("filter", 0)
    private var sharp by pref("sharp", 0)          // DETAIL+ sharpening of saved photos
    private var mirror by pref("mirror", 1)        // save selfies as seen in the preview
    private var nightVideo by pref("nightVideo", 0)

    private val mode: Mode get() = Mode.values()[modeIdx.coerceIn(0, 2)]
    private val filter: Filter get() = FILTERS[filterIdx.coerceIn(0, FILTERS.size - 1)]

    // ---- camera state ----
    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordingPaused = false
    private var torch = false
    private var fillLight = false

    // rear lenses
    private var backCams: List<CameraInfo> = emptyList()
    private var lenses: List<Lens> = emptyList()
    private var mainLens = 0
    private var lensIdx = 0
    private var mainMin = 1f
    private var mainMax = 10f
    private var mainLogical = false
    private var pendingZoom: Float? = null

    // capabilities of the currently selected camera
    private var ultraHdrOk = false
    private var rawOk = false
    private var exts: List<Int> = emptyList()
    private var hlgOk = false
    private var fps60Ok = false
    private var rawActive = false
    private var nightTuning = false
    private var sceneNightOk = false
    private var statusLabel = ""

    // zoom of the bound camera
    private var curZoom = 1f
    private var minZoom = 1f
    private var maxZoom = 1f
    private var zoomChips: List<Float> = emptyList()
    private var zoomAnim: ValueAnimator? = null

    // pro controls
    private var manualExp = false
    private var manualFocus = false
    private var wbIdx = 0
    private var isoP = 300
    private var shutterP = 500
    private var focusP = 0
    private var isoRange: Range<Int>? = null
    private var expRange: Range<Long>? = null
    private var minFocusDist = 0f

    private var curRotation = Surface.ROTATION_0
    private var lastUri: Uri? = null
    private var lastMime = "image/jpeg"
    private var countdownLeft = 0

    private val handler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val proc: ExecutorService = Executors.newSingleThreadExecutor()
    private val sound = MediaActionSound()
    private lateinit var orientationListener: OrientationEventListener

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPermission(Manifest.permission.CAMERA)) startCamera()
            else toast("Camera permission ke bina app nahi chalegi")
        }

    // ------------------------------------------------------------------ lifecycle

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("gkcamera", MODE_PRIVATE)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        previewView = findViewById(R.id.preview)
        gridView = findViewById(R.id.grid)
        focusRing = findViewById(R.id.focusRing)
        flashOverlay = findViewById(R.id.flashOverlay)
        countdownText = findViewById(R.id.countdown)
        chipRow = findViewById(R.id.chipRow)
        recTime = findViewById(R.id.recTime)
        infoText = findViewById(R.id.infoText)
        proPanel = findViewById(R.id.proPanel)
        filterScroll = findViewById(R.id.filterScroll)
        filterRow = findViewById(R.id.filterRow)
        evRow = findViewById(R.id.evRow)
        evSeek = findViewById(R.id.evSeek)
        evValue = findViewById(R.id.evValue)
        zoomRow = findViewById(R.id.zoomRow)
        zoomDial = findViewById(R.id.zoomDial)
        modeRow = findViewById(R.id.modeRow)
        thumb = findViewById(R.id.thumb)
        pauseBtn = findViewById(R.id.pauseBtn)
        shutter = findViewById(R.id.shutter)
        switchCam = findViewById(R.id.switchCam)
        thumb.clipToOutline = true
        previewView.setScreenFlashWindow(window)

        // Full screen, with the controls kept clear of the cutout and navigation bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, root).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        shutter.setOnClickListener { onShutter() }
        switchCam.setOnClickListener {
            if (recording != null) return@setOnClickListener
            front = 1 - front
            lensIdx = mainLens
            pendingZoom = 1f
            bind()
        }
        thumb.setOnClickListener { openLast() }
        pauseBtn.setOnClickListener { togglePause() }

        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    zoomAnim?.cancel()
                    setZoom(curZoom * detector.scaleFactor)
                    return true
                }
            })
        val tapDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    focusAt(e.x, e.y)
                    return true
                }
            })
        previewView.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            if (!scaleDetector.isInProgress) tapDetector.onTouchEvent(ev)
            true
        }

        evSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val cam = camera ?: return
                val st = cam.cameraInfo.exposureState
                val idx = st.exposureCompensationRange.lower + progress
                cam.cameraControl.setExposureCompensationIndex(idx)
                evValue.text = String.format(Locale.US, "%+.1f", idx * st.exposureCompensationStep.toFloat())
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Round zoom dial: drag it left / right with the thumb. It works in "main lens"
        // units, so 0.5 / 1 / 3 on the dial match the lens buttons.
        zoomDial.onChange = { eff ->
            zoomAnim?.cancel()
            setZoom(eff / lensScale())
        }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val rot = when (o) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (rot != curRotation) {
                    curRotation = rot
                    imageCapture?.targetRotation = rot
                    if (recording == null) videoCapture?.targetRotation = rot
                }
            }
        }

        renderModes()
        renderChips()
        renderFilters()

        val wanted = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (hasPermission(Manifest.permission.CAMERA)) startCamera()
        else permissionLauncher.launch(wanted)
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()
        cancelCountdown()
        recording?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
        proc.shutdown()
        sound.release()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.repeatCount == 0) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ camera setup

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = future.get()
            provider = p
            backCams = p.availableCameraInfos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            discoverLenses()
            val extFuture = ExtensionsManager.getInstanceAsync(this, p)
            extFuture.addListener({
                extensions = try { extFuture.get() } catch (e: Exception) { null }
                bind()
            }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    /** 35mm-equivalent focal length of a camera, used to tell ultrawide / main / tele apart. */
    private fun equivFocal(info: CameraInfo): Float? = try {
        val c2 = Camera2CameraInfo.from(info)
        val f = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()
        val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (f == null || size == null) null else f * 43.27f / hypot(size.width, size.height)
    } catch (e: Exception) {
        null
    }

    /**
     * Finds every rear lens the phone exposes: separate camera ids as well as the physical
     * cameras hidden behind the main (logical) camera.
     */
    private fun discoverLenses() {
        val found = ArrayList<Lens>()
        found.add(Lens(1f, CameraSelector.DEFAULT_BACK_CAMERA))
        val main = backCams.firstOrNull()
        val mainEq = if (main != null) equivFocal(main) else null
        if (main != null && mainEq != null) {
            fun consider(info: CameraInfo, selector: CameraSelector) {
                val eq = equivFocal(info) ?: return
                val r = eq / mainEq
                if (r < 0.2f || r > 15f) return
                if (found.none { abs(it.ratio / r - 1f) < 0.15f }) found.add(Lens(r, selector))
            }
            for (info in backCams.drop(1)) {
                try { consider(info, info.cameraSelector) } catch (e: Exception) { Log.w(TAG, "lens skipped", e) }
            }
            try {
                val physical = main.physicalCameraInfos
                mainLogical = physical.size > 1
                for (ph in physical) {
                    val id = Camera2CameraInfo.from(ph).cameraId
                    consider(
                        ph,
                        CameraSelector.Builder()
                            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                            .setPhysicalCameraId(id)
                            .build()
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "physical lenses not available", e)
            }
        }
        lenses = found.sortedBy { it.ratio }
        mainLens = lenses.indexOfFirst { it.ratio == 1f }.coerceAtLeast(0)
        lensIdx = mainLens
    }

    private fun baseSelector(): CameraSelector = when {
        front == 1 -> CameraSelector.DEFAULT_FRONT_CAMERA
        lensIdx in lenses.indices -> lenses[lensIdx].selector
        else -> CameraSelector.DEFAULT_BACK_CAMERA
    }

    /**
     * (Re)binds the use cases for the current mode and settings. If the device rejects the
     * requested combination it is retried with fewer extras: [level] 1 drops stabilisation,
     * 50MP readout and vendor extensions; level 2 also drops HDR, 60fps, RAW, video filters
     * and the lens choice.
     */
    private fun bind(level: Int = 0) {
        val p = provider ?: return
        if (!hasPermission(Manifest.permission.CAMERA)) return
        val lite = level >= 1
        val safe = level >= 2
        if (safe) lensIdx = mainLens
        if (pendingZoom == null && camera != null) pendingZoom = curZoom
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        zoomAnim?.cancel()
        try {
            p.unbindAll()
            imageCapture = null
            videoCapture = null
            torch = false
            setFillLight(false)

            var selector = baseSelector()
            if (!p.hasCamera(selector)) {
                front = 0
                lensIdx = mainLens
                selector = CameraSelector.DEFAULT_BACK_CAMERA
            }
            val info = p.getCameraInfo(selector)

            // What this lens can do.
            val formats = ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
            ultraHdrOk = formats.contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
            rawOk = formats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
            val em = extensions
            exts = if (em == null) emptyList() else listOf(
                ExtensionMode.HDR, ExtensionMode.NIGHT, ExtensionMode.BOKEH, ExtensionMode.AUTO
            ).filter { em.isExtensionAvailable(selector, it) }
            val videoCaps = Recorder.getVideoCapabilities(info)
            hlgOk = videoCaps.supportedDynamicRanges.contains(DynamicRange.HLG_10_BIT)
            val fpsRanges = info.supportedFrameRateRanges
            fps60Ok = fpsRanges.any { it.lower >= 60 && it.upper >= 60 }
            // Lets auto-exposure use slower shutter speeds, which is what brightens dark scenes.
            val slowRange = fpsRanges.filter { it.upper == 30 && it.lower < 30 }.minByOrNull { it.lower }
            sceneNightOk = try {
                Camera2CameraInfo.from(info)
                    .getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                    ?.contains(CameraMetadata.CONTROL_SCENE_MODE_NIGHT) == true
            } catch (e: Exception) {
                false
            }

            val wide = mode == Mode.VIDEO || ratio == 1
            val aspectStrategy =
                if (wide) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            gridView.aspect = if (wide) 16f / 9f else 4f / 3f

            val previewBuilder = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(aspectStrategy).build()
                )
            val useCases = ArrayList<UseCase>()
            val f = filter
            val filtered = filterIdx > 0
            var livePaint = false
            nightTuning = false

            if (mode == Mode.VIDEO) {
                val useFilter = false // video filters are not wired up yet
                val dr = if (hdrVideo == 1 && hlgOk && !safe && !useFilter) DynamicRange.HLG_10_BIT
                else DynamicRange.SDR
                val wantedQ = listOf(Quality.UHD, Quality.FHD, Quality.HD)[vq.coerceIn(0, 2)]
                val night = nightVideo == 1 && !safe
                val useFps = if (fps == 60 && fps60Ok && !safe && !night) 60 else 30
                var bitrate = when (wantedQ) {
                    Quality.UHD -> 60_000_000
                    Quality.FHD -> 24_000_000
                    else -> 12_000_000
                }
                if (useFps == 60) bitrate = bitrate * 3 / 2
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(wantedQ, FallbackStrategy.higherQualityOrLowerThan(wantedQ))
                    )
                    .setTargetVideoEncodingBitRate(bitrate)
                    .build()
                val vb = VideoCapture.Builder(recorder)
                    .setDynamicRange(dr)
                    .setTargetFrameRate(
                        if (night && slowRange != null) slowRange else Range(useFps, useFps)
                    )
                    .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                    .setTargetRotation(curRotation)
                var steady = false
                if (stab == 1 && !lite) {
                    if (Preview.getPreviewCapabilities(info).isStabilizationSupported) {
                        previewBuilder.setPreviewStabilizationEnabled(true)
                        steady = true
                    } else if (videoCaps.isStabilizationSupported) {
                        vb.setVideoStabilizationEnabled(true)
                        steady = true
                    }
                }
                val vc = vb.build()
                videoCapture = vc
                useCases.add(vc)
                nightTuning = night
                statusLabel = listOfNotNull(
                    arrayOf("4K", "1080p", "720p")[vq.coerceIn(0, 2)],
                    "${useFps}fps",
                    if (dr == DynamicRange.HLG_10_BIT) "HDR10-bit" else null,
                    if (steady) "Steady" else null,
                    if (night) "Night" else null,
                    if (useFilter) f.name else null
                ).joinToString(" · ")
                rawActive = false
            } else {
                // Vendor processing (HDR / Night / Portrait) via camera extensions, photo mode only.
                var activeExt = ExtensionMode.NONE
                if (mode == Mode.PHOTO && !lite && em != null && process > 0 && exts.contains(process)) {
                    selector = em.getExtensionEnabledCameraSelector(selector, process)
                    activeExt = process
                }
                // Night picked but the phone offers no night extension here: tune exposure ourselves.
                nightTuning = mode == Mode.PHOTO && process == ExtensionMode.NIGHT &&
                    activeExt == ExtensionMode.NONE && !safe
                if (nightTuning && slowRange != null) previewBuilder.setTargetFrameRate(slowRange)

                rawActive = mode == Mode.PRO && raw == 1 && rawOk && !safe
                val ultra = !rawActive && !safe && activeExt == ExtensionMode.NONE &&
                    ultraHdrOk && (process == 0 || mode == Mode.PRO)
                val full = hiRes == 1 && !lite && activeExt == ExtensionMode.NONE

                // Default to the binned ~12MP readout: it gathers far more light per pixel than
                // the full 50MP mode, so it is sharper and cleaner indoors (selfie camera too).
                val rs = ResolutionSelector.Builder().setAspectRatioStrategy(aspectStrategy)
                if (full) {
                    rs.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    rs.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                } else {
                    rs.setResolutionStrategy(
                        ResolutionStrategy(
                            Size(4096, 3072),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                }

                val ib = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
                    .setTargetRotation(curRotation)
                    .setResolutionSelector(rs.build())
                val screenFlash = previewView.screenFlash
                if (front == 1 && flash != 0 && screenFlash != null) {
                    ib.setScreenFlash(screenFlash)
                    ib.setFlashMode(ImageCapture.FLASH_MODE_SCREEN)
                } else {
                    ib.setFlashMode(flashConst())
                }
                when {
                    rawActive -> ib.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                    ultra -> ib.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                }
                val ic = ib.build()
                imageCapture = ic
                useCases.add(ic)
                livePaint = filtered
                statusLabel = listOfNotNull(
                    when {
                        rawActive -> "RAW + JPEG"
                        activeExt != ExtensionMode.NONE -> processLabel(activeExt)
                        nightTuning -> "Night"
                        ultra -> "Ultra HDR"
                        else -> "Standard"
                    },
                    if (filtered) f.name else null,
                    if (sharp == 1) "Detail+" else null
                ).joinToString(" · ")
            }

            // Live filter preview for photos: tint the preview view itself. That needs the
            // TextureView-based preview; without a filter the faster SurfaceView is used.
            previewView.implementationMode =
                if (livePaint) PreviewView.ImplementationMode.COMPATIBLE
                else PreviewView.ImplementationMode.PERFORMANCE
            applyPreviewPaint(livePaint)

            val preview = previewBuilder.build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            useCases.add(0, preview)

            val cam = p.bindToLifecycle(this, selector, *useCases.toTypedArray())
            camera = cam
            onBound(cam)
        } catch (e: Exception) {
            Log.e(TAG, "bind failed (level=$level)", e)
            if (!safe) {
                if (lite) toast("Yeh combination is lens par support nahi hai — safe settings use ho rahi hain")
                bind(level + 1)
            } else {
                toast("Camera start nahi hua: ${e.message}")
            }
        }
    }

    private fun onBound(cam: Camera) {
        val onMain = front == 0 && lensIdx == mainLens
        cam.cameraInfo.zoomState.observe(this) { z ->
            curZoom = z.zoomRatio
            minZoom = z.minZoomRatio
            maxZoom = z.maxZoomRatio
            if (onMain) {
                mainMin = minZoom
                mainMax = maxZoom
            }
            renderZoom()
        }
        val want = pendingZoom
        pendingZoom = null
        if (want != null && abs(want - 1f) > 0.01f) {
            val z = cam.cameraInfo.zoomState.value
            val lo = z?.minZoomRatio ?: 1f
            val hi = z?.maxZoomRatio ?: 1f
            cam.cameraControl.setZoomRatio(want.coerceIn(lo, hi.coerceAtLeast(lo)))
        }

        val st = cam.cameraInfo.exposureState
        val r = st.exposureCompensationRange
        evSeek.max = (r.upper - r.lower).coerceAtLeast(0)
        evSeek.progress = st.exposureCompensationIndex - r.lower
        evValue.text = String.format(
            Locale.US, "%+.1f", st.exposureCompensationIndex * st.exposureCompensationStep.toFloat()
        )

        try {
            val c2 = Camera2CameraInfo.from(cam.cameraInfo)
            isoRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            expRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            minFocusDist = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        } catch (e: Exception) {
            isoRange = null
            expRange = null
            minFocusDist = 0f
        }
        applyTuning()

        shutter.videoMode = mode == Mode.VIDEO
        gridView.show = grid == 1
        renderChips()
        renderPro()
        renderFilters()
        infoText.text = statusLabel
        handler.postDelayed({ updateInfo() }, 700)
    }

    private fun updateInfo() {
        val res = imageCapture?.resolutionInfo?.resolution
        infoText.text = if (res != null) {
            val mp = res.width.toLong() * res.height / 1_000_000f
            String.format(Locale.US, "%d×%d (%.0fMP) · %s", res.width, res.height, mp, statusLabel)
        } else statusLabel
    }

    private fun flashConst() = when (flash) {
        1 -> ImageCapture.FLASH_MODE_AUTO
        2 -> ImageCapture.FLASH_MODE_ON
        else -> ImageCapture.FLASH_MODE_OFF
    }

    /** Warm screen light for selfie video: bright cream border around a smaller preview. */
    private fun setFillLight(on: Boolean) {
        fillLight = on
        root.setBackgroundColor(if (on) 0xFFFFF1DC.toInt() else Color.BLACK)
        previewView.scaleX = if (on) 0.78f else 1f
        previewView.scaleY = if (on) 0.78f else 1f
        val lp = window.attributes
        lp.screenBrightness =
            if (on) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    // ------------------------------------------------------------------ photo

    private fun onShutter() {
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (mode == Mode.VIDEO) {
            if (recording != null) stopRecording() else startRecording()
            return
        }
        if (countdownLeft > 0) {
            cancelCountdown()
            return
        }
        if (timer > 0) startCountdown(timer) else takePhoto()
    }

    private fun startCountdown(seconds: Int) {
        countdownLeft = seconds
        countdownText.visibility = View.VISIBLE
        tick()
    }

    private fun tick() {
        if (countdownLeft <= 0) {
            countdownText.visibility = View.GONE
            takePhoto()
            return
        }
        countdownText.text = countdownLeft.toString()
        countdownLeft--
        handler.postDelayed(tickRunnable, 1000)
    }

    private val tickRunnable = Runnable { tick() }

    private fun cancelCountdown() {
        countdownLeft = 0
        handler.removeCallbacks(tickRunnable)
        if (::countdownText.isInitialized) countdownText.visibility = View.GONE
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val name = "IMG_" + stamp()
        val meta = ImageCapture.Metadata()
        meta.isReversedHorizontal = front == 1 && mirror == 1
        fun out(ext: String, mime: String): ImageCapture.OutputFileOptions {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.$ext")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/GKCamera")
            }
            return ImageCapture.OutputFileOptions.Builder(
                contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ).setMetadata(meta).build()
        }

        // Filter and sharpening are applied to the saved JPEG at full resolution.
        val matrix = if (filterIdx > 0) filterMatrix(filter) else null
        val sharpen = sharp == 1

        val callback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                val uri = results.savedUri ?: return
                val isJpeg = contentResolver.getType(uri) == "image/jpeg"
                if (isJpeg && (matrix != null || sharpen)) {
                    proc.execute {
                        try {
                            postProcess(uri, matrix, sharpen)
                        } catch (t: Throwable) {
                            Log.e(TAG, "post-process failed", t)
                            runOnUiThread { toast("Filter apply nahi hua — original photo save hai") }
                        }
                        runOnUiThread { showThumb(uri, "image/*") }
                    }
                } else if (isJpeg) {
                    runOnUiThread { showThumb(uri, "image/*") }
                }
            }
            override fun onError(e: ImageCaptureException) {
                Log.e(TAG, "capture failed", e)
                runOnUiThread { toast("Photo save nahi hui: ${e.message}") }
            }
        }

        flashOverlay.alpha = 0.7f
        flashOverlay.animate().alpha(0f).setDuration(220).start()
        sound.play(MediaActionSound.SHUTTER_CLICK)
        try {
            if (rawActive) {
                ic.takePicture(out("dng", "image/x-adobe-dng"), out("jpg", "image/jpeg"), io, callback)
            } else {
                ic.takePicture(out("jpg", "image/jpeg"), io, callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "takePicture threw", e)
            toast("Capture fail: ${e.message}")
        }
    }

    /**
     * Rewrites a saved JPEG in place with the colour [matrix] and/or a mild sharpen applied.
     * Works row by row so only one copy of the image is held in memory.
     */
    private fun postProcess(uri: Uri, matrix: FloatArray?, sharpen: Boolean) {
        val saved = HashMap<String, String>()
        contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            for (tag in EXIF_TAGS) exif.getAttribute(tag)?.let { saved[tag] = it }
        }
        val opts = BitmapFactory.Options()
        opts.inMutable = true
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return
        val w = bmp.width
        val h = bmp.height
        var prev = IntArray(w)
        var cur = IntArray(w)
        var next = IntArray(w)
        val out = IntArray(w)
        bmp.getPixels(cur, 0, w, 0, 0, w, 1)
        System.arraycopy(cur, 0, prev, 0, w)
        for (y in 0 until h) {
            if (y + 1 < h) bmp.getPixels(next, 0, w, 0, y + 1, w, 1)
            else System.arraycopy(cur, 0, next, 0, w)
            for (x in 0 until w) {
                val c = cur[x]
                var r = (c shr 16) and 0xFF
                var g = (c shr 8) and 0xFF
                var b = c and 0xFF
                if (sharpen) {
                    val l = cur[if (x > 0) x - 1 else 0]
                    val rt = cur[if (x < w - 1) x + 1 else x]
                    val u = prev[x]
                    val d = next[x]
                    // Only real edges are boosted, so sensor noise is left alone.
                    val dr = 4 * r - ((l shr 16) and 0xFF) - ((rt shr 16) and 0xFF) -
                        ((u shr 16) and 0xFF) - ((d shr 16) and 0xFF)
                    val dg = 4 * g - ((l shr 8) and 0xFF) - ((rt shr 8) and 0xFF) -
                        ((u shr 8) and 0xFF) - ((d shr 8) and 0xFF)
                    val db = 4 * b - (l and 0xFF) - (rt and 0xFF) - (u and 0xFF) - (d and 0xFF)
                    if (abs(dr) + abs(dg) + abs(db) > 18) {
                        r = clamp(r + dr * 70 / 256)
                        g = clamp(g + dg * 70 / 256)
                        b = clamp(b + db * 70 / 256)
                    }
                }
                if (matrix != null) {
                    val nr = matrix[0] * r + matrix[1] * g + matrix[2] * b + matrix[4]
                    val ng = matrix[5] * r + matrix[6] * g + matrix[7] * b + matrix[9]
                    val nb = matrix[10] * r + matrix[11] * g + matrix[12] * b + matrix[14]
                    r = clamp(nr.toInt())
                    g = clamp(ng.toInt())
                    b = clamp(nb.toInt())
                }
                out[x] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(out, 0, w, 0, y, w, 1)
            val t = prev
            prev = cur
            cur = next
            next = t
        }
        contentResolver.openOutputStream(uri, "wt")?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 97, it) }
        bmp.recycle()
        // Put orientation and shooting details back; re-encoding drops them.
        try {
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                for ((tag, value) in saved) exif.setAttribute(tag, value)
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "exif restore failed", e)
        }
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    // ------------------------------------------------------------------ filters

    private fun diag(rs: Float, gs: Float, bs: Float, off: Float) = ColorMatrix(
        floatArrayOf(
            rs, 0f, 0f, 0f, off,
            0f, gs, 0f, 0f, off,
            0f, 0f, bs, 0f, off,
            0f, 0f, 0f, 1f, 0f
        )
    )

    private fun filterMatrix(f: Filter): FloatArray {
        val cm = ColorMatrix()
        cm.setSaturation(f.sat)
        cm.postConcat(diag(f.contrast, f.contrast, f.contrast, 127.5f * (1f - f.contrast) + f.bright * 255f))
        if (f.warmth != 0f) cm.postConcat(diag(1f + f.warmth, 1f + f.warmth * 0.25f, 1f - f.warmth, 0f))
        if (f.fade > 0f) cm.postConcat(diag(1f - f.fade, 1f - f.fade, 1f - f.fade, f.fade * 255f))
        return cm.array
    }

    private fun applyPreviewPaint(on: Boolean) {
        if (on) {
            val paint = Paint()
            paint.colorFilter = ColorMatrixColorFilter(filterMatrix(filter))
            previewView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        } else {
            previewView.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private fun renderFilters() {
        filterRow.removeAllViews()
        for (i in FILTERS.indices) {
            chip(filterRow, FILTERS[i].name, i == filterIdx) {
                if (recording != null) {
                    toast("Recording ke dauraan filter nahi badal sakte")
                    return@chip
                }
                val was = filterIdx
                filterIdx = i
                // Between two photo filters only the tint changes; anything else needs a rebind.
                if (mode != Mode.VIDEO && was > 0 && i > 0) {
                    applyPreviewPaint(true)
                    renderFilters()
                    renderChips()
                } else {
                    bind()
                }
            }
        }
    }

    // ------------------------------------------------------------------ video

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_" + stamp() + ".mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/GKCamera")
        }
        val options = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = vc.output.prepareRecording(this, options)
        if (mic == 1 && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pending = pending.withAudioEnabled()
        }
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        recordingPaused = false
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> setRecordingUi(true)
                is VideoRecordEvent.Status -> {
                    val s = TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos)
                    recTime.text = String.format(
                        Locale.US, "%s %02d:%02d", if (recordingPaused) "II" else "●", s / 60, s % 60
                    )
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    setRecordingUi(false)
                    sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                    if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
                        toast("Video error (code ${event.error})")
                    }
                    val uri = event.outputResults.outputUri
                    if (uri != Uri.EMPTY) showThumb(uri, "video/*")
                }
            }
        }
    }

    private fun stopRecording() {
        recording?.stop()
    }

    private fun togglePause() {
        val rec = recording ?: return
        if (recordingPaused) rec.resume() else rec.pause()
        recordingPaused = !recordingPaused
        pauseBtn.text = if (recordingPaused) "▶" else "II"
    }

    private fun setRecordingUi(active: Boolean) {
        shutter.recording = active
        recTime.visibility = if (active) View.VISIBLE else View.GONE
        if (active) recTime.text = "● 00:00"
        val idle = if (active) View.INVISIBLE else View.VISIBLE
        findViewById<View>(R.id.chipScroll).visibility = idle
        modeRow.visibility = idle
        switchCam.visibility = idle
        if (active) filterScroll.visibility = View.GONE
        thumb.visibility = if (active) View.GONE else View.VISIBLE
        pauseBtn.visibility = if (active) View.VISIBLE else View.GONE
        pauseBtn.text = "II"
    }

    // ------------------------------------------------------------------ focus / zoom

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        try {
            cam.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            return
        }
        if (fillLight) return
        focusRing.animate().cancel()
        focusRing.x = previewView.x + x - focusRing.width / 2f
        focusRing.y = previewView.y + y - focusRing.height / 2f
        focusRing.visibility = View.VISIBLE
        focusRing.alpha = 1f
        focusRing.scaleX = 1.4f
        focusRing.scaleY = 1.4f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(900).setDuration(300).start()
        }.start()
    }

    /** Zoom on the lens that is currently bound (pinch). */
    private fun setZoom(value: Float) {
        camera?.cameraControl?.setZoomRatio(value.coerceIn(minZoom, maxZoom.coerceAtLeast(minZoom)))
    }

    private fun rearLens(): Boolean = front == 0 && lenses.isNotEmpty()

    /** Zoom relative to the main lens, whichever lens is active. */
    private fun effectiveZoom(): Float =
        if (rearLens() && lensIdx in lenses.indices) lenses[lensIdx].ratio * curZoom else curZoom

    private fun zoomTargets(): List<Float> {
        val all = ArrayList<Float>()
        if (rearLens()) {
            for (l in lenses) all.add(l.ratio)
            if (mainMin < 0.95f) all.add(mainMin)
            all.add(2f)
        } else {
            if (minZoom < 0.95f) all.add(minZoom)
            all.add(1f)
            if (maxZoom >= 2f) all.add(2f)
        }
        all.sort()
        val out = ArrayList<Float>()
        for (t in all) if (out.isEmpty() || t / out.last() > 1.15f) out.add(t)
        return out
    }

    /** Jumps to a zoom level, switching to the lens that reaches it optically when there is one. */
    private fun goZoom(target: Float) {
        if (!rearLens()) {
            animateZoomTo(target)
            return
        }
        val opticalBetween = lenses.any { it.ratio > 1.05f && it.ratio <= target + 0.02f }
        val useMain =
            if (target < 0.95f) mainMin <= target + 0.02f
            else target <= mainMax && (mainLogical || !opticalBetween)
        val idx = if (useMain) mainLens else
            lenses.indices.filter { lenses[it].ratio <= target + 0.02f }
                .maxByOrNull { lenses[it].ratio } ?: 0
        val z = target / lenses[idx].ratio
        if (idx == lensIdx) {
            animateZoomTo(z)
        } else if (recording != null) {
            toast("Recording ke dauraan lens nahi badal sakte")
        } else {
            lensIdx = idx
            pendingZoom = z
            bind()
        }
    }

    /** Glides to a zoom level instead of jumping, so tapping a zoom button does not jerk. */
    private fun animateZoomTo(value: Float) {
        zoomAnim?.cancel()
        val from = curZoom
        val to = value.coerceIn(minZoom, maxZoom.coerceAtLeast(minZoom))
        if (from <= 0f || abs(to - from) < 0.01f) {
            setZoom(to)
            return
        }
        val step = (to / from).toDouble()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 280
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener {
            val t = (it.animatedValue as Float).toDouble()
            camera?.cameraControl?.setZoomRatio((from * step.pow(t)).toFloat())
        }
        anim.start()
        zoomAnim = anim
    }

    private fun renderZoom() {
        val targets = zoomTargets()
        val eff = effectiveZoom()
        // The buttons are built once and only restyled afterwards, with a fixed width,
        // so the row never shifts while zooming.
        if (targets != zoomChips || zoomRow.childCount != (if (targets.size < 2) 0 else targets.size)) {
            zoomRow.removeAllViews()
            zoomChips = targets
            if (targets.size >= 2) {
                for (t in targets) {
                    val tv = chip(zoomRow, fmtZoom(t), false) { goZoom(t) }
                    tv.minWidth = dp(56)
                    tv.gravity = Gravity.CENTER
                }
            }
        }
        val active = targets.lastOrNull { it <= eff * 1.03f } ?: targets.firstOrNull()
        for (i in 0 until zoomRow.childCount) {
            val tv = zoomRow.getChildAt(i) as TextView
            val on = targets[i] == active
            tv.text = if (on) fmtZoom(eff) + "x" else fmtZoom(targets[i])
            styleChip(tv, on)
        }
        val canZoom = maxZoom > minZoom * 1.01f
        zoomDial.visibility = if (canZoom) View.VISIBLE else View.INVISIBLE
        zoomDial.setRange(minZoom * lensScale(), maxZoom * lensScale())
        if (!zoomDial.tracking) zoomDial.value = eff
    }

    private fun lensScale(): Float =
        if (rearLens() && lensIdx in lenses.indices) lenses[lensIdx].ratio else 1f

    private fun fmtZoom(z: Float): String {
        // Lens ratios come out as 0.52 or 2.9; show them as the familiar 0.5 and 3.
        val half = (z * 2).roundToInt() / 2f
        val v = if (half > 0f && abs(half - z) / z < 0.08f) half else (z * 10).roundToInt() / 10f
        return if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    }

    // ------------------------------------------------------------------ pro controls / tuning

    private fun isoValue(): Int {
        val r = isoRange ?: return 100
        val lo = r.lower.coerceAtLeast(1).toDouble()
        return (lo * (r.upper / lo).pow(isoP / 1000.0)).roundToInt().coerceIn(r.lower, r.upper)
    }

    private fun shutterNanos(): Long {
        val r = expRange ?: return 10_000_000L
        val lo = r.lower.coerceAtLeast(100_000L).toDouble()            // not faster than 1/10000s
        val hi = r.upper.coerceAtMost(1_000_000_000L).toDouble()       // not slower than 1s
        if (hi <= lo) return lo.toLong()
        return (lo * (hi / lo).pow(shutterP / 1000.0)).toLong()
    }

    private fun focusDiopters(): Float = minFocusDist * focusP / 1000f

    /** Pushes manual (Pro) settings and night tuning to the camera. */
    private fun applyTuning() {
        val cam = camera ?: return
        try {
            val b = CaptureRequestOptions.Builder()
            if (mode == Mode.PRO) {
                if (manualExp && isoRange != null && expRange != null) {
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, isoValue())
                    b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNanos())
                }
                if (manualFocus && minFocusDist > 0f) {
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters())
                }
                if (wbIdx > 0) {
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, WB_MODES[wbIdx])
                }
            } else if (nightTuning && sceneNightOk) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                b.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_NIGHT)
            }
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        } catch (e: Exception) {
            Log.w(TAG, "tuning not applied", e)
        }
        try {
            if (cam.cameraInfo.isLowLightBoostSupported) {
                cam.cameraControl.enableLowLightBoostAsync(nightTuning)
            }
        } catch (e: Exception) {
            Log.w(TAG, "low light boost not applied", e)
        }
    }

    private fun renderPro() {
        proPanel.removeAllViews()
        val pro = mode == Mode.PRO
        evRow.visibility = if (pro && manualExp) View.GONE else View.VISIBLE
        if (!pro || (!manualExp && !manualFocus)) {
            proPanel.visibility = View.GONE
            return
        }
        proPanel.visibility = View.VISIBLE
        if (manualExp) {
            proSlider("ISO", isoP, { isoValue().toString() }) { isoP = it }
            proSlider("SHUTTER", shutterP, { fmtShutter(shutterNanos()) }) { shutterP = it }
        }
        if (manualFocus) {
            proSlider("FOCUS", focusP, {
                val d = focusDiopters()
                if (d <= 0.01f) "∞" else String.format(Locale.US, "%.0fcm", 100f / d)
            }) { focusP = it }
        }
    }

    private fun proSlider(label: String, start: Int, value: () -> String, set: (Int) -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val name = TextView(this)
        name.text = label
        name.setTextColor(Color.WHITE)
        name.textSize = 11f
        name.typeface = Typeface.DEFAULT_BOLD
        val v = TextView(this)
        v.setTextColor(0xFFFFD60A.toInt())
        v.textSize = 12f
        v.gravity = Gravity.END
        v.text = value()
        val sb = SeekBar(this)
        sb.max = 1000
        sb.progress = start
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                set(progress)
                v.text = value()
                applyTuning()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        row.addView(name, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(sb, LinearLayout.LayoutParams(0, dp(36), 1f))
        row.addView(v, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
        proPanel.addView(row)
    }

    private fun fmtShutter(ns: Long): String {
        val sec = ns / 1e9
        return if (sec >= 0.4) String.format(Locale.US, "%.1fs", sec)
        else "1/" + (1.0 / sec).roundToInt()
    }

    // ------------------------------------------------------------------ chips / modes

    private fun processOptions(): List<Int> {
        val list = ArrayList<Int>()
        if (ultraHdrOk) list.add(0)
        list.add(-1)
        list.addAll(exts)
        // Night is always offered; without a vendor night mode the app tunes exposure itself.
        if (!list.contains(ExtensionMode.NIGHT)) list.add(ExtensionMode.NIGHT)
        return list
    }

    private fun processLabel(p: Int) = when (p) {
        0 -> "ULTRA HDR"
        ExtensionMode.HDR -> "HDR+"
        ExtensionMode.NIGHT -> "NIGHT"
        ExtensionMode.BOKEH -> "PORTRAIT"
        ExtensionMode.AUTO -> "AI AUTO"
        else -> "STANDARD"
    }

    private fun renderChips() {
        chipRow.removeAllViews()
        val m = mode
        if (m == Mode.VIDEO) filterScroll.visibility = View.GONE
        if (m != Mode.VIDEO) {
            chip(chipRow, if (filterIdx > 0) filter.name else "FILTER", filterIdx > 0) {
                filterScroll.visibility =
                    if (filterScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            if (m == Mode.PHOTO) {
                val opts = processOptions()
                val current = if (opts.contains(process)) process else opts[0]
                chip(chipRow, processLabel(current), current != -1) {
                    process = opts[(opts.indexOf(current) + 1) % opts.size]
                    bind()
                }
            }
            if (front == 1) {
                chip(chipRow, if (flash != 0) "SCREEN FLASH" else "FLASH OFF", flash != 0) {
                    flash = if (flash != 0) 0 else 2
                    bind()
                }
            } else {
                chip(chipRow, "FLASH " + arrayOf("OFF", "AUTO", "ON")[flash.coerceIn(0, 2)], flash != 0) {
                    flash = (flash + 1) % 3
                    imageCapture?.flashMode = flashConst()
                    renderChips()
                }
            }
            chip(chipRow, "DETAIL+", sharp == 1) {
                sharp = 1 - sharp
                renderChips()
            }
            chip(chipRow, if (timer == 0) "TIMER" else "TIMER ${timer}s", timer != 0) {
                timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 }
                renderChips()
            }
            chip(chipRow, if (ratio == 1) "16:9" else "4:3", false) {
                ratio = 1 - ratio
                bind()
            }
            chip(chipRow, "50MP", hiRes == 1) {
                hiRes = 1 - hiRes
                bind()
            }
            if (front == 1) {
                chip(chipRow, "MIRROR", mirror == 1) {
                    mirror = 1 - mirror
                    renderChips()
                }
            }
            if (m == Mode.PRO) {
                chip(chipRow, "RAW", raw == 1 && rawOk, rawOk) {
                    raw = 1 - raw
                    bind()
                }
                chip(chipRow, if (manualExp) "EXP MANUAL" else "EXP AUTO", manualExp, isoRange != null) {
                    manualExp = !manualExp
                    applyTuning(); renderPro(); renderChips()
                }
                chip(chipRow, if (manualFocus) "FOCUS MF" else "FOCUS AF", manualFocus, minFocusDist > 0f) {
                    manualFocus = !manualFocus
                    applyTuning(); renderPro(); renderChips()
                }
                chip(chipRow, "WB " + WB_NAMES[wbIdx], wbIdx != 0) {
                    wbIdx = (wbIdx + 1) % WB_MODES.size
                    applyTuning(); renderChips()
                }
            }
        } else {
            chip(chipRow, "NIGHT", nightVideo == 1) {
                nightVideo = 1 - nightVideo
                bind()
            }
            chip(chipRow, "LIGHT", torch || fillLight) {
                if (front == 1) {
                    setFillLight(!fillLight)
                } else {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                }
                renderChips()
            }
            chip(chipRow, arrayOf("4K", "1080p", "720p")[vq.coerceIn(0, 2)], false) {
                vq = (vq + 1) % 3
                bind()
            }
            chip(chipRow, if (fps == 60 && fps60Ok) "60 FPS" else "30 FPS", fps == 60 && fps60Ok, fps60Ok) {
                fps = if (fps == 60) 30 else 60
                bind()
            }
            chip(chipRow, "HDR 10-BIT", hdrVideo == 1 && hlgOk, hlgOk) {
                hdrVideo = 1 - hdrVideo
                bind()
            }
            chip(chipRow, "STEADY", stab == 1) {
                stab = 1 - stab
                bind()
            }
            chip(chipRow, if (mic == 1) "MIC ON" else "MIC OFF", mic == 1) {
                mic = 1 - mic
                renderChips()
            }
        }
        chip(chipRow, "GRID", grid == 1) {
            grid = 1 - grid
            gridView.show = grid == 1
            renderChips()
        }
    }

    private fun renderModes() {
        modeRow.removeAllViews()
        for (m in Mode.values()) {
            val tv = TextView(this)
            tv.text = m.name
            tv.textSize = 13f
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setShadowLayer(6f, 0f, 0f, Color.BLACK)
            tv.setTextColor(if (m == mode) 0xFFFFD60A.toInt() else Color.WHITE)
            tv.setPadding(dp(16), dp(6), dp(16), dp(6))
            tv.setOnClickListener {
                if (recording != null || m == mode) return@setOnClickListener
                cancelCountdown()
                modeIdx = m.ordinal
                renderModes()
                bind()
            }
            modeRow.addView(tv)
        }
    }

    private fun chip(
        parent: LinearLayout, text: String, active: Boolean,
        enabled: Boolean = true, onClick: () -> Unit
    ): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setPadding(dp(12), dp(7), dp(12), dp(7))
        styleChip(tv, active)
        tv.alpha = if (enabled) 1f else 0.4f
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(dp(4), 0, dp(4), 0)
        parent.addView(tv, lp)
        tv.setOnClickListener {
            if (!enabled) {
                toast("Yeh feature is lens / phone par available nahi hai")
                return@setOnClickListener
            }
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
        return tv
    }

    private fun styleChip(tv: TextView, active: Boolean) {
        if (tv.tag == active) return
        tv.tag = active
        tv.setTextColor(if (active) Color.BLACK else Color.WHITE)
        tv.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (active) 0xFFFFD60A.toInt() else 0x66000000)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun showThumb(uri: Uri, mime: String) {
        lastUri = uri
        lastMime = mime
        io.execute {
            try {
                val bmp = contentResolver.loadThumbnail(uri, Size(200, 200), null)
                runOnUiThread { thumb.setImageBitmap(bmp) }
            } catch (e: Exception) {
                Log.w(TAG, "thumbnail failed", e)
            }
        }
    }

    private fun openLast() {
        val uri = lastUri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, lastMime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            toast("Gallery app nahi mili")
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "GKCamera"
        private val WB_NAMES = arrayOf("AUTO", "SUN", "CLOUD", "BULB", "TUBE")
        private val WB_MODES = intArrayOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        )

        private val FILTERS = listOf(
            Filter("ORIGINAL", 1f, 1f, 0f),
            Filter("VIVID", 1.32f, 1.12f, 0f),
            Filter("VIVID WARM", 1.32f, 1.12f, 0.09f),
            Filter("VIVID COOL", 1.32f, 1.12f, -0.09f),
            Filter("DRAMATIC", 0.82f, 1.3f, 0f, -0.03f),
            Filter("DRAMATIC WARM", 0.82f, 1.3f, 0.09f, -0.03f),
            Filter("DRAMATIC COOL", 0.82f, 1.3f, -0.09f, -0.03f),
            Filter("MONO", 0f, 1.06f, 0f),
            Filter("SILVERTONE", 0f, 1.22f, 0f, 0.03f, 0.06f),
            Filter("NOIR", 0f, 1.5f, 0f, -0.04f)
        )

        private val EXIF_TAGS = arrayOf(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE
        )
    }
}
