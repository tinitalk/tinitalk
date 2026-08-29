package org.tinitalk.media

import org.tinitalk.call.CameraFacing
import org.junit.Assert.assertEquals
import org.junit.Test
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer

class WebRtcCameraPolicyTest {
    @Test
    fun defaultCameraOrderTriesEveryFrontCameraBeforeBackCamera() {
        val enumerator = FakeCameraEnumerator(
            devices = arrayOf("back-main", "front-wide", "other", "front-main"),
            front = setOf("front-wide", "front-main"),
        )

        assertEquals(
            listOf("front-wide", "front-main", "back-main", "other"),
            orderedCameraNames(enumerator),
        )
    }

    @Test
    fun oppositeFacingTargetSkipsAdditionalSameFacingLenses() {
        val enumerator = FakeCameraEnumerator(
            devices = arrayOf("front-wide", "front-main", "back-main", "back-tele"),
            front = setOf("front-wide", "front-main"),
        )

        assertEquals(
            "back-main",
            oppositeFacingCameraName(enumerator, CameraFacing.Front),
        )
        assertEquals(
            "front-wide",
            oppositeFacingCameraName(enumerator, CameraFacing.Back),
        )
    }

    @Test
    fun brokenCamera2EnumerationDoesNotPreventCamera1Fallback() {
        val failures = mutableListOf<String>()
        val camera1 = FakeCameraEnumerator(
            devices = arrayOf("camera1-front"),
            front = setOf("camera1-front"),
        )

        val devices = enumerateCameraBackends(
            backends = listOf(
                CameraEnumeratorBackend("camera2") {
                    FakeCameraEnumerator(
                        devices = arrayOf("camera2-front"),
                        front = setOf("camera2-front"),
                        failDeviceNames = true,
                    )
                },
                CameraEnumeratorBackend("camera1") { camera1 },
            ),
            onFailure = { backend, _ -> failures += backend },
        )

        assertEquals(listOf("camera1-front"), devices.map { it.deviceName })
        assertEquals(listOf("camera2"), failures)
    }

    private class FakeCameraEnumerator(
        private val devices: Array<String>,
        private val front: Set<String>,
        private val failDeviceNames: Boolean = false,
    ) : CameraEnumerator {
        override fun getDeviceNames(): Array<String> {
            if (failDeviceNames) error("broken camera backend")
            return devices
        }
        override fun isFrontFacing(deviceName: String): Boolean = deviceName in front
        override fun isBackFacing(deviceName: String): Boolean = deviceName !in front
        override fun getSupportedFormats(deviceName: String) = emptyList<org.webrtc.CameraEnumerationAndroid.CaptureFormat>()
        override fun createCapturer(
            deviceName: String,
            eventsHandler: CameraVideoCapturer.CameraEventsHandler?,
        ): CameraVideoCapturer? = null
    }
}
