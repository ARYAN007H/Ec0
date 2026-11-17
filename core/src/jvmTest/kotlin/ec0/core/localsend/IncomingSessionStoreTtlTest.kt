/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * The receiver holds ONE transfer slot; a sender that times out mid-decision or
 * never uploads after ACCEPT used to wedge it permanently (every later
 * prepare-upload answered 409 "Blocked by another session"). Stale sessions must
 * release the slot.
 */
package ec0.core.localsend

import ec0.core.localsend.dto.FileDto
import ec0.core.localsend.dto.RegisterDtoV2
import ec0.core.localsend.server.IncomingSessionStatus
import ec0.core.localsend.server.IncomingSessionStore
import ec0.ui.models.DeviceType
import ec0.ui.models.ProtocolType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncomingSessionStoreTtlTest {

    private fun registerDto() = RegisterDtoV2(
        alias = "ec0-desktop",
        version = "2.1",
        deviceModel = "Linux",
        deviceType = DeviceType.MOBILE,
        fingerprint = "fp-desktop",
        protocol = ProtocolType.HTTP,
        download = false,
        port = 53317,
    )

    private fun fileDto() = FileDto(
        id = "file-1",
        fileName = "photo.png",
        size = 4_661_508L,
        fileType = "image/png",
    )

    @Test
    fun pendingSessionReleasesSlotAfterTimeout() {
        var now = 1_000_000L
        val store = IncomingSessionStore(
            pendingTimeoutMs = 60_000L,
            decidingTimeoutMs = 300_000L,
            clockMs = { now },
        )
        val destination = Files.createTempDirectory("ec0-ttl").resolve("incoming")

        store.createSession(
            sessionId = "s1",
            senderIp = "10.115.38.1",
            sender = registerDto(),
            files = mapOf("file-1" to fileDto()),
            destination = destination,
            status = IncomingSessionStatus.DECIDING,
        )

        // Decision deferred past the desktop client's read timeout; the sender walked
        // away. The accepted-but-never-uploaded session must release the slot.
        store.filterToAccepted("s1", setOf("file-1")) // → PENDING
        now += 60_001L
        assertFalse(store.hasBlockingSession(), "stale PENDING session must be evicted, not 409 forever")
    }

    @Test
    fun decidingSessionKeepsSlotWhileFresh() {
        var now = 1_000_000L
        val store = IncomingSessionStore(
            pendingTimeoutMs = 60_000L,
            decidingTimeoutMs = 300_000L,
            clockMs = { now },
        )
        store.createSession(
            sessionId = "s2",
            senderIp = "10.115.38.1",
            sender = registerDto(),
            files = mapOf("file-1" to fileDto()),
            destination = Files.createTempDirectory("ec0-ttl2").resolve("incoming"),
            status = IncomingSessionStatus.DECIDING,
        )
        now += 30_000L
        assertTrue(store.hasBlockingSession(), "a fresh DECIDING session still owns the slot")
        now += 271_000L // past the deciding TTL
        assertFalse(store.hasBlockingSession(), "an unanswered prompt must eventually release the slot")
    }

    @Test
    fun freshPendingSessionStillBlocks() {
        var now = 1_000_000L
        val store = IncomingSessionStore(clockMs = { now })
        store.createSession(
            sessionId = "s3",
            senderIp = "10.115.38.1",
            sender = registerDto(),
            files = mapOf("file-1" to fileDto()),
            destination = Files.createTempDirectory("ec0-ttl3").resolve("incoming"),
            status = IncomingSessionStatus.PENDING,
        )
        now += 10_000L
        assertTrue(store.hasBlockingSession(), "an active PENDING session still blocks — only staleness releases")
    }
}
