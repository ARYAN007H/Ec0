/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Pure-JVM unit tests for Android production features & security hardening:
 *   - Continuity Camera profile parsing (720p / 1080p / 2k / 4k @ 30/60fps)
 *   - ShareIntentSanitizer (strict content:// allowlist, self-provider & file:// block, filename sanitization)
 *   - Velocity-accelerated touchpad scaling bounds
 */
package ec0.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductionHardeningAndFeaturesTest {

    @Test
    fun parseCameraProfileResolvesStandardAndCustomResolutions() {
        val p1080 = parseCameraProfile("1080p", requestedFps = 30)
        assertEquals(1920, p1080.width)
        assertEquals(1080, p1080.height)
        assertEquals(30, p1080.fps)

        val p720 = parseCameraProfile("720p", requestedFps = 60)
        assertEquals(1280, p720.width)
        assertEquals(720, p720.height)
        assertEquals(60, p720.fps)

        val p4k = parseCameraProfile("4K", requestedFps = 120, maxFpsCeiling = 60)
        assertEquals(3840, p4k.width)
        assertEquals(2160, p4k.height)
        assertEquals(60, p4k.fps)

        val fallback = parseCameraProfile("unknown-profile", requestedFps = 5)
        assertEquals(1920, fallback.width)
        assertEquals(1080, fallback.height)
        assertEquals(15, fallback.fps)
    }

    @Test
    fun shareIntentSanitizerBlocksFileUrisAndOwnProvider() {
        assertTrue(
            ShareIntentSanitizer.isAllowedShareUri(
                "content://com.android.providers.media.documents/document/image%3A1042",
                appPackageName = "ec0.android",
            ),
        )
        assertTrue(
            ShareIntentSanitizer.isAllowedShareUri(
                "content://com.google.android.apps.photos.contentprovider/0/1/mediakey",
                appPackageName = "ec0.android",
            ),
        )
        // Must block file:// URI exfiltration (e.g. malicious app pointing at ec0's identity.key)
        assertFalse(
            ShareIntentSanitizer.isAllowedShareUri(
                "file:///data/data/ec0.android/files/ec0/identity.key",
                appPackageName = "ec0.android",
            ),
        )
        // Must block self-authority URIs
        assertFalse(
            ShareIntentSanitizer.isAllowedShareUri(
                "content://ec0.android.fileprovider/private/master.key",
                appPackageName = "ec0.android",
            ),
        )
        assertFalse(
            ShareIntentSanitizer.isAllowedShareUri(
                "content://ec0.android/secrets",
                appPackageName = "ec0.android",
            ),
        )
        // Must block http/javascript/empty URIs
        assertFalse(ShareIntentSanitizer.isAllowedShareUri("https://evil.example/payload"))
        assertFalse(ShareIntentSanitizer.isAllowedShareUri("content:///missing-authority"))
        assertFalse(ShareIntentSanitizer.isAllowedShareUri(null))
        assertFalse(ShareIntentSanitizer.isAllowedShareUri(""))
    }

    @Test
    fun shareIntentSanitizerStripsTraversalAndControlCharsFromFilenames() {
        assertEquals(
            "passwd",
            ShareIntentSanitizer.sanitizeShareFileName("../../etc/passwd"),
        )
        assertEquals(
            "photo_1.jpg",
            ShareIntentSanitizer.sanitizeShareFileName("folder\\sub\\photo_1.jpg\u0000\n"),
        )
        assertEquals(
            "fallback.bin",
            ShareIntentSanitizer.sanitizeShareFileName("   ///   ", fallback = "fallback.bin"),
        )
    }

    @Test
    fun acceleratedDragScalesMonotonicallyAndClamps() {
        val (slowX, _) = PointerGestures.scaleDragAccelerated(4f, 0f, sensitivity = 1.6f)
        val (fastX, _) = PointerGestures.scaleDragAccelerated(30f, 0f, sensitivity = 1.6f)
        assertTrue(slowX in 6..10, "expected gentle slow movement, got $slowX")
        assertTrue(fastX > slowX * 10, "expected noticeable acceleration on fast swipe, got $fastX vs $slowX")

        val (clampedX, clampedY) = PointerGestures.scaleDragAccelerated(5000f, -5000f, sensitivity = 4f)
        assertEquals(500, clampedX)
        assertEquals(-500, clampedY)
    }
}
