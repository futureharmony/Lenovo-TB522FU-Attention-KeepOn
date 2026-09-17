package futureharmony.tb522fu.aon

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.attention.AttentionService
import android.util.Size

/** YUV format constant kept local to avoid pulling android.graphics.ImageFormat typo risk. */
private const val YUV_420_888 = 0x23 // ImageFormat.YUV_420_888

/**
 * Camera2 fallback backend (reimplementation of the original v1 behavior):
 * scans for a front camera with face detection, streams a small YUV preview and
 * reads STATISTICS_FACES; deadline + recent-face grace decide the result.
 */
class Camera2Backend(
    private val context: Context,
    private val config: AonConfig,
) : DetectionBackend {

    private val handler: Handler

    private var cameraId: String? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var active: DetectionBackend.CheckCallback? = null
    private var startedAt = 0L
    private var lastFaceAt = 0L
    private var finishedFrames = 0

    @Volatile private var running = false

    init {
        val thread = HandlerThread("cameraThread")
        thread.start()
        handler = Handler(thread.looper)
    }

    override fun name() = "camera2"

    override fun isHealthy() = true

    override fun start() {
        running = true
        AonLog.i(TAG, "backend started (on-demand checks)")
    }

    override fun stop() {
        running = false
        handler.post { closeCamera() }
    }

    /** Find front camera, prioritizing low-power Camera 3 (OG0VE) before Camera 1. */
    private fun selectFrontCamera(): String? {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        return try {
            for (candidate in arrayOf("1")) {
                try {
                    val ch = cm.getCameraCharacteristics(candidate)
                    val facing = ch.get(CameraCharacteristics.LENS_FACING)
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        val modes = ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
                        if (modes != null && modes.size >= 2) {
                            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            if (map != null) {
                                for (s in map.getOutputSizes(YUV_420_888)) {
                                    if (s.width == 640 && s.height == 480) {
                                        AonLog.i(TAG, "selectFrontCamera selected Camera $candidate" +
                                            " (HW level=" +
                                            ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) + ")")
                                        return candidate
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    AonLog.w(TAG, "Error inspecting camera $candidate: $e")
                }
            }
            null
        } catch (e: Exception) {
            AonLog.e(TAG, "selectFrontCamera failed", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun openAndCheck(cb: DetectionBackend.CheckCallback) {
        val id = selectFrontCamera()
        if (id == null) {
            AonLog.w(TAG, "no front camera with face detection was found")
            cb.onError(AttentionService.ATTENTION_FAILURE_CAMERA_PERMISSION_ABSENT, "no capable front camera")
            return
        }
        cameraId = id
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        try {
            AonLog.i(TAG, "opening front camera $id")
            cm?.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    createSession(cb)
                }

                override fun onDisconnected(d: CameraDevice) {
                    AonLog.w(TAG, "camera disconnected")
                    closeCamera()
                    failIfActive(AttentionService.ATTENTION_FAILURE_UNKNOWN, "camera disconnected")
                }

                override fun onError(d: CameraDevice, error: Int) {
                    AonLog.e(TAG, "front camera error=$error")
                    closeCamera()
                    failIfActive(AttentionService.ATTENTION_FAILURE_UNKNOWN, "camera error $error")
                }
            }, handler)
        } catch (e: SecurityException) {
            AonLog.e(TAG, "camera permission rejected")
            cb.onError(AttentionService.ATTENTION_FAILURE_CAMERA_PERMISSION_ABSENT, "permission")
        } catch (e: Exception) {
            AonLog.e(TAG, "unable to open the front camera", e)
            cb.onError(AttentionService.ATTENTION_FAILURE_UNKNOWN, "open failed")
        }
    }

    private fun createSession(cb: DetectionBackend.CheckCallback) {
        try {
            val dev = device ?: return
            val r = ImageReader.newInstance(640, 480, YUV_420_888, 4)
            reader = r
            r.setOnImageAvailableListener({ reader ->
                try {
                    reader.acquireLatestImage()?.use {
                        // frames are drained only to pace the session; faces come from results
                    }
                } catch (_: Exception) {
                }
            }, handler)

            val surfaces = ArrayList<Any>()
            surfaces.add(r.surface)
            @Suppress("UNCHECKED_CAST")
            dev.createCaptureSession(surfaces as List<android.view.Surface>,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        startRepeating(cb)
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        AonLog.e(TAG, "front camera session configuration failed")
                        closeCamera()
                        failIfActive(AttentionService.ATTENTION_FAILURE_UNKNOWN, "session configure failed")
                    }
                }, handler)
        } catch (e: Exception) {
            AonLog.e(TAG, "failed to start attention capture", e)
            closeCamera()
            failIfActive(AttentionService.ATTENTION_FAILURE_UNKNOWN, "session create failed")
        }
    }

    private fun faceMode(): Int {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val ch = cm?.getCameraCharacteristics(cameraId!!)
            val modes = ch?.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
            if (modes != null) {
                for (m in modes) {
                    if (m == CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL) return m
                }
                for (m in modes) {
                    if (m == CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE) return m
                }
            }
        } catch (_: Exception) {
        }
        return CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
    }

    private fun startRepeating(cb: DetectionBackend.CheckCallback) {
        try {
            val dev = device ?: return
            val ses = session ?: return
            val r = reader ?: return
            active = cb
            startedAt = SystemClock.uptimeMillis()
            lastFaceAt = 0
            finishedFrames = 0
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(r.surface)
            b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceMode())
            ses.setRepeatingRequest(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    handleResult(result)
                }

                override fun onCaptureFailed(
                    s: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    AonLog.w(TAG, "capture failed reason=${failure.reason}")
                }
            }, handler)
            handler.postDelayed({ deadlineCheck() }, config.camera2TimeoutMs.toLong())
        } catch (e: Exception) {
            AonLog.e(TAG, "setRepeatingRequest failed", e)
            closeCamera()
            failIfActive(AttentionService.ATTENTION_FAILURE_UNKNOWN, "repeating request failed")
        }
    }

    private fun handleResult(result: TotalCaptureResult) {
        if (active == null) return
        finishedFrames++
        val faces = result.get(CaptureResult.STATISTICS_FACES)
        val n = faces?.size ?: 0
        if (n > 0) lastFaceAt = SystemClock.uptimeMillis()
        AonLog.d(TAG, "frames=$finishedFrames faces=$n")
        if (n > 0) finish(true, "face present ($n)")
    }

    private fun deadlineCheck() {
        if (active == null) return
        val now = SystemClock.uptimeMillis()
        val sinceFace = if (lastFaceAt == 0L) -1 else now - lastFaceAt
        if (lastFaceAt > 0 && sinceFace < 2000) {
            AonLog.i(TAG, "using recent-face grace after transient miss; sinceLastFaceMs=$sinceFace")
            finish(true, "recent-face grace")
        } else {
            AonLog.i(TAG, "deadline reached without a face; applying no-face policy" +
                (if (sinceFace >= 0) " sinceLastFaceMs=$sinceFace" else ""))
            finish(false, "no face within deadline")
        }
    }

    @Synchronized
    private fun finish(present: Boolean, reason: String) {
        val cb = active ?: return
        active = null
        closeCamera()
        AonLog.i(TAG, "check finished present=$present elapsedMs=" +
            (SystemClock.uptimeMillis() - startedAt) + " reason=$reason")
        if (present) cb.onResult(AttentionService.ATTENTION_SUCCESS_PRESENT, reason)
        else cb.onResult(AttentionService.ATTENTION_SUCCESS_ABSENT, reason)
    }

    @Synchronized
    private fun failIfActive(failureCode: Int, reason: String) {
        val cb = active ?: return
        active = null
        cb.onError(failureCode, reason)
    }

    private fun closeCamera() {
        try {
            session?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            session?.abortCaptures()
        } catch (_: Exception) {
        }
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        try {
            device?.close()
        } catch (_: Exception) {
        }
        session = null
        reader = null
        device = null
    }

    override fun check(cb: DetectionBackend.CheckCallback, timeoutMs: Int) {
        if (!running) start()
        handler.post {
            if (active != null) {
                AonLog.w(TAG, "check re-entered; failing previous")
                failIfActive(AttentionService.ATTENTION_FAILURE_UNKNOWN, "superseded")
            }
            openAndCheck(cb)
        }
    }

    companion object {
        private const val TAG = "CAM2"
    }
}
