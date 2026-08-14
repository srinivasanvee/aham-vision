package com.aham.vision

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
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

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.CAMERA] == true) startCamera()
        else binding.status.text = "Camera permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.preview.scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
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
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
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
            val plane = image.planes[0]
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind(); bitmap.copyPixelsFromBuffer(plane.buffer)
            val rotation = image.imageInfo.rotationDegrees.toFloat()
            val upright = if (rotation == 0f) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
            val started = SystemClock.elapsedRealtime()
            val detections = yolo.detect(upright)
            val elapsed = SystemClock.elapsedRealtime() - started
            runOnUiThread {
                binding.overlay.update(detections)
                if (recording == null) binding.status.text = "${detections.size} objects • ${elapsed}ms • offline"
            }
        } catch (e: Exception) {
            runOnUiThread { binding.status.text = "Inference error: ${e.message}" }
        } finally { image.close() }
    }

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
        recording?.stop(); detector?.close(); analysisExecutor.shutdown(); super.onDestroy()
    }
}
