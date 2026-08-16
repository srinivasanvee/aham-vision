package com.aham.vision

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.aham.vision.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var detector: YoloDetector? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lastAnalysis = 0L
    private var selectedTargets: Set<String> = emptySet()
    private var countTarget: String? = null
    private val countTracker = ObjectCountTracker()
    private val lastAlertAt = mutableMapOf<String, Long>()
    private var alertTone: ToneGenerator? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.CAMERA] == true) startCamera()
        else binding.status.text = "Camera permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        selectedTargets = intent.getStringArrayExtra(EXTRA_ALERT_TARGETS)?.map(String::lowercase)?.toSet().orEmpty()
        countTarget = intent.getStringExtra(EXTRA_COUNT_TARGET)?.lowercase()
        if (selectedTargets.isNotEmpty()) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            alertTone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        }
        if (countTarget != null) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding.objectCount.visibility = View.VISIBLE
        }
        // TextureView composition guarantees our detection overlay is drawn above the camera preview.
        binding.preview.implementationMode = androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
        binding.preview.scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
        binding.overlay.bringToFront()
        binding.status.bringToFront()
        binding.objectCount.bringToFront()
        binding.record.bringToFront()
        binding.record.setOnClickListener { toggleRecording() }
        analysisExecutor.execute {
            runCatching { YoloDetector(applicationContext) }
                .onSuccess { detector = it; runOnUiThread { binding.status.text = "Offline model ready" } }
                .onFailure { e -> runOnUiThread { binding.status.text = "Model error: ${e.message}" } }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = binding.preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { useCase -> useCase.setAnalyzer(analysisExecutor, ::analyze) }
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build()
            videoCapture = VideoCapture.withOutput(recorder)
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, videoCapture)
            }.onFailure { binding.status.text = "Camera error: ${it.message}" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val yolo = detector
        if (yolo == null || now - lastAnalysis < 90L) { image.close(); return }
        lastAnalysis = now
        try {
            val bitmap = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees.toFloat()
            val upright = if (rotation == 0f) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
            val started = SystemClock.elapsedRealtime()
            val detections = yolo.detect(upright)
            val elapsed = SystemClock.elapsedRealtime() - started
            val visibleDetections = when {
                countTarget != null -> ObjectCounter.matching(detections, countTarget.orEmpty())
                selectedTargets.isNotEmpty() -> detections.filter { it.label.lowercase() in selectedTargets }
                else -> detections
            }
            val stableCount = if (countTarget != null) countTracker.update(visibleDetections.size) else null
            val alertDetection = if (selectedTargets.isNotEmpty()) nextAlert(visibleDetections) else null
            val savedPhoto = alertDetection?.let { saveAlertPhoto(upright, visibleDetections) }
            runOnUiThread {
                binding.overlay.update(visibleDetections, upright.width, upright.height)
                if (recording == null) {
                    binding.status.text = when {
                        countTarget != null -> "Counting ${countDisplayName()} • ${elapsed}ms • offline"
                        selectedTargets.isNotEmpty() -> "Watching ${targetNames()} • ${visibleDetections.size} nearby • ${elapsed}ms"
                        else -> "${detections.size} objects • ${elapsed}ms • offline"
                    }
                }
                stableCount?.let { binding.objectCount.text = it.toString() }
                alertDetection?.let { playAlert(it, savedPhoto) }
            }
        } catch (e: Exception) {
            runOnUiThread { binding.status.text = "Inference error: ${e.message}" }
        } finally { image.close() }
    }

    private fun targetNames(): String = selectedTargets.joinToString(", ") { if (it == "person") "human" else it }

    private fun countDisplayName(): String = when (countTarget) {
        "sports ball" -> "ping-pong / sports balls"
        "person" -> "people"
        else -> countTarget.orEmpty()
    }

    private fun nextAlert(detections: List<Detection>): Detection? {
        val now = SystemClock.elapsedRealtime()
        val due = detections.firstOrNull { now - (lastAlertAt[it.label.lowercase()] ?: 0L) >= ALERT_COOLDOWN_MS } ?: return null
        lastAlertAt[due.label.lowercase()] = now
        return due
    }

    private fun playAlert(detection: Detection, savedPhoto: String?) {
        alertTone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
        val target = if (detection.label == "person") "Human" else detection.label.replaceFirstChar(Char::uppercase)
        val saved = savedPhoto?.let { " • saved $it" }.orEmpty()
        binding.status.text = "⚠ $target detected • ${(detection.score * 100).toInt()}%$saved"
    }

    private fun saveAlertPhoto(source: Bitmap, detections: List<Detection>): String? = runCatching {
        val annotated = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)
        val stroke = (annotated.width / 180f).coerceAtLeast(5f)
        val textSize = (annotated.width / 24f).coerceAtLeast(28f)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = stroke }
        val fillPaint = Paint().apply { color = Color.argb(235, 0, 0, 0); style = Paint.Style.FILL }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; this.textSize = textSize; isFakeBoldText = true }
        detections.forEach { detection ->
            val left = detection.left * annotated.width
            val top = detection.top * annotated.height
            val right = detection.right * annotated.width
            val bottom = detection.bottom * annotated.height
            canvas.drawRect(left, top, right, bottom, boxPaint)
            val label = "${if (detection.label == "person") "Human" else detection.label.replaceFirstChar(Char::uppercase)} ${(detection.score * 100).toInt()}%"
            val labelHeight = textSize * 1.35f
            val labelTop = (top - labelHeight).coerceAtLeast(0f)
            canvas.drawRect(left, labelTop, left + textPaint.measureText(label) + stroke * 3, labelTop + labelHeight, fillPaint)
            canvas.drawText(label, left + stroke, labelTop + textSize, textPaint)
        }
        val fileName = "ride_alert_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())}.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/aham-vision/Ride Alerts")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create photo")
            contentResolver.openOutputStream(uri)?.use { annotated.compress(Bitmap.CompressFormat.JPEG, 94, it) }
                ?: error("Could not write photo")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } else {
            val folder = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Ride Alerts").apply { mkdirs() }
            val file = File(folder, fileName)
            file.outputStream().use { annotated.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
        }
        annotated.recycle()
        fileName
    }.getOrNull()

    private fun toggleRecording() {
        recording?.let { it.stop(); recording = null; binding.record.text = "Record"; return }
        val capture = videoCapture ?: return
        val folder = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val name = "aham_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.mp4"
        var pending = capture.output.prepareRecording(this, FileOutputOptions.Builder(File(folder, name)).build())
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> { binding.record.text = "Stop"; binding.status.text = "● Recording + detecting" }
                is VideoRecordEvent.Finalize -> {
                    recording = null; binding.record.text = "Record"
                    binding.status.text = if (event.hasError()) "Recording failed (${event.error})" else "Saved ${name}"
                }
            }
        }
    }

    override fun onDestroy() {
        recording?.stop(); detector?.close(); alertTone?.release(); analysisExecutor.shutdown(); super.onDestroy()
    }

    companion object {
        const val EXTRA_ALERT_TARGETS = "alert_targets"
        const val EXTRA_COUNT_TARGET = "count_target"
        private const val ALERT_COOLDOWN_MS = 4_000L
    }
}
