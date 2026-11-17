/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Pure-function tests for the v4l2/scrcpy camera pre-flight: version parsing,
 * support floor (>= 2.2), modprobe argv, back-camera id discovery, and the
 * fake-runner-driven pre-flight outcomes (READY / NEEDS_PASSWORD / FAILED /
 * UNSUPPORTED).
 */
package ec0.core.engine.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContinuityCameraPreflightTest {

    // ---- parseScrcpyVersion ------------------------------------------------

    @Test
    fun parsesBannerVersions() {
        assertEquals("5.0.1", parseScrcpyVersion("scrcpy 5.0.1 <https://github.com/Genymobile/scrcpy>"))
        assertEquals("2.2", parseScrcpyVersion("scrcpy 2.2"))
        assertEquals("3.3.3", parseScrcpyVersion("noise before\nscrcpy\t3.3.3\nnoise after"))
    }

    @Test
    fun parseRejectsGarbageAndAbsence() {
        assertNull(parseScrcpyVersion("command not found: scrcpy"))
        assertNull(parseScrcpyVersion(""))
        assertNull(parseScrcpyVersion("scrcpy version unclear"))
    }

    // ---- isScrcpyVersionSupported ------------------------------------------

    @Test
    fun supportFloorIsTwoDotTwo() {
        assertTrue(isScrcpyVersionSupported("2.2"))
        assertTrue(isScrcpyVersionSupported("2.4"))
        assertTrue(isScrcpyVersionSupported("3.1.3"))
        assertTrue(isScrcpyVersionSupported("5.0.1"))
        assertFalse(isScrcpyVersionSupported("2.1.3"))
        assertFalse(isScrcpyVersionSupported("1.25"))
        assertFalse(isScrcpyVersionSupported(null))
    }

    // ---- argv builders ------------------------------------------------------

    @Test
    fun sudoModprobeArgvIsPasswordlessProbe() {
        val coordinator = ContinuityCameraCoordinator()
        val argv = coordinator.buildSudoModprobeCommand()
        assertEquals("sudo", argv.first())
        assertEquals("-n", argv[1]) // passwordless — never prompts
        assertEquals("modprobe", argv[2])
        assertEquals("v4l2loopback", argv[3])
        assertTrue(argv.contains("video_nr=10"))
        assertTrue(argv.contains("devices=1"))
        assertTrue(argv.contains("exclusive_caps=1"))
        assertTrue(argv.contains("card_label=ec0 camera"))
    }

    @Test
    fun scrcpyVersionCommandUsesConfiguredBinary() {
        val coordinator = ContinuityCameraCoordinator(scrcpyBinaryPath = "/opt/scrcpy/bin/scrcpy")
        assertEquals(listOf("/opt/scrcpy/bin/scrcpy", "--version"), coordinator.buildScrcpyVersionCommand())
    }

    @Test
    fun cameraIdFlagWinsOverFacingHeuristic() {
        val coordinator = ContinuityCameraCoordinator()
        val withId = coordinator.buildScrcpyCameraCommand(host = "192.168.49.1", cameraId = "0")
        assertTrue(withId.contains("--camera-id=0"))
        assertFalse(withId.any { it.startsWith("--camera-facing") })

        val withoutId = coordinator.buildScrcpyCameraCommand(host = "192.168.49.1")
        assertTrue(withoutId.contains("--camera-facing=back"))
        assertFalse(withoutId.any { it.startsWith("--camera-id") })

        // the task's start-camera contract: video-source + v4l2-sink /dev/video10
        assertTrue(withId.contains("--video-source=camera"))
        assertTrue(withId.contains("--v4l2-sink=/dev/video10"))
    }

    // ---- back camera discovery ---------------------------------------------

    @Test
    fun backCameraIdFromListCamerasOutput() {
        val output = """
            [scrcpy] INFO: List of cameras:
                --camera-id=0    (facing=back, video_size=1920x1080)
                --camera-id=1    (facing=front, video_size=1920x1080)
        """.trimIndent()
        assertEquals("0", parseBackCameraId(output))
    }

    @Test
    fun backCameraIdSpaceSeparatedFacingVariant() {
        assertEquals("2", parseBackCameraId("Camera #2, facing back, video_size=1920x1080"))
        assertNull(parseBackCameraId("Camera #1, facing=front, video_size=1920x1080"))
        assertNull(parseBackCameraId(""))
    }

    // ---- v4l2Preflight with fake runner ------------------------------------

    @Test
    fun preflightReadyWhenModprobeSucceedsAndScrcpyIsModern() {
        val coordinator = ContinuityCameraCoordinator(v4l2DeviceCheck = { false })
        val calls = mutableListOf<List<String>>()
        val result = coordinator.v4l2Preflight { argv ->
            calls.add(argv)
            when (argv.firstOrNull()) {
                "sudo" -> CommandExecResult(0, "", "")
                else -> CommandExecResult(0, "scrcpy 5.0.1 <https://github.com/Genymobile/scrcpy>", "")
            }
        }
        assertEquals(V4l2Outcome.READY, result.outcome)
        assertEquals("5.0.1", result.scrcpyVersionText)
        assertTrue(result.scrcpySupported)
        assertEquals("", result.remediationCommand)
        assertEquals(2, calls.size) // modprobe probe + scrcpy --version
    }

    @Test
    fun preflightSurfacesPasswordRemediationInsteadOfPrompting() {
        val coordinator = ContinuityCameraCoordinator(v4l2DeviceCheck = { false })
        val result = coordinator.v4l2Preflight { argv ->
            if (argv.firstOrNull() == "sudo") {
                CommandExecResult(1, "", "sudo: a password is required")
            } else {
                CommandExecResult(0, "scrcpy 5.0.1", "")
            }
        }
        assertEquals(V4l2Outcome.NEEDS_PASSWORD, result.outcome)
        assertFalse(result.scrcpySupported) // scrcpy was never checked — not ready
        assertTrue(result.remediationCommand.contains("sudo modprobe v4l2loopback"))
        assertTrue(result.remediationCommand.contains("video_nr=10"))
    }

    @Test
    fun preflightReportsModprobeFailure() {
        val coordinator = ContinuityCameraCoordinator(v4l2DeviceCheck = { false })
        val result = coordinator.v4l2Preflight { argv ->
            if (argv.firstOrNull() == "sudo") {
                CommandExecResult(1, "", "modprobe: FATAL: Module v4l2loopback not found")
            } else {
                CommandExecResult(0, "scrcpy 5.0.1", "")
            }
        }
        assertEquals(V4l2Outcome.MODPROBE_FAILED, result.outcome)
    }

    @Test
    fun preflightFlagsOldScrcpy() {
        val coordinator = ContinuityCameraCoordinator(v4l2DeviceCheck = { true }) // device already present
        val result = coordinator.v4l2Preflight { _ ->
            CommandExecResult(0, "scrcpy 2.1.3", "")
        }
        assertEquals(V4l2Outcome.SCRCPY_UNSUPPORTED, result.outcome)
        assertEquals("2.1.3", result.scrcpyVersionText)
        assertFalse(result.scrcpySupported)
    }

    @Test
    fun preflightSkipsModprobeWhenDeviceNodeReady() {
        val coordinator = ContinuityCameraCoordinator(v4l2DeviceCheck = { true })
        val sudoCalled = mutableListOf<List<String>>()
        val result = coordinator.v4l2Preflight { argv ->
            sudoCalled.add(argv)
            CommandExecResult(0, "scrcpy 5.0.1", "")
        }
        assertEquals(V4l2Outcome.READY, result.outcome)
        assertTrue(sudoCalled.none { it.firstOrNull() == "sudo" }) // device ready → no modprobe attempt
    }
}
