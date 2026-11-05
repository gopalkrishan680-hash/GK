package com.gk.procamera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.provider.MediaStore
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.concurrent.futures.await
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CamMode(val label: String, val extension: Int?) {
    NIGHT("NIGHT", ExtensionMode.NIGHT),
    PORTRAIT("PORTRAIT", ExtensionMode.BOKEH),
    PHOTO("PHOTO", null),
    HDR("HDR", ExtensionMode.HDR),
    PRO("PRO", null),
}

val WhiteBalanceModes = listOf(
    "Auto" to CaptureRequest.CONTROL_AWB_MODE_AUTO,
    "Sunny" to CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
    "Cloudy" to CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
    "Bulb" to CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
    "Tube" to CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
)

@OptIn(ExperimentalCamera2Interop::class)
class CameraEngine(private val context: Context) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var rotation = Surface.ROTATION_0
    private var zoomLive: LiveData<ZoomState>? = null
    private val zoomObserver = Observer<ZoomState> { z ->
        minZoom = z.minZoomRatio
        maxZoom = z.maxZoomRatio
        zoom = z.zoomRatio
    }

    var ready by mutableStateOf(false); private set
    var lensFacing by mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
    var mode by mutableStateOf(CamMode.PHOTO)
    var modes by mutableStateOf(listOf(CamMode.NIGHT, CamMode.PHOTO, CamMode.PRO)); private set
    var extensionActive by mutableStateOf(false); private set
    var flashMode by mutableIntStateOf(ImageCapture.FLASH_MODE_OFF); private set
    var hasFlash by mutableStateOf(false); private set
    var zoom by mutableFloatStateOf(1f); private set
    var minZoom by mutableFloatStateOf(1f); private set
    var maxZoom by mutableFloatStateOf(1f); private set
    var evIndex by mutableIntStateOf(0); private set
    var evRange by mutableStateOf(0..0); private set
    var evStep by mutableFloatStateOf(0f); private set

    // Pro mode
    var manualSensor by mutableStateOf(false); private set
    var isoRange by mutableStateOf<IntRange?>(null); private set
    var shutterRange by mutableStateOf<LongRange?>(null); private set
    var minFocusDist by mutableFloatStateOf(0f); private set
    var proIso by mutableIntStateOf(0)            // 0 = auto
    var proShutterNs by mutableLongStateOf(0L)    // 0 = auto
    var proFocus by mutableFloatStateOf(-1f)      // < 0 = autofocus, else diopters
    var proWb by mutableIntStateOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)

    suspend fun init() {
        val p = ProcessCameraProvider.getInstance(context).await()
        provider = p
        extensions = try {
            ExtensionsManager.getInstanceAsync(context, p).await()
        } catch (e: Exception) {
            null
        }
        val hasBack = try { p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) } catch (e: Exception) { false }
        if (!hasBack) lensFacing = CameraSelector.LENS_FACING_FRONT
        ready = true
    }

    private fun extAvailable(selector: CameraSelector, ext: Int): Boolean = try {
        extensions?.isExtensionAvailable(selector, ext) == true
    } catch (e: Exception) {
        false
    }

    fun bind(owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        val p = provider ?: return
        val base = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        modes = buildList {
            add(CamMode.NIGHT) // native extension or our multi-frame fallback
            if (extAvailable(base, ExtensionMode.BOKEH)) add(CamMode.PORTRAIT)
            add(CamMode.PHOTO)
            if (extAvailable(base, ExtensionMode.HDR)) add(CamMode.HDR)
            add(CamMode.PRO)
        }
        if (mode !in modes) mode = CamMode.PHOTO

        val ext = mode.extension
        val wantExt = ext != null && extAvailable(base, ext)
        val selector = if (wantExt) extensions!!.getExtensionEnabledCameraSelector(base, ext!!) else base

        val ratio = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder().setResolutionSelector(ratio).build()
        preview.setSurfaceProvider(surfaceProvider)
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(100)
            .setFlashMode(flashMode)
            .setResolutionSelector(ratio)
            .setTargetRotation(rotation)
            .build()

        zoomLive?.removeObserver(zoomObserver)
        p.unbindAll()
        val cam = try {
            p.bindToLifecycle(owner, selector, preview, capture).also { extensionActive = wantExt }
        } catch (e: Exception) {
            if (!wantExt) throw e
            p.unbindAll()
            p.bindToLifecycle(owner, base, preview, capture).also { extensionActive = false }
        }
        camera = cam
        imageCapture = capture

        val info = cam.cameraInfo
        hasFlash = info.hasFlashUnit()
        zoomLive = info.zoomState.also { it.observe(owner, zoomObserver) }
        val es = info.exposureState
        if (es.isExposureCompensationSupported) {
            evRange = es.exposureCompensationRange.lower..es.exposureCompensationRange.upper
            evStep = es.exposureCompensationStep.toFloat()
        } else {
            evRange = 0..0
            evStep = 0f
        }
        evIndex = es.exposureCompensationIndex

        try {
            val c2 = Camera2CameraInfo.from(info)
            val caps = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            manualSensor = !extensionActive &&
                caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
            isoRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?.let { it.lower..it.upper }
            shutterRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?.let { it.lower..it.upper }
            minFocusDist = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        } catch (e: Exception) {
            manualSensor = false
        }
        applyPro()
    }

    /** Pushes Pro-mode settings (ISO / shutter / focus / white balance) to the camera. */
    fun applyPro() {
        val cam = camera ?: return
        if (extensionActive) return
        try {
            val ctrl = Camera2CameraControl.from(cam.cameraControl)
            if (mode != CamMode.PRO) {
                ctrl.clearCaptureRequestOptions()
                return
            }
            val b = CaptureRequestOptions.Builder()
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, proWb)
            if (manualSensor && (proIso > 0 || proShutterNs > 0L)) {
                val iso = if (proIso > 0) proIso else isoRange?.let { 400.coerceIn(it.first, it.last) } ?: 400
                val sh = if (proShutterNs > 0L) proShutterNs
                else shutterRange?.let { 16_666_666L.coerceIn(it.first, it.last) } ?: 16_666_666L
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, sh)
            }
            if (manualSensor && proFocus >= 0f) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, proFocus)
            }
            ctrl.setCaptureRequestOptions(b.build())
        } catch (e: Exception) {
            // Some devices reject manual options; ignore and stay on auto.
        }
    }

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(minZoom, maxZoom))
    }

    fun setEv(index: Int) {
        val i = index.coerceIn(evRange.first, evRange.last)
        evIndex = i
        camera?.cameraControl?.setExposureCompensationIndex(i)
    }

    fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
    }

    fun setRotation(r: Int) {
        if (r == rotation) return
        rotation = r
        imageCapture?.targetRotation = r
    }

    fun focusAt(point: MeteringPoint) {
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    fun switchLens() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
        else CameraSelector.LENS_FACING_BACK
    }

    fun shutdown() {
        zoomLive?.removeObserver(zoomObserver)
        executor.shutdown()
    }

    // ---------------------------------------------------------------- capture

    private fun metadata() = ImageCapture.Metadata().apply {
        isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
    }

    /** Takes a photo, applies mode processing + filter, saves to gallery. Returns the gallery Uri. */
    suspend fun capture(filter: PhotoFilter, onProgress: (String?) -> Unit): Uri? {
        val ic = imageCapture ?: return null
        val meta = metadata()
        if (extensionActive) onProgress("Hold steady…")

        if (mode == CamMode.NIGHT && !extensionActive) return nightStack(ic, meta, filter, onProgress)

        val matrix = filter.matrix
        if (matrix == null) return ic.takeToGallery(meta)

        val tmp = File(context.cacheDir, "shot_${System.nanoTime()}.jpg")
        try {
            ic.takeToFile(tmp, meta)
            onProgress("Applying ${filter.name}…")
            return withContext(Dispatchers.Default) {
                val bmp = ImageProcessing.decodeUpright(tmp)
                ImageProcessing.saveBitmap(context, ImageProcessing.applyFilter(bmp, matrix))
            }
        } finally {
            tmp.delete()
        }
    }

    private suspend fun nightStack(
        ic: ImageCapture,
        meta: ImageCapture.Metadata,
        filter: PhotoFilter,
        onProgress: (String?) -> Unit,
    ): Uri {
        val dir = File(context.cacheDir, "night").apply { mkdirs() }
        val frames = mutableListOf<File>()
        try {
            val total = 6
            for (i in 0 until total) {
                onProgress("Night shot ${i + 1}/$total – hold steady")
                val f = File(dir, "f$i.jpg")
                ic.takeToFile(f, meta)
                frames += f
            }
            onProgress("Merging frames…")
            return withContext(Dispatchers.Default) {
                var bmp = ImageProcessing.stackFrames(frames)
                filter.matrix?.let { bmp = ImageProcessing.applyFilter(bmp, it) }
                ImageProcessing.saveBitmap(context, bmp)
            }
        } finally {
            frames.forEach { it.delete() }
        }
    }

    private suspend fun ImageCapture.takeToFile(file: File, meta: ImageCapture.Metadata) =
        suspendCancellableCoroutine<Unit> { cont ->
            val opts = ImageCapture.OutputFileOptions.Builder(file).setMetadata(meta).build()
            takePicture(opts, executor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            })
        }

    private suspend fun ImageCapture.takeToGallery(meta: ImageCapture.Metadata) =
        suspendCancellableCoroutine<Uri?> { cont ->
            val opts = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ImageProcessing.newImageValues(pending = false),
            ).setMetadata(meta).build()
            takePicture(opts, executor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (cont.isActive) cont.resume(outputFileResults.savedUri)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            })
        }
}
