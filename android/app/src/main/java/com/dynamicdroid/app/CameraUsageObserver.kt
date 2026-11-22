package com.dynamicdroid.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

/** Reports front-camera availability without opening the camera or requesting camera permission. */
internal class CameraUsageObserver(
    context: Context,
    private val onBusyChanged: (Boolean) -> Unit,
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val frontCameraIds = runCatching {
        manager?.cameraIdList.orEmpty().filter { cameraId ->
            manager?.getCameraCharacteristics(cameraId)
                ?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }.toSet()
    }.getOrDefault(emptySet())

    private val callback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            if (cameraId in frontCameraIds) onBusyChanged(true)
        }

        override fun onCameraAvailable(cameraId: String) {
            if (cameraId in frontCameraIds) onBusyChanged(false)
        }
    }

    fun start() {
        runCatching { manager?.registerAvailabilityCallback(callback, handler) }
    }

    fun stop() {
        runCatching { manager?.unregisterAvailabilityCallback(callback) }
    }
}
