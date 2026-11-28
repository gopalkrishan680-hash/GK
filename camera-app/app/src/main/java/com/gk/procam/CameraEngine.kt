package com.gk.procam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaActionSound
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt

private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.awaitFuture(): T =
    suspendCancellableCoroutine { cont ->
        addListener({
            runCatching { get() }.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
        }, Runnable::run)
    }

enum class Mode(val label: String, val isVideo: Boolean) {
    TIMELAPSE("TIME-LAPSE", true),
    SLOMO("SLO-MO", true),
    VIDEO("VIDEO", true),
    PHOTO("PHOTO", false),
    PORTRAIT("PORTRAIT", false),
    NIGHT("NIGHT", false),
    PRO("PRO", false),
}

data class LensOption(val cameraId: String, val zoom: Float, val displayZoom: Float)

enum class ProParam(val label: String) { ISO("ISO"), SHUTTER("S"), WB("WB"), FOCUS("MF"), EV("EV") }

val WB_MODES = listOf(
    "Auto" to CaptureRequest.CONTROL_AWB_MODE_AUTO,
    "Daylight" to CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
    "Cloudy" to CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
    "Shade" to CaptureRequest.CONTROL_AWB_MODE_SHADE,
    "Tungsten" to CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
    "Fluorescent" to CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
    "Twilight" to CaptureRequest.CONTROL_AWB_MODE_TWILIGHT,
)

private val ALL_ISO = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
    1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800)
private val ALL_SHUTTER_NS: List<Long> = listOf(8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2)
    .map { 1_000_000_000L / it } + listOf(1, 2, 4, 8, 15, 30).map { it * 1_000_000_000L }

@OptIn(ExperimentalCamera2Interop::class, ExperimentalGetImage::class, UnstableApi::class)
class CameraEngine(private val activity: ComponentActivity, val settings: AppSettings) {
    private val tag = "ProCam"
    val previewView = PreviewView(activity).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val mainExec = ContextCompat.getMainExecutor(activity)
    private val camExec = Executors.newSingleThreadExecutor()
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val scope = activity.lifecycleScope
    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var zoomLive: LiveData<ZoomState>? = null
    private val zoomObserver = Observer<ZoomState> {
        zoomRatio = it.zoomRatio; minZoom = it.minZoomRatio; maxZoom = it.maxZoomRatio
    }
    private val sound by lazy { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    private val scanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    val portraitAi = PortraitProcessor(activity)

    // ---------------- UI state
    var mode by mutableStateOf(Mode.PHOTO); private set
    var lensFacing by mutableIntStateOf(CameraSelector.LENS_FACING_BACK); private set
    var lensOptions by mutableStateOf(listOf<LensOption>()); private set
    var activeCameraId by mutableStateOf<String?>(null); private set
    var activeZoomFactor by mutableFloatStateOf(1f); private set   // intrinsic factor of a separate physical lens
    var zoomRatio by mutableFloatStateOf(1f); private set
    var minZoom by mutableFloatStateOf(1f); private set
    var maxZoom by mutableFloatStateOf(1f); private set
    var isRecording by mutableStateOf(false); private set
    var recordNanos by mutableLongStateOf(0L); private set
    var capturing by mutableStateOf(false); private set
    var captureProgress by mutableFloatStateOf(0f); private set
    var captureLabel by mutableStateOf<String?>(null); private set
    var processing by mutableIntStateOf(0); private set
    var thumbnail by mutableStateOf<Bitmap?>(null); private set
    private var lastUri: Pair<Uri, String>? = null
    var toast by mutableStateOf<String?>(null)
    var focusPoint by mutableStateOf<Offset?>(null); private set
    var focusKey by mutableIntStateOf(0); private set
    var aeLocked by mutableStateOf(false); private set
    var ev by mutableFloatStateOf(0f); private set
    var evMin by mutableFloatStateOf(-2f); private set
    var evMax by mutableFloatStateOf(2f); private set
    private var evStep = 1f / 3f
    var liveIso by mutableIntStateOf(0); private set
    var liveExposureNs by mutableLongStateOf(0L); private set
    var qrText by mutableStateOf<String?>(null); private set
    var histogram by mutableStateOf<FloatArray?>(null); private set
    var activeExtension by mutableStateOf<Int?>(null); private set
    var hasFlash by mutableStateOf(true); private set
    var countdown by mutableIntStateOf(0); private set
    var orientation by mutableIntStateOf(0); private set        // raw degrees 0..359
    var snappedRotation by mutableIntStateOf(0); private set    // 0/90/180/270
    var shutterBlink by mutableIntStateOf(0); private set
    var burstCount by mutableIntStateOf(0); private set
    var rawSupported by mutableStateOf(false); private set
    var ultraHdrSupported by mutableStateOf(false); private set
    var hdrVideoSupported by mutableStateOf(false); private set
    var slomoActiveFps by mutableIntStateOf(0); private set
    var nightDeviceAvailable by mutableStateOf(false); private set
    var outputMp by mutableFloatStateOf(0f); private set
    var aperture by mutableFloatStateOf(2.8f)

    // Pro
    var proIso by mutableStateOf<Int?>(null); private set
    var proShutter by mutableStateOf<Long?>(null); private set
    var proWb by mutableIntStateOf(0); private set
    var proFocus by mutableStateOf<Float?>(null); private set
    var isoValues by mutableStateOf(listOf<Int>()); private set
    var shutterValues by mutableStateOf(listOf<Long>()); private set
    private var minFocusDiopters = 10f

    private var timerJob: Job? = null
    private var burstJob: Job? = null
    private var pendingZoom: Float? = null
    private var defaultBackId: String? = null
    private var defaultFrontId: String? = null
    private var lastReadout = 0L

    val darkScene get() = liveIso >= 1600 || liveExposureNs >= 50_000_000L
    val style get() = PhotoStyle.entries.getOrElse(settings.style) { PhotoStyle.STANDARD }

    private val orientationListener = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(o: Int) {
            if (o == ORIENTATION_UNKNOWN) return
            orientation = o
            val snapped = when {
                abs(angleDiff(o, snappedRotation)) <= 55 -> snappedRotation
                else -> ((o + 45) / 90 % 4) * 90
            }
            if (snapped != snappedRotation) {
                snappedRotation = snapped
                val r = surfaceRotation()
                imageCapture?.targetRotation = r
                if (!isRecording) videoCapture?.targetRotation = r
            }
        }
    }

    private fun angleDiff(a: Int, b: Int): Int { var d = (a - b) % 360; if (d > 180) d -= 360; if (d < -180) d += 360; return d }

    private fun surfaceRotation() = when (snappedRotation) {
        90 -> Surface.ROTATION_270
        180 -> Surface.ROTATION_180
        270 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }

    // ============================================================== lifecycle

    fun start() {
        orientationListener.enable()
        previewView.setScreenFlashWindow(activity.window)
        portraitAi.ensureModel()
        scope.launch {
            val p = ProcessCameraProvider.getInstance(activity).awaitFuture()
            provider = p
            extensions = runCatching { ExtensionsManager.getInstance(activity, p) }.getOrNull()
            defaultBackId = runCatching { idOf(p.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)) }.getOrNull()
            defaultFrontId = runCatching { idOf(p.getCameraInfo(CameraSelector.DEFAULT_FRONT_CAMERA)) }.getOrNull()
            computeLensOptions()
            bind()
            refreshLastThumbnail()
        }
    }

    fun release() {
        orientationListener.disable()
        recording?.stop()
        sound.release()
        scanner.close()
        camExec.shutdown()
        analysisExec.shutdown()
    }

    private fun idOf(info: CameraInfo): String = Camera2CameraInfo.from(info).cameraId

    // ============================================================== lenses

    private fun computeLensOptions() {
        val p = provider ?: return
        val defId = (if (lensFacing == CameraSelector.LENS_FACING_BACK) defaultBackId else defaultFrontId) ?: return
        val infos = p.availableCameraInfos.filter { it.lensFacing == lensFacing }
        val def = infos.firstOrNull { idOf(it) == defId } ?: return
        val defIntrinsic = def.intrinsicZoomRatio.takeIf { it > 0f } ?: 1f
        val zs = def.zoomState.value
        val minZ = zs?.minZoomRatio ?: 1f
        val maxZ = zs?.maxZoomRatio ?: 1f
        val opts = mutableListOf<LensOption>()
        fun rel(info: CameraInfo) = (info.intrinsicZoomRatio.takeIf { it > 0f } ?: 1f) / defIntrinsic

        if (minZ < 0.95f) opts += LensOption(defId, minZ, minZ)
        else infos.filter { idOf(it) != defId && rel(it) < 0.9f }.minByOrNull { rel(it) }
            ?.let { opts += LensOption(idOf(it), 1f, rel(it)) }
        opts += LensOption(defId, 1f, 1f)
        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            if (maxZ >= 2f) opts += LensOption(defId, 2f, 2f)
            val tele = infos.filter { idOf(it) != defId && rel(it) > 1.8f }.maxByOrNull { rel(it) }
            when {
                tele != null -> opts += LensOption(idOf(tele), 1f, rel(tele))
                maxZ >= 3f -> opts += LensOption(defId, 3f, 3f)
            }
        }
        lensOptions = opts
        if (activeCameraId == null) activeCameraId = defId
    }

    fun selectLens(opt: LensOption) {
        haptic()
        if (opt.cameraId == activeCameraId) {
            camera?.cameraControl?.setZoomRatio(opt.zoom)
        } else {
            if (isRecording) return
            activeCameraId = opt.cameraId
            pendingZoom = opt.zoom
            bind()
        }
    }

    val displayZoom get() = zoomRatio * activeZoomFactor

    fun zoomBy(factor: Float) {
        val cam = camera ?: return
        cam.cameraControl.setZoomRatio((zoomRatio * factor).coerceIn(minZoom, maxZoom))
    }

    fun flipCamera() {
        if (isRecording || capturing) return
        haptic()
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
        else CameraSelector.LENS_FACING_BACK
        activeCameraId = null
        computeLensOptions()
        bind()
    }

    private fun selector(): CameraSelector {
        val id = activeCameraId
        val defId = if (lensFacing == CameraSelector.LENS_FACING_BACK) defaultBackId else defaultFrontId
        if (id == null || id == defId) return CameraSelector.Builder().requireLensFacing(lensFacing).build()
        return CameraSelector.Builder().addCameraFilter { list -> list.filter { idOf(it) == id } }.build()
    }

    // ============================================================== modes

    fun changeMode(m: Mode) {
        if (m == mode || isRecording || capturing) return
        haptic()
        mode = m
        resetPro()
        if (m == Mode.PORTRAIT && !portraitAi.ready && settings.portraitEngine == 0) {
            if (portraitAi.state == ModelState.FAILED) portraitAi.download()
            toast = "Preparing AI portrait model..."
        }
        bind()
    }

    fun shiftMode(delta: Int) {
        val list = Mode.entries
        val i = (mode.ordinal + delta).coerceIn(0, list.size - 1)
        changeMode(list[i])
    }

    fun rebind() = bind()

    private fun bind() {
        val p = provider ?: return
        if (isRecording) return
        zoomLive?.removeObserver(zoomObserver)
        p.unbindAll()
        camera = null; imageCapture = null; videoCapture = null
        activeExtension = null; qrText = null; histogram = null; slomoActiveFps = 0
        focusPoint = null; aeLocked = false
        activeZoomFactor = lensOptions.firstOrNull { it.cameraId == activeCameraId && it.zoom == 1f }?.displayZoom ?: 1f
        try {
            when (mode) {
                Mode.SLOMO -> bindSlowMo(p)
                Mode.VIDEO, Mode.TIMELAPSE -> bindVideo(p)
                else -> bindPhoto(p, withAnalysis = true)
            }
        } catch (e: Exception) {
            Log.w(tag, "bind failed, retrying simpler config", e)
            try {
                p.unbindAll()
                when (mode) {
                    Mode.SLOMO, Mode.VIDEO, Mode.TIMELAPSE -> bindVideo(p, safe = true)
                    else -> bindPhoto(p, withAnalysis = false, safe = true)
                }
            } catch (e2: Exception) {
                Log.e(tag, "bind failed", e2)
                toast = "Camera unavailable"
            }
        }
        camera?.let { onBound(it) }
    }

    private fun previewBuilder(aspect: Int, readouts: Boolean = true): Preview.Builder {
        val b = Preview.Builder().setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy(aspect, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                .build()
        )
        if (readouts) Camera2Interop.Extender(b).setSessionCaptureCallback(readoutCallback)
        return b
    }

    private fun desiredExtension(sel: CameraSelector): Int? {
        val em = extensions ?: return null
        fun ok(m: Int) = runCatching { em.isExtensionAvailable(sel, m) }.getOrDefault(false)
        nightDeviceAvailable = ok(ExtensionMode.NIGHT)
        return when (mode) {
            Mode.NIGHT -> if (settings.nightEngine == 0 && ok(ExtensionMode.NIGHT)) ExtensionMode.NIGHT else null
            Mode.PORTRAIT -> if (settings.portraitEngine == 1 && ok(ExtensionMode.BOKEH)) ExtensionMode.BOKEH else null
            Mode.PHOTO -> if (!settings.highRes && settings.smartHdr && ok(ExtensionMode.HDR)) ExtensionMode.HDR else null
            else -> null
        }
    }

    private fun bindPhoto(p: ProcessCameraProvider, withAnalysis: Boolean, safe: Boolean = false) {
        val sel = selector()
        val ext = if (safe) null else desiredExtension(sel)
        val aspect = if (settings.aspect == 1) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
        val info = p.getCameraInfo(sel)

        val pb = previewBuilder(aspect, readouts = ext == null)
        if (ext != null) { liveIso = 0; liveExposureNs = 0 }
        val multiFrame = mode == Mode.NIGHT || mode == Mode.PORTRAIT || (mode == Mode.PHOTO && settings.fusion > 0)
        val highRes = settings.highRes && (mode == Mode.PHOTO || mode == Mode.PRO) && !safe
        val ib = ImageCapture.Builder()
            .setCaptureMode(
                if (multiFrame && !highRes) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            )
            .setJpegQuality(100)
            .setFlashMode(flashModeFor())
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(aspect, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .apply { if (highRes) setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE) }
                    .build()
            )
        if (ext == null) {
            Camera2Interop.Extender(ib)
                .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            if (mode == Mode.NIGHT) {
                // Let auto-exposure use longer shutter times for brighter, cleaner night frames.
                nightFpsRange(info)?.let { r ->
                    Camera2Interop.Extender(pb).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r)
                    Camera2Interop.Extender(ib).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r)
                }
            }
        }
        val formats = runCatching { ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats }.getOrDefault(emptySet())
        rawSupported = ImageCapture.OUTPUT_FORMAT_RAW_JPEG in formats
        ultraHdrSupported = ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR in formats
        if (ext == null && !safe) {
            if (mode == Mode.PRO && settings.proRaw && rawSupported) ib.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
            else if (mode == Mode.PHOTO && settings.ultraHdr && ultraHdrSupported) ib.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
        }
        val preview = pb.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val ic = ib.build()
        ic.screenFlash = previewView.screenFlash
        ic.targetRotation = surfaceRotation()

        val useCases = mutableListOf<UseCase>(preview, ic)
        val wantAnalysis = withAnalysis && (mode == Mode.PRO || (mode == Mode.PHOTO && settings.qrScan))
        val analysisOk = ext == null || runCatching { extensions!!.isImageAnalysisSupported(sel, ext) }.getOrDefault(false)
        if (wantAnalysis && analysisOk) useCases += buildAnalysis()

        val finalSel = if (ext != null) extensions!!.getExtensionEnabledCameraSelector(sel, ext) else sel
        camera = try {
            p.bindToLifecycle(activity, finalSel, *useCases.toTypedArray())
        } catch (e: Exception) {
            if (useCases.size > 2) {
                p.unbindAll()
                p.bindToLifecycle(activity, finalSel, preview, ic)
            } else throw e
        }
        imageCapture = ic
        activeExtension = ext
        outputMp = ic.resolutionInfo?.resolution?.let { it.width * it.height / 1_000_000f } ?: 0f
    }

    private fun nightFpsRange(info: CameraInfo): Range<Int>? {
        val ranges = runCatching {
            Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        }.getOrNull() ?: return null
        return ranges.filter { it.upper >= 24 && it.lower >= 5 }.minByOrNull { it.lower }
    }

    private fun buildAnalysis(): ImageAnalysis =
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build()
            )
            .build().also { it.setAnalyzer(analysisExec, ::analyze) }

    private fun bindVideo(p: ProcessCameraProvider, safe: Boolean = false) {
        val sel = selector()
        val info = p.getCameraInfo(sel)
        val caps = Recorder.getVideoCapabilities(info)
        hdrVideoSupported = caps.supportedDynamicRanges.any { it == DynamicRange.HLG_10_BIT }
        val hdr = !safe && mode == Mode.VIDEO && settings.hdrVideo && hdrVideoSupported
        val dr = if (hdr) DynamicRange.HLG_10_BIT else DynamicRange.SDR
        val uhd = mode == Mode.VIDEO && settings.videoRes == 0 && !safe
        val want = if (uhd) Quality.UHD else Quality.FHD
        val fps60 = !safe && mode == Mode.VIDEO && settings.videoFps == 60
        val bitrate = (if (uhd) 80_000_000 else 28_000_000) * (if (fps60) 3 else 2) / 2
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(want, FallbackStrategy.lowerQualityOrHigherThan(want)))
            .setTargetVideoEncodingBitRate(bitrate)
            .build()
        val vb = VideoCapture.Builder(recorder)
            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
            .setDynamicRange(dr)
        if (fps60) vb.setTargetFrameRate(Range(60, 60))
        if (!safe && settings.stabilization && caps.isStabilizationSupported) vb.setVideoStabilizationEnabled(true)
        val vc = vb.build().also { it.targetRotation = surfaceRotation() }
        val preview = previewBuilder(AspectRatio.RATIO_16_9).setDynamicRange(dr).build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        camera = try {
            p.bindToLifecycle(activity, sel, preview, vc)
        } catch (e: Exception) {
            if (safe) throw e
            p.unbindAll()
            toast = "That video format isn't supported here, using 1080p 30"
            return bindVideo(p, safe = true)
        }
        videoCapture = vc
    }

    private fun bindSlowMo(p: ProcessCameraProvider) {
        val sel = selector()
        val info = p.getCameraInfo(sel)
        val caps = runCatching { Recorder.getHighSpeedVideoCapabilities(info) }.getOrNull()
        val qualities = caps?.getSupportedQualities(DynamicRange.SDR).orEmpty()
        if (qualities.isEmpty()) {
            toast = "Slo-mo isn't available on this lens"
            bindVideo(p, safe = true)
            return
        }
        val q = if (Quality.FHD in qualities) Quality.FHD else qualities.first()
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(q)).build()
        val vc = VideoCapture.Builder(recorder).build().also { it.targetRotation = surfaceRotation() }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val ranges = info.getSupportedFrameRateRanges(HighSpeedVideoSessionConfig(vc, preview))
        val fixed = ranges.filter { it.lower == it.upper }.ifEmpty { ranges.toList() }
        val range = fixed.firstOrNull { it.upper == settings.slomoFps }
            ?: fixed.filter { it.upper <= settings.slomoFps }.maxByOrNull { it.upper }
            ?: fixed.maxByOrNull { it.upper }
        if (range == null) {
            toast = "Slo-mo isn't available on this lens"
            bindVideo(p, safe = true); return
        }
        camera = p.bindToLifecycle(activity, sel, HighSpeedVideoSessionConfig(vc, preview, range, true))
        videoCapture = vc
        slomoActiveFps = range.upper
    }

    private fun onBound(cam: Camera) {
        zoomLive = cam.cameraInfo.zoomState.also { it.observe(activity, zoomObserver) }
        pendingZoom?.let { cam.cameraControl.setZoomRatio(it) }
        pendingZoom = null
        hasFlash = cam.cameraInfo.hasFlashUnit()
        val es = cam.cameraInfo.exposureState
        evStep = es.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: (1f / 3f)
        evMin = es.exposureCompensationRange.lower * evStep
        evMax = es.exposureCompensationRange.upper * evStep
        ev = 0f
        val c2 = Camera2CameraInfo.from(cam.cameraInfo)
        val isoR = runCatching { c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) }.getOrNull()
        val expR = runCatching { c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) }.getOrNull()
        isoValues = if (isoR != null) ALL_ISO.filter { it in isoR.lower..isoR.upper } else ALL_ISO.take(18)
        shutterValues = if (expR != null) ALL_SHUTTER_NS.filter { it in expR.lower..expR.upper } else ALL_SHUTTER_NS.take(13)
        minFocusDiopters = runCatching { c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) }
            .getOrNull()?.takeIf { it > 0f } ?: 0f
        if (mode == Mode.NIGHT && activeExtension == null && cam.cameraInfo.isLowLightBoostSupported) {
            runCatching { cam.cameraControl.enableLowLightBoostAsync(true) }
        }
        applyRequestOptions()
    }

    // ============================================================== live analysis

    private val readoutCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastReadout < 250) return
            lastReadout = now
            val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: return
            val exp = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            mainExec.execute { liveIso = iso; liveExposureNs = exp }
        }
    }

    private var lastHist = 0L
    private var lastQrScan = 0L
    private var lastQrSeen = 0L
    @Volatile private var qrBusy = false

    private fun analyze(img: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (mode == Mode.PRO && now - lastHist > 120) {
            lastHist = now
            val plane = img.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val bins = IntArray(64)
            var y = 0
            while (y < img.height) {
                var x = 0
                while (x < img.width) {
                    bins[(buf.get(y * rs + x * ps).toInt() and 0xFF) shr 2]++
                    x += 6
                }
                y += 6
            }
            val max = bins.max().coerceAtLeast(1).toFloat()
            val norm = FloatArray(64) { (bins[it] / max) }
            mainExec.execute { histogram = norm }
        }
        if (mode == Mode.PHOTO && settings.qrScan && !qrBusy && now - lastQrScan > 350) {
            val media = img.image
            if (media != null) {
                lastQrScan = now
                qrBusy = true
                scanner.process(InputImage.fromMediaImage(media, img.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        val t = codes.firstOrNull()?.rawValue
                        val seen = SystemClock.elapsedRealtime()
                        if (t != null) { lastQrSeen = seen; qrText = t }
                        else if (seen - lastQrSeen > 1500) qrText = null
                    }
                    .addOnCompleteListener { qrBusy = false; img.close() }
                return
            }
        }
        img.close()
    }

    fun openQr() {
        val t = qrText ?: return
        val uri = Uri.parse(t)
        if (uri.scheme in listOf("http", "https", "tel", "mailto", "geo", "upi", "sms")) {
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        } else {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("QR", t))
            toast = "Copied to clipboard"
        }
    }

    // ============================================================== focus / exposure

    fun tapFocus(x: Float, y: Float, lock: Boolean) {
        val cam = camera ?: return
        if (mode == Mode.PRO && proFocus != null) return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .apply { if (lock) disableAutoCancel() else setAutoCancelDuration(5, TimeUnit.SECONDS) }
            .build()
        if (aeLocked && !lock) { aeLocked = false; applyRequestOptions() }
        cam.cameraControl.startFocusAndMetering(action)
        focusPoint = Offset(x, y)
        focusKey++
        applyEv(0f)
        if (lock) {
            haptic()
            scope.launch { delay(700); aeLocked = true; applyRequestOptions() }
        }
    }

    fun clearFocus() { if (!aeLocked) focusPoint = null }

    fun applyEv(value: Float) {
        val cam = camera ?: return
        val v = value.coerceIn(evMin, evMax)
        val idx = (v / evStep).roundToInt()
        ev = idx * evStep
        cam.cameraControl.setExposureCompensationIndex(idx)
    }

    // ============================================================== pro controls

    private fun resetPro() { proIso = null; proShutter = null; proWb = 0; proFocus = null }

    fun updateProIso(v: Int?) { proIso = v; applyRequestOptions() }
    fun updateProShutter(v: Long?) { proShutter = v; applyRequestOptions() }
    fun updateProWb(i: Int) { proWb = i; applyRequestOptions() }
    fun updateProFocus(v: Float?) { proFocus = v; applyRequestOptions() }

    private fun applyRequestOptions() {
        val cam = camera ?: return
        if (activeExtension != null || mode == Mode.SLOMO) return
        val b = CaptureRequestOptions.Builder()
        if (mode == Mode.PRO) {
            if (proIso != null || proShutter != null) {
                val iso = proIso ?: liveIso.takeIf { it > 0 } ?: 100
                val exp = proShutter ?: liveExposureNs.takeIf { it > 0 } ?: 16_666_666L
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exp, 33_333_333L))
            }
            if (proWb != 0) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, WB_MODES[proWb].second)
            proFocus?.let {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it * minFocusDiopters)
            }
        }
        if (aeLocked) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
        runCatching { Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build()) }
    }

    // ============================================================== flash

    fun cycleFlash() {
        settings.flash = (settings.flash + 1) % 3
        imageCapture?.flashMode = flashModeFor()
        if (mode.isVideo) camera?.cameraControl?.enableTorch(settings.flash == 1)
    }

    private fun flashModeFor(): Int {
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        return when (settings.flash) {
            0 -> if (front) ImageCapture.FLASH_MODE_OFF else ImageCapture.FLASH_MODE_AUTO
            1 -> if (front) ImageCapture.FLASH_MODE_SCREEN else ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    // ============================================================== shutter

    fun onShutter() {
        if (mode.isVideo) { toggleRecording(); return }
        timerJob?.let {
            if (it.isActive) { it.cancel(); countdown = 0; return }
        }
        if (capturing || burstJob?.isActive == true) return
        val t = settings.timer
        if (t > 0) {
            timerJob = scope.launch {
                for (i in t downTo 1) { countdown = i; delay(1000) }
                countdown = 0
                takePhoto()
            }
        } else takePhoto()
    }

    fun startBurst() {
        if (mode != Mode.PHOTO || capturing || countdown > 0) return
        val ic = imageCapture ?: return
        if (ic.outputFormat != ImageCapture.OUTPUT_FORMAT_JPEG) return
        burstCount = 0
        burstJob = scope.launch {
            while (isActive && burstCount < 200) {
                val ok = runCatching { saveDirect(ic) }.isSuccess
                if (!ok) break
                burstCount++
            }
        }
    }

    fun stopBurst() {
        if (burstJob?.isActive == true) {
            burstJob?.cancel()
            val n = burstCount
            if (n > 0) toast = "$n photos saved"
            scope.launch { delay(600); burstCount = 0 }
        }
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        ic.targetRotation = surfaceRotation()
        val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT && settings.mirrorFront
        val ext = activeExtension
        val styled = style != PhotoStyle.STANDARD
        when {
            ic.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW_JPEG -> captureRaw(ic)
            ic.outputFormat == ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR -> scope.launch { runCatching { saveDirect(ic) } }
            ext != null -> {
                if (styled || mirror) multiFrame(ic, 1, MergeKind.FUSION, mirror, label = extLabel(ext), toneMap = false)
                else scope.launch {
                    if (ext == ExtensionMode.NIGHT || ext == ExtensionMode.BOKEH) { capturing = true; captureLabel = extLabel(ext) }
                    runCatching { saveDirect(ic) }.onFailure { toast = "Capture failed" }
                    capturing = false; captureLabel = null
                }
            }
            mode == Mode.NIGHT -> multiFrame(ic, nightFrames(), MergeKind.NIGHT, mirror, label = "Hold still")
            mode == Mode.PORTRAIT -> multiFrame(ic, if (liveIso >= 800) 3 else 1, MergeKind.PORTRAIT, mirror, label = null)
            mode == Mode.PHOTO && !settings.highRes && (settings.fusion == 2 || (settings.fusion == 1 && liveIso >= 400)) ->
                multiFrame(ic, fusionFrames(), MergeKind.FUSION, mirror, label = null)
            mode == Mode.PHOTO && (styled || mirror) -> multiFrame(ic, 1, MergeKind.FUSION, mirror, label = null, toneMap = false)
            else -> scope.launch { runCatching { saveDirect(ic) }.onFailure { toast = "Capture failed" } }
        }
    }

    private fun extLabel(ext: Int) = when (ext) {
        ExtensionMode.NIGHT -> "Hold still"
        ExtensionMode.BOKEH -> "Portrait"
        else -> null
    }

    private fun nightFrames() = when {
        liveIso >= 3200 || liveExposureNs >= 100_000_000L -> 12
        liveIso >= 1600 -> 10
        liveIso >= 800 -> 8
        else -> 6
    }

    private fun fusionFrames() = when {
        liveIso >= 1600 -> 6
        liveIso >= 800 -> 5
        liveIso >= 400 -> 4
        else -> 3
    }

    private fun shutterFx() {
        shutterBlink++
        if (settings.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        haptic()
    }

    private suspend fun saveDirect(ic: ImageCapture): Uri = suspendCancellableCoroutine { cont ->
        shutterFx()
        ic.takePicture(MediaSaver.jpegOutputOptions(activity), mainExec, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val uri = output.savedUri
                if (uri != null) { onSaved(uri, "image/jpeg"); if (cont.isActive) cont.resume(uri) }
                else if (cont.isActive) cont.resumeWithException(IllegalStateException("no uri"))
            }
            override fun onError(exception: ImageCaptureException) {
                if (cont.isActive) cont.resumeWithException(exception)
            }
        })
    }

    private fun captureRaw(ic: ImageCapture) {
        shutterFx()
        capturing = true
        captureLabel = "RAW"
        ic.takePicture(MediaSaver.rawOutputOptions(activity), MediaSaver.jpegOutputOptions(activity), mainExec,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false; captureLabel = null
                    output.savedUri?.let { if (output.imageFormat != android.graphics.ImageFormat.RAW_SENSOR) onSaved(it, "image/jpeg") }
                }
                override fun onError(exception: ImageCaptureException) {
                    capturing = false; captureLabel = null
                    toast = "RAW capture failed"
                }
            })
    }

    private suspend fun captureFrame(ic: ImageCapture): Frame = suspendCancellableCoroutine { cont ->
        ic.takePicture(camExec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val f = runCatching { image.toFrame() }
                image.close()
                f.fold({ if (cont.isActive) cont.resume(it) }, { if (cont.isActive) cont.resumeWithException(it) })
            }
            override fun onError(exception: ImageCaptureException) {
                if (cont.isActive) cont.resumeWithException(exception)
            }
        })
    }

    private fun multiFrame(ic: ImageCapture, count: Int, kind: MergeKind, mirror: Boolean, label: String?, toneMap: Boolean = true) {
        capturing = true
        captureProgress = 0f
        captureLabel = label
        val iso = liveIso
        val styleNow = style
        val aperture = this.aperture
        val usePortraitAi = kind == MergeKind.PORTRAIT && portraitAi.ready
        scope.launch {
            val frames = mutableListOf<Frame>()
            try {
                shutterFx()
                repeat(count) { i ->
                    frames += captureFrame(ic)
                    captureProgress = (i + 1f) / count
                }
            } catch (e: Exception) {
                Log.w(tag, "burst interrupted", e)
            }
            capturing = false
            captureProgress = 0f
            captureLabel = null
            if (frames.isEmpty()) { toast = "Capture failed"; return@launch }
            if (kind == MergeKind.PORTRAIT && !usePortraitAi) toast = "AI model not ready yet, saved without depth effect"
            processing++
            try {
                val saved = withContext(Dispatchers.Default) {
                    var bmp = if (toneMap) Processing.process(frames, kind, iso, mirror)
                    else Processing.orient(Processing.decode(frames[0]), frames[0].rotation, mirror)
                    if (usePortraitAi) {
                        val depth = runCatching { portraitAi.apply(bmp, aperture) }.getOrNull()
                        if (depth != null) { bmp.recycle(); bmp = depth }
                        else withContext(Dispatchers.Main) { toast = "No subject found, saved without depth effect" }
                    }
                    bmp = styleNow.apply(bmp)
                    val note = when (kind) {
                        MergeKind.NIGHT -> "Night ${frames.size}x"
                        MergeKind.PORTRAIT -> "Portrait f/${"%.1f".format(aperture)}"
                        MergeKind.FUSION -> if (frames.size > 1) "Fusion ${frames.size}x" else "Single"
                    }
                    val uri = MediaSaver.saveJpeg(activity, bmp, frames[0].jpeg, note)
                    val thumb = Bitmap.createScaledBitmap(bmp, 240, (240f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
                    bmp.recycle()
                    uri?.let { Triple(it, thumb, Unit) }
                }
                if (saved != null) {
                    lastUri = saved.first to "image/jpeg"
                    thumbnail = saved.second
                } else toast = "Couldn't save photo"
            } catch (e: Throwable) {
                Log.e(tag, "processing failed", e)
                toast = if (e is OutOfMemoryError) "Out of memory, try fewer frames" else "Processing failed"
            } finally {
                processing--
            }
        }
    }

    // ============================================================== video

    private var timelapseFile: File? = null

    private fun toggleRecording() {
        recording?.let { it.stop(); recording = null; return }
        val vc = videoCapture ?: return
        vc.targetRotation = surfaceRotation()
        val pending = if (mode == Mode.TIMELAPSE) {
            val f = File(activity.cacheDir, "timelapse_${System.currentTimeMillis()}.mp4")
            timelapseFile = f
            vc.output.prepareRecording(activity, FileOutputOptions.Builder(f).build())
        } else {
            vc.output.prepareRecording(activity,
                MediaStoreOutputOptions.Builder(activity.contentResolver, android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(MediaSaver.videoValues(if (mode == Mode.SLOMO) "SLOMO" else "VID")).build())
        }
        if (mode == Mode.VIDEO &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) pending.withAudioEnabled()
        haptic()
        if (settings.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
        if (settings.flash == 1 && hasFlash) camera?.cameraControl?.enableTorch(true)
        val recMode = mode
        recording = pending.start(mainExec) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> isRecording = true
                is VideoRecordEvent.Status -> recordNanos = ev.recordingStats.recordedDurationNanos
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    recordNanos = 0
                    if (settings.shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                    val okFile = ev.error == VideoRecordEvent.Finalize.ERROR_NONE ||
                        ev.outputResults.outputUri != Uri.EMPTY
                    if (!okFile) { toast = "Recording failed"; return@start }
                    if (recMode == Mode.TIMELAPSE) timelapseFile?.let { speedUp(it) }
                    else onSaved(ev.outputResults.outputUri, "video/mp4")
                }
                else -> {}
            }
        }
    }

    private fun speedUp(src: File) {
        processing++
        val speed = settings.timelapseSpeed.toFloat()
        val out = File(activity.cacheDir, "timelapse_out_${System.currentTimeMillis()}.mp4")
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src)))
            .setRemoveAudio(true)
            .setEffects(Effects(listOf(), listOf(SpeedChangeEffect(speed), FrameDropEffect.createDefaultFrameDropEffect(30f))))
            .build()
        Transformer.Builder(activity)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    scope.launch {
                        val uri = withContext(Dispatchers.IO) { MediaSaver.copyVideoToGallery(activity, out, "TIMELAPSE") }
                        src.delete(); out.delete()
                        processing--
                        if (uri != null) onSaved(uri, "video/mp4") else toast = "Couldn't save time-lapse"
                    }
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    Log.e(tag, "timelapse export failed", exportException)
                    processing--
                    // Keep the footage: save it at normal speed instead.
                    scope.launch {
                        val uri = withContext(Dispatchers.IO) { MediaSaver.copyVideoToGallery(activity, src, "VID") }
                        src.delete(); out.delete()
                        toast = "Time-lapse speed-up failed, saved normal video"
                        uri?.let { onSaved(it, "video/mp4") }
                    }
                }
            })
            .build()
            .start(item, out.absolutePath)
    }

    // ============================================================== gallery

    private fun onSaved(uri: Uri, mime: String) {
        lastUri = uri to mime
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { activity.contentResolver.loadThumbnail(uri, Size(320, 320), null) }.getOrNull()
            }
            if (bmp != null) thumbnail = bmp
        }
    }

    private fun refreshLastThumbnail() {
        scope.launch {
            val latest = withContext(Dispatchers.IO) { MediaSaver.latest(activity) } ?: return@launch
            lastUri = latest
            val bmp = withContext(Dispatchers.IO) {
                runCatching { activity.contentResolver.loadThumbnail(latest.first, Size(320, 320), null) }.getOrNull()
            }
            if (bmp != null && thumbnail == null) thumbnail = bmp
        }
    }

    fun openLast() {
        val (uri, mime) = lastUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { activity.startActivity(intent) }.onFailure { toast = "No gallery app found" }
    }

    /** Static sample state for UI screenshot tests (no camera attached). */
    internal fun demoState(m: Mode = Mode.PHOTO) {
        mode = m
        lensOptions = listOf(LensOption("0", 0.6f, 0.6f), LensOption("0", 1f, 1f), LensOption("0", 2f, 2f), LensOption("2", 1f, 3f))
        activeCameraId = "0"; zoomRatio = 1f; minZoom = 0.6f; maxZoom = 10f
        evMin = -2f; evMax = 2f
        liveIso = 125; liveExposureNs = 8_000_000L
        isoValues = listOf(50, 100, 200, 400, 800, 1600, 3200); shutterValues = listOf(1_000_000L, 4_000_000L, 16_000_000L)
        histogram = FloatArray(64) { i -> (kotlin.math.sin(i / 10.0) * 0.4 + 0.5).toFloat() }
    }

    fun haptic() {
        if (settings.haptics) previewView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }
}
