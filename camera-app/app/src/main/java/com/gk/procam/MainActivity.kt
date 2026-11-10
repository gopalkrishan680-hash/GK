package com.gk.procam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class MainActivity : AppCompatActivity() {

    private enum class Mode { PHOTO, VIDEO, PRO }

    // ---- views ----
    private lateinit var previewView: PreviewView
    private lateinit var gridView: GridOverlayView
    private lateinit var focusRing: View
    private lateinit var flashOverlay: View
    private lateinit var countdownText: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var recTime: TextView
    private lateinit var infoText: TextView
    private lateinit var proPanel: LinearLayout
    private lateinit var evRow: View
    private lateinit var evSeek: SeekBar
    private lateinit var evValue: TextView
    private lateinit var zoomRow: LinearLayout
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

    private val mode: Mode get() = Mode.values()[modeIdx.coerceIn(0, 2)]

    // ---- camera state ----
    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordingPaused = false
    private var backCams: List<CameraInfo> = emptyList()
    private var lensIndex = 0
    private var torch = false

    // capabilities of the currently selected camera
    private var ultraHdrOk = false
    private var rawOk = false
    private var exts: List<Int> = emptyList()
    private var hlgOk = false
    private var fps60Ok = false
    private var rawActive = false
    private var statusLabel = ""

    // zoom
    private var curZoom = 1f
    private var minZoom = 1f
    private var maxZoom = 1f

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

        previewView = findViewById(R.id.preview)
        gridView = findViewById(R.id.grid)
        focusRing = findViewById(R.id.focusRing)
        flashOverlay = findViewById(R.id.flashOverlay)
        countdownText = findViewById(R.id.countdown)
        chipRow = findViewById(R.id.chipRow)
        recTime = findViewById(R.id.recTime)
        infoText = findViewById(R.id.infoText)
        proPanel = findViewById(R.id.proPanel)
        evRow = findViewById(R.id.evRow)
        evSeek = findViewById(R.id.evSeek)
        evValue = findViewById(R.id.evValue)
        zoomRow = findViewById(R.id.zoomRow)
        modeRow = findViewById(R.id.modeRow)
        thumb = findViewById(R.id.thumb)
        pauseBtn = findViewById(R.id.pauseBtn)
        shutter = findViewById(R.id.shutter)
        switchCam = findViewById(R.id.switchCam)
        thumb.clipToOutline = true

        // Full screen, with the controls kept clear of the cutout and navigation bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.root)
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
            lensIndex = 0
            bind()
        }
        thumb.setOnClickListener { openLast() }
        pauseBtn.setOnClickListener { togglePause() }

        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
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
            val extFuture = ExtensionsManager.getInstanceAsync(this, p)
            extFuture.addListener({
                extensions = try { extFuture.get() } catch (e: Exception) { null }
                bind()
            }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun baseSelector(): CameraSelector = when {
        front == 1 -> CameraSelector.DEFAULT_FRONT_CAMERA
        lensIndex > 0 && lensIndex < backCams.size -> backCams[lensIndex].cameraSelector
        else -> CameraSelector.DEFAULT_BACK_CAMERA
    }

    /**
     * (Re)binds the use cases for the current mode and settings. If the device rejects the
     * requested combination it is retried with fewer extras: [level] 1 drops stabilisation,
     * 50MP readout and vendor extensions; level 2 also drops HDR, 60fps, RAW and lens choice.
     */
    private fun bind(level: Int = 0) {
        val lite = level >= 1
        val safe = level >= 2
        if (safe) lensIndex = 0
        val p = provider ?: return
        if (!hasPermission(Manifest.permission.CAMERA)) return
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        try {
            p.unbindAll()
            imageCapture = null
            videoCapture = null
            torch = false

            var selector = baseSelector()
            if (!p.hasCamera(selector)) {
                front = 0
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
            fps60Ok = info.supportedFrameRateRanges.any { it.lower >= 60 && it.upper >= 60 }

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

            if (mode == Mode.VIDEO) {
                val dr = if (hdrVideo == 1 && hlgOk && !safe) DynamicRange.HLG_10_BIT else DynamicRange.SDR
                val wantedQ = listOf(Quality.UHD, Quality.FHD, Quality.HD)[vq.coerceIn(0, 2)]
                val useFps = if (fps == 60 && fps60Ok && !safe) 60 else 30
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
                    .setTargetFrameRate(Range(useFps, useFps))
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
                statusLabel = listOfNotNull(
                    arrayOf("4K", "1080p", "720p")[vq.coerceIn(0, 2)],
                    "${useFps}fps",
                    if (dr == DynamicRange.HLG_10_BIT) "HDR10-bit" else null,
                    if (steady) "Steady" else null
                ).joinToString(" · ")
                rawActive = false
            } else {
                // Vendor processing (HDR / Night / Portrait) via camera extensions, photo mode only.
                var activeExt = ExtensionMode.NONE
                if (mode == Mode.PHOTO && !lite && em != null && process > 0 && exts.contains(process)) {
                    selector = em.getExtensionEnabledCameraSelector(selector, process)
                    activeExt = process
                }
                rawActive = mode == Mode.PRO && raw == 1 && rawOk && !safe
                val ultra = !rawActive && !safe && activeExt == ExtensionMode.NONE &&
                    ultraHdrOk && (process == 0 || mode == Mode.PRO)
                val full = hiRes == 1 && !lite && activeExt == ExtensionMode.NONE

                val rs = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspectStrategy)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                if (full) rs.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)

                val ib = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
                    .setFlashMode(flashConst())
                    .setTargetRotation(curRotation)
                    .setResolutionSelector(rs.build())
                when {
                    rawActive -> ib.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                    ultra -> ib.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                }
                val ic = ib.build()
                imageCapture = ic
                useCases.add(ic)
                statusLabel = when {
                    rawActive -> "RAW + JPEG"
                    activeExt != ExtensionMode.NONE -> processLabel(activeExt)
                    ultra -> "Ultra HDR"
                    else -> "Standard"
                }
            }

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
        cam.cameraInfo.zoomState.observe(this) { z ->
            curZoom = z.zoomRatio
            minZoom = z.minZoomRatio
            maxZoom = z.maxZoomRatio
            renderZoom()
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
        applyPro()

        shutter.videoMode = mode == Mode.VIDEO
        gridView.show = grid == 1
        renderChips()
        renderPro()
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
        fun out(ext: String, mime: String): ImageCapture.OutputFileOptions {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.$ext")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/GKCamera")
            }
            return ImageCapture.OutputFileOptions.Builder(
                contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ).build()
        }

        val callback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                val uri = results.savedUri ?: return
                runOnUiThread { showThumb(uri, "image/*") }
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

    private fun setZoom(value: Float) {
        camera?.cameraControl?.setZoomRatio(value.coerceIn(minZoom, maxZoom))
    }

    private fun renderZoom() {
        zoomRow.removeAllViews()
        val stops = ArrayList<Float>()
        if (minZoom < 0.95f) stops.add(minZoom)
        for (s in floatArrayOf(1f, 2f, 3f, 5f, 10f)) if (s <= maxZoom + 0.01f) stops.add(s)
        if (stops.size < 2) return
        val nearest = stops.minByOrNull { abs(it - curZoom) }
        for (s in stops) {
            val active = s == nearest
            val label = if (active) fmtZoom(curZoom) + "x" else fmtZoom(s)
            chip(zoomRow, label, active) { setZoom(s) }
        }
    }

    private fun fmtZoom(z: Float): String {
        val r = (z * 10).roundToInt() / 10f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }

    // ------------------------------------------------------------------ pro controls

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

    private fun applyPro() {
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
            }
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        } catch (e: Exception) {
            Log.w(TAG, "pro controls not applied", e)
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
                applyPro()
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
        if (m != Mode.VIDEO) {
            chip(chipRow, "FLASH " + arrayOf("OFF", "AUTO", "ON")[flash.coerceIn(0, 2)], flash != 0) {
                flash = (flash + 1) % 3
                imageCapture?.flashMode = flashConst()
                renderChips()
            }
            if (m == Mode.PHOTO) {
                val opts = processOptions()
                val current = if (opts.contains(process)) process else opts[0]
                chip(chipRow, processLabel(current), current != -1) {
                    process = opts[(opts.indexOf(current) + 1) % opts.size]
                    bind()
                }
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
            if (m == Mode.PRO) {
                chip(chipRow, "RAW", raw == 1 && rawOk, rawOk) {
                    raw = 1 - raw
                    bind()
                }
                chip(chipRow, if (manualExp) "EXP MANUAL" else "EXP AUTO", manualExp, isoRange != null) {
                    manualExp = !manualExp
                    applyPro(); renderPro(); renderChips()
                }
                chip(chipRow, if (manualFocus) "FOCUS MF" else "FOCUS AF", manualFocus, minFocusDist > 0f) {
                    manualFocus = !manualFocus
                    applyPro(); renderPro(); renderChips()
                }
                chip(chipRow, "WB " + WB_NAMES[wbIdx], wbIdx != 0) {
                    wbIdx = (wbIdx + 1) % WB_MODES.size
                    applyPro(); renderChips()
                }
            }
        } else {
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
            chip(chipRow, "LIGHT", torch) {
                torch = !torch
                camera?.cameraControl?.enableTorch(torch)
                renderChips()
            }
        }
        chip(chipRow, "GRID", grid == 1) {
            grid = 1 - grid
            gridView.show = grid == 1
            renderChips()
        }
        if (front == 0 && backCams.size > 1) {
            chip(chipRow, "LENS ${lensIndex + 1}/${backCams.size}", lensIndex != 0) {
                lensIndex = (lensIndex + 1) % backCams.size
                bind()
            }
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
    ) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setTextColor(if (active) Color.BLACK else Color.WHITE)
        tv.setPadding(dp(12), dp(7), dp(12), dp(7))
        tv.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (active) 0xFFFFD60A.toInt() else 0x66000000)
        }
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
    }
}
