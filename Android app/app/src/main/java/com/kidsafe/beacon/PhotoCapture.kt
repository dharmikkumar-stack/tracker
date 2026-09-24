package com.kidsafe.beacon

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Captures one still image with CameraX without showing a preview or launching
 * the camera app. Caller must already hold CAMERA permission.
 */
class PhotoCapture(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    /** @param useFrontCamera true = selfie camera, false = rear camera. */
    suspend fun capture(useFrontCamera: Boolean): File? =
        suspendCancellableCoroutine { cont ->
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    val selector = if (useFrontCamera)
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    else
                        CameraSelector.DEFAULT_BACK_CAMERA

                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, imageCapture)

                    val file = File.createTempFile("photo_", ".jpg", context.cacheDir)
                    val output = ImageCapture.OutputFileOptions.Builder(file).build()

                    imageCapture.takePicture(
                        output,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                                provider.unbindAll()
                                if (cont.isActive) cont.resume(file)
                            }

                            override fun onError(exc: ImageCaptureException) {
                                provider.unbindAll()
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }, ContextCompat.getMainExecutor(context))
        }
}
