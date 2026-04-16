/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.session.Session
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@SuppressLint("AutoCloseableUse")
class StreamViewModel(
  application: Application,
  private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "CameraAccess:StreamViewModel"
    private val INITIAL_STATE = StreamUiState()
    private val SESSION_TERMINAL_STATES = setOf(StreamSessionState.CLOSED)
    private const val DETECTION_INTERVAL_FRAMES = 10 // Analyse 1 frame sur 10
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var session: Session? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  // TFLite
  private var objectDetector: ObjectDetector? = null
  private var frameCount = 0
  private val _detectionResult = MutableStateFlow<String>("")
  val detectionResult: StateFlow<String> = _detectionResult.asStateFlow()

  private var videoJob: Job? = null
  private var stateJob: Job? = null
  private var errorJob: Job? = null
  private var sessionStateJob: Job? = null
  private var stream: Stream? = null
  private var presentationQueue: PresentationQueue? = null

  init {
    setupDetector()
  }

  private fun setupDetector() {
    try {
      val options = ObjectDetector.ObjectDetectorOptions.builder()
        .setMaxResults(3)
        .setScoreThreshold(0.3f)
        .build()
      objectDetector = ObjectDetector.createFromFileAndOptions(
        getApplication(),
        "efficientdet_lite0.tflite",
        options
      )
      Log.d(TAG, "TFLite detector loaded!")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load TFLite model: ${e.message}")
    }
  }

  private fun analyzeFrame(bitmap: Bitmap) {
    frameCount++
    if (frameCount % DETECTION_INTERVAL_FRAMES != 0) return

    try {
      val tensorImage = TensorImage.fromBitmap(bitmap)
      val results = objectDetector?.detect(tensorImage)
      val text = if (results.isNullOrEmpty()) {
        "Aucun objet détecté"
      } else {
        results.take(3).joinToString(" | ") { detection ->
          val label = detection.categories.firstOrNull()?.label ?: "?"
          val score = detection.categories.firstOrNull()?.score ?: 0f
          "$label ${"%.0f".format(score * 100)}%"
        }
      }
      _detectionResult.update { text }
      Log.d(TAG, "Detection: $text")
    } catch (e: Exception) {
      Log.e(TAG, "Detection error: ${e.message}")
    }
  }

  fun startStream() {
    videoJob?.cancel()
    stateJob?.cancel()
    errorJob?.cancel()
    sessionStateJob?.cancel()
    presentationQueue?.stop()
    presentationQueue = null

    val queue = PresentationQueue(
      bufferDelayMs = 100L,
      maxQueueSize = 15,
      onFrameReady = { frame ->
        _uiState.update {
          it.copy(videoFrame = frame.bitmap, videoFrameCount = it.videoFrameCount + 1)
        }
        // Analyse TFLite sur le frame
        analyzeFrame(frame.bitmap)
      },
    )
    presentationQueue = queue
    queue.start()

    if (session == null) {
      Wearables.createSession(deviceSelector)
        .onSuccess { createdSession ->
          session = createdSession
          session?.start()
        }
        .onFailure { error, _ -> Log.e(TAG, "Failed to create session: ${error.description}") }
      if (session == null) return
    }
    startStreamInternal()
  }

  private fun startStreamInternal() {
    sessionStateJob = viewModelScope.launch {
      session?.state?.collect { currentState ->
        if (currentState == DeviceSessionState.STARTED) {
          videoJob?.cancel()
          stateJob?.cancel()
          errorJob?.cancel()
          stream?.stop()
          stream = null
          session?.addStream(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24))
            ?.onSuccess { addedStream ->
              stream = addedStream
              videoJob = viewModelScope.launch {
                stream?.videoStream?.collect { handleVideoFrame(it) }
              }
              stateJob = viewModelScope.launch {
                stream?.state?.collect { currentState ->
                  val prevState = _uiState.value.streamSessionState
                  _uiState.update { it.copy(streamSessionState = currentState) }
                  val wasActive = prevState !in SESSION_TERMINAL_STATES
                  val isTerminated = currentState in SESSION_TERMINAL_STATES
                  if (wasActive && isTerminated) {
                    stopStream()
                    wearablesViewModel.navigateToDeviceSelection()
                  }
                }
              }
              errorJob = viewModelScope.launch {
                stream?.errorStream?.collect { error ->
                  if (error == StreamError.HINGE_CLOSED) {
                    stopStream()
                    wearablesViewModel.navigateToDeviceSelection()
                  }
                }
              }
              stream?.start()
            }
            ?.onFailure { error, _ ->
              Log.e(TAG, "Failed to add stream: ${error.description}")
            }
        }
      }
    }
  }

  fun stopStream() {
    videoJob?.cancel(); videoJob = null
    stateJob?.cancel(); stateJob = null
    errorJob?.cancel(); errorJob = null
    sessionStateJob?.cancel(); sessionStateJob = null
    presentationQueue?.stop(); presentationQueue = null
    _uiState.update { INITIAL_STATE }
    stream?.stop(); stream = null
    session?.stop(); session = null
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) return
    if (uiState.value.streamSessionState == StreamSessionState.STREAMING) {
      _uiState.update { it.copy(isCapturing = true) }
      viewModelScope.launch {
        stream?.capturePhoto()
          ?.onSuccess { photoData ->
            handlePhotoData(photoData)
            _uiState.update { it.copy(isCapturing = false) }
          }
          ?.onFailure { error, _ ->
            Log.e(TAG, "Photo capture failed: ${error.description}")
            _uiState.update { it.copy(isCapturing = false) }
          }
      }
    }
  }

  fun showShareDialog() { _uiState.update { it.copy(isShareDialogVisible = true) } }
  fun hideShareDialog() { _uiState.update { it.copy(isShareDialogVisible = false) } }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream) }
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        putExtra(Intent.EXTRA_STREAM, uri)
        type = "image/png"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      val chooser = Intent.createChooser(intent, "Share Image").apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
      }
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to share photo", e)
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    val bitmap = YuvToBitmapConverter.convert(videoFrame.buffer, videoFrame.width, videoFrame.height)
    if (bitmap != null) {
      presentationQueue?.enqueue(bitmap, videoFrame.presentationTimeUs)
    }
  }

  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto = when (photo) {
      is PhotoData.Bitmap -> photo.bitmap
      is PhotoData.HEIC -> {
        val byteArray = ByteArray(photo.data.remaining())
        photo.data.get(byteArray)
        val exifInfo = getExifInfo(byteArray)
        val transform = getTransform(exifInfo)
        decodeHeic(byteArray, transform)
      }
    }
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { ExifInterface(it) }
    } catch (e: IOException) { null }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()
    if (exifInfo == null) return matrix
    when (exifInfo.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
    }
    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) return bitmap
    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) bitmap.recycle()
      transformed
    } catch (e: OutOfMemoryError) { bitmap }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
    objectDetector?.close()
  }

  class Factory(
    private val application: Application,
    private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(application, wearablesViewModel) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}