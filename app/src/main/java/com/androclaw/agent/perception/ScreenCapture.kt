package com.androclaw.agent.perception

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class ScreenCapture {
    suspend fun captureBase64(service: AccessibilityService): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        
        return suspendCancellableCoroutine { continuation ->
            val executor = Executors.newSingleThreadExecutor()
            service.takeScreenshot(
                0,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            hardwareBuffer.close()
                            
                            if (bitmap != null) {
                                val baos = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                                val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                                bitmap.recycle()
                                continuation.resume(base64)
                            } else {
                                continuation.resume(null)
                            }
                        } catch (e: Exception) {
                            continuation.resume(null)
                        } finally {
                            executor.shutdown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        executor.shutdown()
                        continuation.resume(null)
                    }
                }
            )
        }
    }
}
