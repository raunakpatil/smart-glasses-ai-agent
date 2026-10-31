package com.ronie.app.glasses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.photo.Photo
import com.meta.wearable.dat.camera.photo.types.PhotoQuality
import com.meta.wearable.dat.camera.photo.types.PhotoResolution
import com.meta.wearable.dat.camera.photo.types.PhotoState
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.DonState
import com.meta.wearable.dat.core.types.HingeState
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.ronie.app.voice.Diag
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** [needsGlassesUpdate]: the glasses' software must be updated in the Meta AI app before apps can use the camera. */
class CameraException(message: String, val needsGlassesUpdate: Boolean = false) : Exception(message)

/** What the user should hear or read for each reason the glasses end a session. */
private fun explain(error: DeviceSessionError?): CameraException {
    val deviceId = Wearables.devices.value.firstOrNull()
    val meta = deviceId?.let { Wearables.devicesMetadata[it]?.value }
    return when (error) {
        DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED, DeviceSessionError.INSUFFICIENT_SDK_VERSION ->
            CameraException("The glasses need a software update before Ronie can use the camera. Tap Update glasses.", needsGlassesUpdate = true)
        DeviceSessionError.CAPABILITY_DENIED ->
            CameraException("Camera access isn't allowed in Meta AI app. Open Devices in Ronie and tap Allow camera.")
        DeviceSessionError.NO_ELIGIBLE_DEVICE -> when {
            Wearables.registrationState.value != RegistrationState.REGISTERED ->
                CameraException("Ronie isn't connected to the Meta AI app. Open Devices and tap Connect glasses.")
            Wearables.devices.value.isEmpty() ->
                CameraException("No Ray-Ban Meta glasses detected in Meta AI app. Put them on and check Meta AI.")
            meta?.hingeState == HingeState.CLOSED ->
                CameraException("The glasses' hinges are closed. Open both temples to turn them on.")
            meta?.donState == DonState.DOFFED ->
                CameraException("Put the glasses on your face and make sure they are turned on.")
            meta?.linkState != LinkState.CONNECTED ->
                CameraException("The glasses' Bluetooth link is reconnecting. Put them on and wait a moment.")
            else ->
                CameraException("The glasses aren't ready. Put them on, open both temples, and check Bluetooth.")
        }
        DeviceSessionError.DEVICE_DISCONNECTED ->
            CameraException("The glasses disconnected. Put them on and check Bluetooth.")
        DeviceSessionError.THERMAL_CRITICAL, DeviceSessionError.THERMAL_EMERGENCY ->
            CameraException("The glasses are too warm to use the camera right now.")
        DeviceSessionError.BATTERY_CRITICAL, DeviceSessionError.PEAK_POWER_SHUTDOWN ->
            CameraException("The glasses' battery is too low for the camera.")
        DeviceSessionError.SESSION_ALREADY_EXISTS ->
            CameraException("Another app is using the glasses' camera.")
        DeviceSessionError.SESSION_ENDED_BY_DEVICE ->
            CameraException(
                "The glasses ended the camera session. Their software may need an update: tap Update glasses.",
                needsGlassesUpdate = true,
            )
        null -> CameraException("The glasses ended the session.")
        else -> CameraException("The glasses ended the session (${error.description}).")
    }
}

/** The session, camera and photo capability that belong together. */
private class OpenCamera(
    val session: DeviceSession,
    val camera: Camera,
    val photo: Photo,
)

/**
 * Takes photos with the glasses' camera through Meta's Wearables SDK. The user first connects Ronie
 * to the Meta AI app (registration) and allows camera access; both are buttons on the Devices screen.
 *
 * Supports fast periodic capture (taking a picture every 3.5 seconds into a live memory cache),
 * allowing Ronie to answer "what am I looking at?" in milliseconds instead of waiting for a 4s camera cycle.
 *
 * Meta's SDK needs BLUETOOTH_SCAN to keep seeing the glasses. Without it the first session can work
 * and every later one fails with "The glasses aren't connected" while the link stays DISCONNECTED.
 * Starting a camera session also fights the glasses' HFP/SCO call link, so [pauseCallAudio] lets the
 * voice controller drop it for the handshake and bring it back afterwards.
 */
object GlassesCamera {
    val registration: StateFlow<RegistrationState> get() = Wearables.registrationState

    suspend fun cameraAllowed(): Boolean =
        Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull() is PermissionStatus.Granted

    private lateinit var appContext: Context

    private val _nearbyGranted = MutableStateFlow(true)
    /** True when both BLUETOOTH_CONNECT and BLUETOOTH_SCAN are granted to Ronie. */
    val nearbyGranted: StateFlow<Boolean> = _nearbyGranted

    /** The voice controller installs these so a camera handshake can briefly drop the glasses' call link. */
    @Volatile
    var pauseCallAudio: (() -> Unit)? = null
    @Volatile
    var resumeCallAudio: (() -> Unit)? = null
    /** Told true while a camera session is open, so the voice route keeps the call link stable. */
    @Volatile
    var onCameraActive: ((Boolean) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile
    private var periodicJob: Job? = null
    @Volatile
    private var lastPeriodicJob: Job? = null
    @Volatile
    private var consecutiveFailures = 0

    /** Last EXIF orientation logged, so a fixed camera rotation is reported once instead of every frame. */
    @Volatile
    private var lastExifOrientation = Int.MIN_VALUE

    // A standalone photo keeps its session open briefly. The glasses can take ~10 s to accept a
    // new session after the previous one closes, so rapid follow-up shots reuse this one.
    @Volatile private var standby: OpenCamera? = null
    @Volatile private var standbyAt = 0L
    @Volatile private var standbyJob: Job? = null

    @Volatile
    var latestPhoto: ByteArray? = null
        private set

    @Volatile
    var latestPhotoAt: Long = 0L
        private set

    /** Recent frames, newest last. The oldest is dropped once [MAX_CACHED_PHOTOS] is reached. */
    private class CachedPhoto(val jpeg: ByteArray, val at: Long)
    private val cacheLock = Any()
    private val photoCache = ArrayDeque<CachedPhoto>()

    // Emits new photos to waiters (replay = 0 guarantees no stale historical photos are ever returned)
    private val _newPhotoFlow = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 1)
    val newPhotoFlow: SharedFlow<ByteArray> = _newPhotoFlow.asSharedFlow()

    // Holds the latest photo for UI previews (StateFlow)
    private val _latestPhotoFlow = MutableStateFlow<ByteArray?>(null)
    val latestPhotoFlow: StateFlow<ByteArray?> = _latestPhotoFlow

    private val _isPeriodicActive = MutableStateFlow(false)
    val isPeriodicActive: StateFlow<Boolean> = _isPeriodicActive

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private const val CALL_AUDIO_SETTLE_MS = 350L
    private const val LINK_WAIT_MS = 8_000L
    private const val SESSION_START_TIMEOUT_MS = 15_000L
    private const val CAMERA_START_TIMEOUT_MS = 8_000L
    private const val MAX_RETRY_BACKOFF_MS = 3_000L
    /** How long a standalone photo keeps its session open for a quick follow-up shot. */
    private const val STANDBY_IDLE_MS = 45_000L
    /** At most this many recent photos stay in memory; each new frame overwrites the oldest. */
    private const val MAX_CACHED_PHOTOS = 20

    fun init(context: Context) {
        appContext = context.applicationContext
        refreshPermissions()
    }

    fun refreshPermissions() {
        _nearbyGranted.value = nearbyPermissionsGranted()
    }

    private fun nearbyPermissionsGranted(): Boolean {
        if (!::appContext.isInitialized) return true
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    /** Adds a frame to the rolling cache, overwriting the oldest once 20 are stored. */
    private fun rememberPhoto(jpeg: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        synchronized(cacheLock) {
            photoCache.addLast(CachedPhoto(jpeg, now))
            while (photoCache.size > MAX_CACHED_PHOTOS) photoCache.removeFirst()
            latestPhoto = jpeg
            latestPhotoAt = now
        }
        _latestPhotoFlow.value = jpeg
        _newPhotoFlow.tryEmit(jpeg)
    }

    private fun newestPhoto(): CachedPhoto? = synchronized(cacheLock) { photoCache.lastOrNull() }

    /** A frame has been handed to a caller; the older frames are no longer needed. */
    private fun clearPhotoCache() = synchronized(cacheLock) {
        photoCache.clear()
        latestPhoto = null
        latestPhotoAt = 0L
    }

    /**
     * Start capturing pictures periodically (default every 3500 ms = 3.5 seconds) in the background.
     * Keeps an open DeviceSession and Photo capability to avoid the 2-3s session negotiation delay
     * on every shot.
     */
    fun startPeriodicCapture(intervalMs: Long = 3500L, maxSide: Int = 1024) {
        if (periodicJob?.isActive == true) return
        consecutiveFailures = 0
        _isPeriodicActive.value = true
        Diag.log("starting periodic camera capture (every ${intervalMs}ms)")
        val previous = lastPeriodicJob
        val job = scope.launch {
            val self = coroutineContext[Job]
            try {
                // The previous session may still be shutting down; a new session can't start until it has.
                previous?.join()
                while (isActive) {
                    if (registration.value != RegistrationState.REGISTERED) {
                        delay(2000L)
                        continue
                    }
                    if (!nearbyPermissionsGranted()) {
                        _lastError.value = "Nearby Devices permission needed"
                        Diag.log("periodic camera: Nearby Devices permission is not granted")
                        delay(5_000L)
                        continue
                    }

                    var open: OpenCamera? = null
                    try {
                        open = openCamera(maxWaitMs = 12_000L)
                        consecutiveFailures = 0
                        _lastError.value = null
                        Diag.log("glasses camera session active; capturing every ${intervalMs}ms")

                        while (isActive) {
                            try {
                                val jpeg = capture(open.photo)
                                val scaled = shrink(jpeg, maxSide)
                                rememberPhoto(scaled)
                                _lastError.value = null
                                consecutiveFailures = 0
                                Diag.log("fast camera: photo captured (${scaled.size / 1024} KB)")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                _lastError.value = e.message
                                Diag.log("periodic photo error: ${e.message}")
                                break // re-establish session on stream error
                            }
                            delay(intervalMs)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        consecutiveFailures++
                        _lastError.value = e.message
                        Diag.log("periodic camera session error ($consecutiveFailures): ${e.message}")
                        delay(minOf(MAX_RETRY_BACKOFF_MS, 1_000L * consecutiveFailures))
                    } finally {
                        open?.let { closeCamera(it) }
                    }
                }
            } finally {
                if (periodicJob === self) {
                    _isPeriodicActive.value = false
                    Diag.log("periodic camera capture stopped")
                }
            }
        }
        periodicJob = job
        lastPeriodicJob = job
    }

    /** Stop periodic background capture to save battery and release camera hardware. */
    fun stopPeriodicCapture() {
        val job = periodicJob ?: return
        if (job.isActive) Diag.log("periodic camera capture stopped")
        job.cancel()
        periodicJob = null
        _isPeriodicActive.value = false
        consecutiveFailures = 0
    }

    /**
     * Retrieves the most recent photo taken within [maxAgeMs] (default 3500ms).
     * Returns immediately (0ms) if a cached photo is ready, or waits for an in-flight
     * periodic capture, or falls back to a standalone capture.
     * Returns (jpegBytes, ageInMilliseconds).
     */
    suspend fun getLatestOrTakePhoto(
        maxAgeMs: Long = 3500L,
        waitInFlightMs: Long = 8_000L,
        maxSide: Int = 1024,
    ): Pair<ByteArray, Long> {
        val cached = newestPhoto()
        val age = cached?.let { SystemClock.elapsedRealtime() - it.at } ?: Long.MAX_VALUE
        if (cached != null && age <= maxAgeMs) {
            Diag.log("getLatestOrTakePhoto: using fresh cached photo (${age}ms old)")
            // Hand over the newest frame; older frames from the same moment aren't needed.
            clearPhotoCache()
            return cached.jpeg to age
        }

        // If periodic capture is running, wait for the next frame. A session that is still
        // reconnecting can take longer than the old 2.5 s, so give it up to 8 s before falling back.
        if (_isPeriodicActive.value && periodicJob?.isActive == true) {
            val next = withTimeoutOrNull(waitInFlightMs) {
                _newPhotoFlow.first()
            }
            if (next != null) {
                Diag.log("getLatestOrTakePhoto: received fresh in-flight periodic photo")
                clearPhotoCache()
                return next to 0L
            }
            Diag.log("getLatestOrTakePhoto: no periodic photo after ${waitInFlightMs}ms; trying standalone")
        }

        // Standalone fallback: clean capture directly
        Diag.log("getLatestOrTakePhoto: no fresh cached photo, capturing standalone photo")
        val photo = takePhoto(maxSide)
        return photo to 0L
    }

    /** One photo from the glasses, as a JPEG at most [maxSide] pixels on its longest side. */
    suspend fun takePhoto(maxSide: Int = 1024): ByteArray = withTimeout(45_000) {
        mutex.withLock {
            val wasPeriodic = _isPeriodicActive.value
            // If periodic capture is running, pause it and wait for its session to close
            val periodic = periodicJob
            if (periodic?.isActive == true) {
                Diag.log("takePhoto: temporarily pausing periodic capture for standalone shot")
                periodic.cancel()
                try { periodic.join() } catch (_: Exception) {}
                periodicJob = null
            }

            try {
                if (!nearbyPermissionsGranted()) {
                    throw CameraException(
                        "Ronie needs the Nearby Devices permission to find and reconnect to the glasses. " +
                            "Open Devices and tap Allow Nearby Devices."
                    )
                }
                if (registration.value != RegistrationState.REGISTERED) {
                    throw CameraException("Ronie isn't connected to the Meta AI app yet (Devices screen)")
                }

                val open = reusableOrOpenCamera()
                var keep = false
                try {
                    val jpeg = capture(open.photo)
                    val scaled = shrink(jpeg, maxSide)
                    rememberPhoto(scaled)
                    _lastError.value = null
                    keep = true
                    Diag.log("takePhoto: standalone photo captured (${scaled.size / 1024} KB)")
                    scaled
                } finally {
                    if (keep) keepStandby(open) else closeCamera(open)
                }
            } finally {
                // If periodic capture was active before standalone capture, resume it
                if (wasPeriodic) {
                    closeStandby()
                    startPeriodicCapture()
                }
            }
        }
    }

    /** Reuses the session left open by a recent standalone photo, or opens a new one. */
    private suspend fun reusableOrOpenCamera(): OpenCamera {
        val existing = standby
        if (existing != null &&
            SystemClock.elapsedRealtime() - standbyAt < STANDBY_IDLE_MS &&
            existing.session.state.value == DeviceSessionState.STARTED &&
            existing.photo.state.value == PhotoState.STARTED
        ) {
            Diag.log("takePhoto: reusing open camera session")
            standbyJob?.cancel()
            standbyJob = null
            return existing
        }
        closeStandby()
        return openCamera(maxWaitMs = 25_000L)
    }

    /** Keeps a successful standalone session open for [STANDBY_IDLE_MS]. */
    private fun keepStandby(open: OpenCamera) {
        standby = open
        standbyAt = SystemClock.elapsedRealtime()
        standbyJob?.cancel()
        standbyJob = scope.launch {
            delay(STANDBY_IDLE_MS)
            mutex.withLock {
                if (standby === open) {
                    standby = null
                    standbyJob = null
                    Diag.log("takePhoto: closing idle camera session")
                    closeCamera(open)
                }
            }
        }
    }

    /** Closes the standby session. The caller must hold [mutex]. */
    private suspend fun closeStandby() {
        val open = standby ?: return
        standby = null
        standbyJob?.cancel()
        standbyJob = null
        closeCamera(open)
    }

    private suspend fun openCamera(maxWaitMs: Long): OpenCamera {
        val deadline = SystemClock.elapsedRealtime() + maxWaitMs
        var attempt = 0
        var lastError: Exception? = null

        while (SystemClock.elapsedRealtime() < deadline) {
            attempt++
            val paused = pauseCallAudio
            paused?.invoke()
            try {
                // The call link (HFP/SCO) and the camera link share the glasses' Bluetooth
                // connection; give Android a moment to actually tear SCO down.
                if (paused != null) delay(CALL_AUDIO_SETTLE_MS)

                val remaining = deadline - SystemClock.elapsedRealtime()
                val deviceId = awaitConnectedDevice(minOf(LINK_WAIT_MS, maxOf(1_000L, remaining)))
                    ?: throw CameraException(unavailableMessage())

                val session = openSession(deviceId)
                try {
                    val camera = session.addCamera().getOrNull()
                        ?: throw CameraException("Couldn't use the glasses' camera. Is camera access allowed?")
                    val photo = camera.photo
                    val started = withTimeoutOrNull(CAMERA_START_TIMEOUT_MS) {
                        photo.state.first { it == PhotoState.STARTED }
                    }
                    if (started == null) {
                        try { camera.stop() } catch (_: Exception) {}
                        throw CameraException("The glasses' camera didn't start in time.")
                    }
                    Diag.log("camera: session started (attempt $attempt)")
                    return OpenCamera(session, camera, photo)
                } catch (e: Exception) {
                    stopSession(session)
                    throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Diag.log("camera: attempt $attempt failed: ${e.message}")
            } finally {
                resumeCallAudio?.invoke()
            }

            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining > 0) {
                delay(minOf(MAX_RETRY_BACKOFF_MS, 500L * attempt).coerceAtMost(remaining))
            }
        }

        throw lastError ?: CameraException(unavailableMessage())
    }

    /** Waits until a paired glasses device reports a connected link. */
    private suspend fun awaitConnectedDevice(timeoutMs: Long): DeviceIdentifier? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last: LinkState? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val deviceId = pickDevice()
            if (deviceId == null) {
                if (last != null) Diag.log("camera: no glasses paired; waiting")
                last = null
                delay(500L)
                continue
            }
            val meta = Wearables.devicesMetadata[deviceId]?.value
            if (meta?.hingeState == HingeState.CLOSED) {
                if (last != LinkState.DISCONNECTED) Diag.log("camera: glasses hinges closed; waiting for them to open")
                last = LinkState.DISCONNECTED
                delay(500L)
                continue
            }
            val link = meta?.linkState
            if (link == LinkState.CONNECTED) {
                if (last != LinkState.CONNECTED) Diag.log("camera: glasses link CONNECTED")
                return deviceId
            }
            if (link != last) {
                Diag.log("camera: glasses link ${link ?: "unknown"}; waiting for CONNECTED")
                last = link
            }
            delay(500L)
        }
        return null
    }

    /** The connected glasses if there are several; otherwise the first paired one. */
    private fun pickDevice(): DeviceIdentifier? {
        val devices = Wearables.devices.value
        return devices.firstOrNull { Wearables.devicesMetadata[it]?.value?.linkState == LinkState.CONNECTED }
            ?: devices.firstOrNull()
    }

    /** Creates and starts a session. Pin the device instead of AutoDeviceSelector: its "active device"
     *  needs a don-sensor signal and returns NO_ELIGIBLE_DEVICE when that signal hasn't arrived. */
    private suspend fun openSession(deviceId: DeviceIdentifier): DeviceSession {
        val created = Wearables.createSession(SpecificDeviceSelector(deviceId))
        val session = created.getOrNull() ?: throw explain(created.errorOrNull())

        var lastError: DeviceSessionError? = null
        val errors = scope.launch { session.errors.collect { lastError = it } }
        try {
            session.start()
            val state = withTimeoutOrNull(SESSION_START_TIMEOUT_MS) {
                session.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED }
            }
            when (state) {
                DeviceSessionState.STARTED -> return session
                DeviceSessionState.STOPPED -> throw explain(lastError)
                else -> throw CameraException("The glasses didn't connect in time. Put them on and check Bluetooth.")
            }
        } catch (e: Exception) {
            stopSession(session)
            throw e
        } finally {
            errors.cancel()
        }
    }

    private suspend fun stopSession(session: DeviceSession?) {
        if (session == null) return
        withContext(NonCancellable) {
            try { session.stop() } catch (_: Exception) {}
            withTimeoutOrNull(3_000L) {
                session.state.first { it == DeviceSessionState.STOPPED }
            }
        }
    }

    private suspend fun closeCamera(open: OpenCamera) {
        try { open.photo.stop() } catch (_: Exception) {}
        try { open.camera.stop() } catch (_: Exception) {}
        stopSession(open.session)
    }

    /** One photo from an already-open photo capability. */
    private suspend fun capture(photo: Photo): ByteArray = coroutineScope {
        val captured = async { photo.photoStream.first() }
        val failed = async { photo.errors.first() }
        photo.capturePhoto(PhotoResolution.MEDIUM, PhotoQuality.HIGH)
        select {
            captured.onAwait { failed.cancel(); it.imageData }
            failed.onAwait { captured.cancel(); throw CameraException("Photo failed: $it") }
        }
    }

    /** The most useful explanation for the current device state. */
    private fun unavailableMessage(): String {
        val meta = pickDevice()?.let { Wearables.devicesMetadata[it]?.value }
        return when {
            meta?.hingeState == HingeState.CLOSED ->
                "The glasses' hinges are closed. Open both temples to turn them on."
            meta?.donState == DonState.DOFFED ->
                "Put the glasses on your face and make sure they are turned on."
            meta?.linkState != LinkState.CONNECTED ->
                "The glasses aren't connected. Put them on, open both temples, and check Bluetooth."
            else ->
                "The glasses aren't ready. Put them on, open both temples, and check Bluetooth."
        }
    }

    /** Scale down for the AI model: smaller uploads, faster answers, same understanding. */
    private fun shrink(image: ByteArray, maxSide: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw CameraException("The glasses sent a photo Ronie couldn't read")

        // The glasses send the sensor image as-is and record the camera's rotation in EXIF.
        // BitmapFactory ignores that tag, so a photo taken in portrait arrives landscape and
        // sideways. Rotate it before scaling so the preview and the vision model see it upright.
        val orientation = exifOrientation(image)
        if (orientation != lastExifOrientation) {
            lastExifOrientation = orientation
            Diag.log("camera: photo ${bounds.outWidth}x${bounds.outHeight}, EXIF orientation $orientation")
        }
        val bitmap = rotateForExif(decoded, orientation)
        if (bitmap !== decoded) decoded.recycle()

        val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        val sized = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val out = ByteArrayOutputStream().also { sized.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
        if (sized !== bitmap) sized.recycle()
        bitmap.recycle()
        return out
    }

    private fun exifOrientation(image: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(image))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Turns the decoded bitmap upright according to its EXIF orientation tag. */
    private fun rotateForExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
