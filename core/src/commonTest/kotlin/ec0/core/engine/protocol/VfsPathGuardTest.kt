/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * VFS path containment tests (audit H2): a paired-but-compromised phone must
 * NOT read outside the explicitly granted roots nor plant files outside the
 * desktop download sink.
 */
package ec0.core.engine.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VfsPathGuardTest {

    // --- canonical containment ---

    @Test
    fun rootItselfIsInside() {
        assertTrue(VfsPathGuard.isInsideRoot("/home/u/Downloads", "/home/u/Downloads"))
    }

    @Test
    fun childrenAreInside() {
        assertTrue(VfsPathGuard.isInsideRoot("/home/u/Downloads", "/home/u/Downloads/DCIM/a.jpg"))
    }

    @Test
    fun siblingPrefixIsOutside() {
        // classic prefix confusion: /home/u/Downloads_evil starts with /home/u/Downloads
        assertFalse(VfsPathGuard.isInsideRoot("/home/u/Downloads", "/home/u/Downloads_evil/x"))
    }

    @Test
    fun parentIsOutside() {
        assertFalse(VfsPathGuard.isInsideRoot("/home/u/Downloads", "/home/u"))
        assertFalse(VfsPathGuard.isInsideRoot("/home/u/Downloads", "/"))
    }

    @Test
    fun trailingSlashDoesNotConfuseContainment() {
        assertTrue(VfsPathGuard.isInsideRoot("/home/u/Downloads/", "/home/u/Downloads/x"))
        assertTrue(VfsPathGuard.isInsideRoot("/home/u/Downloads/", "/home/u/Downloads/"))
    }

    // --- download filename sanitization ---

    @Test
    fun plainBasenamesSurvive() {
        assertEquals("readme.txt", VfsPathGuard.safeDownloadFileName("/storage/DCIM/readme.txt"))
        assertEquals("photo 2024 (final).jpg", VfsPathGuard.safeDownloadFileName("photo 2024 (final).jpg"))
    }

    @Test
    fun dotDotAloneIsRejected() {
        assertNull(VfsPathGuard.safeDownloadFileName(".."))
        assertNull(VfsPathGuard.safeDownloadFileName("."))
        assertNull(VfsPathGuard.safeDownloadFileName("/a/.."))
    }

    @Test
    fun controlCharactersRejected() {
        assertNull(VfsPathGuard.safeDownloadFileName("bad\u0000name"))
        assertNull(VfsPathGuard.safeDownloadFileName("bad\nname"))
    }

    @Test
    fun separatorsAreStrippedToBasename() {
        assertEquals("passwd", VfsPathGuard.safeDownloadFileName("../../../etc/passwd"))
        assertEquals("passwd", VfsPathGuard.safeDownloadFileName("/etc/passwd"))
    }

    @Test
    fun hostileCharsetIsNeutralized() {
        assertEquals("c", VfsPathGuard.safeDownloadFileName("a/b/c"))
        assertEquals("x_rm -rf .sh", VfsPathGuard.safeDownloadFileName("x;rm -rf .sh"))
    }

    @Test
    fun oversizeNamesRejected() {
        assertNull(VfsPathGuard.safeDownloadFileName("a".repeat(5000)))
    }

    // --- request-side granted-root check (phone-side receiver contract) ---

    @Test
    fun requestsInsideGrantedRootPass() {
        val roots = listOf("/storage/emulated/0/DCIM", "/storage/emulated/0/Download")
        assertTrue(VfsPathGuard.isRequestInsideGrantedRoots("/storage/emulated/0/DCIM", roots))
        assertTrue(VfsPathGuard.isRequestInsideGrantedRoots("/storage/emulated/0/DCIM/Camera", roots))
        assertTrue(VfsPathGuard.isRequestInsideGrantedRoots("/storage/emulated/0/Download", roots))
    }

    @Test
    fun traversalRequestsOutsideRootsFail() {
        val roots = listOf("/storage/emulated/0/DCIM")
        assertFalse(VfsPathGuard.isRequestInsideGrantedRoots("/storage/emulated/0/DCIM/../../etc", roots))
        assertFalse(VfsPathGuard.isRequestInsideGrantedRoots("/etc", roots))
        assertFalse(VfsPathGuard.isRequestInsideGrantedRoots("/", roots))
        // relative escape above the granted root
        assertFalse(VfsPathGuard.isRequestInsideGrantedRoots("DCIM/../../..", roots))
    }

    @Test
    fun symlinkWordStillRejectedTextually() {
        // Textual rule: canonical verification happens again at the I/O boundary;
        // `..` segments resolving outside are rejected regardless of spelling.
        assertFalse(VfsPathGuard.isRequestInsideGrantedRoots("/storage/emulated/0/DCIM/../data/data", listOf("/storage/emulated/0/DCIM")))
    }

    @Test
    fun hostilesRequestsRejected() {
        val roots = listOf("/storage/emulated/0/DCIM")
        assertFalse(VfsPathGuard.isRequestInsideGrantedRoots("", roots))
        assertFalse(VfsPathGuard.isRequestInsideGrantedRoots("a".repeat(5000), roots))
        assertFalse(VfsPathGuard.isRequestInsideGrantedRoots("/storage/\u0000/DCIM", roots))
        assertFalse(VfsPathGuard.isRequestInsideGrantedRoots("/storage/emulated/0/DCIM\u0000", roots))
    }
}
