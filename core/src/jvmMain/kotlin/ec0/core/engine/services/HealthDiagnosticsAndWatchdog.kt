/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Feature #14 — Production Observability, Diagnostics & Watchdog:
 *   1. SystemHealthDiagnosticsRunner: one-click health checks (mDNS, /dev/uinput,
 *      firewall, v4l2loopback, PipeWire/PulseAudio, adb/scrcpy, Keystore backend)
 *      using argv-only read-only probes with actionable auto-fix guidance.
 *   2. PiiRedactor & DiagnosticRingBuffer: bounded 500-entry log buffer that
 *      scrubs tokens, secrets, emails, phone numbers, IPs, and OTP codes.
 *   3. SupportBundleExporter: writes a redacted JSON diagnostics bundle inside
 *      `0700` `<configDir>/diagnostics/` with `0600` file permissions.
 *   4. ServiceCrashLoopWatchdog: sliding-window crash-loop detector with
 *      exponential backoff (1s..60s) and circuit-breaker trip.
 */
package ec0.core.engine.services

import ec0.core.engine.CanonicalJson
import ec0.core.engine.crypto.EncryptedIdentityStore
import ec0.core.engine.crypto.KeySealerBackend
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class DiagnosticSeverity {
    OK,
    WARN,
    ERROR,
}

data class DiagnosticCheckResult(
    val id: String,
    val title: String,
    val severity: DiagnosticSeverity,
    val summary: String,
    val fixHint: String = "",
)

data class DiagnosticLogEntry(
    val timestampMs: Long,
    val level: String,
    val tag: String,
    val message: String,
)

/**
 * Scrubs sensitive secrets and PII before writing logs or exporting support bundles.
 */
object PiiRedactor {
    private val tokenPatterns = listOf(
        Regex("""github_pat_[A-Za-z0-9_]{10,}"""),
        Regex("""gh[pousr]_[A-Za-z0-9]{10,}"""),
        Regex("""(?i)(bearer\s+)[A-Za-z0-9._\-+/=]{8,}"""),
        Regex("""(?i)((?:token|secret|password|auth|pairing_token|resume_token|master_key)[=:\s"']+)[^\s"',;}{]+"""),
        Regex("""\b[0-9a-fA-F]{48,}\b"""),
    )
    private val emailRegex = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val ipv4Regex = Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""")
    private val phoneRegex = Regex("""(?<!\w)\+?\d[\d\s\-()]{8,16}\d(?!\w)""")
    private val otpRegex = Regex("""(?i)\b(otp|code|verification|passcode|pin)(\s*(?:is|:|#)?\s*)(\d{4,8})\b""")

    fun redact(raw: String): String {
        var out = raw
        // Preserve redaction markers cleanly while stripping secrets first
        out = tokenPatterns[0].replace(out, "[REDACTED_TOKEN]")
        out = tokenPatterns[1].replace(out, "[REDACTED_TOKEN]")
        out = tokenPatterns[2].replace(out) { "${it.groupValues[1]}[REDACTED_TOKEN]" }
        out = tokenPatterns[3].replace(out) { "${it.groupValues[1]}[REDACTED_SECRET]" }
        out = tokenPatterns[4].replace(out, "[REDACTED_HEX]")
        out = emailRegex.replace(out, "[REDACTED_EMAIL]")
        out = otpRegex.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}[REDACTED_OTP]" }
        out = ipv4Regex.replace(out) { match ->
            val ip = match.value
            if (ip == "127.0.0.1" || ip == "224.0.0.251" || ip == "0.0.0.0") ip else "[REDACTED_IP]"
        }
        out = phoneRegex.replace(out, "[REDACTED_PHONE]")
        return out
    }
}

/**
 * Thread-safe bounded diagnostic ring buffer (default 500 entries) with PII scrubbing.
 */
class DiagnosticRingBuffer(
    private val maxEntries: Int = 500,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = ReentrantLock()
    private val buffer = ArrayDeque<DiagnosticLogEntry>(maxEntries)

    fun record(level: String, tag: String, message: String) = lock.withLock {
        if (buffer.size >= maxEntries) {
            buffer.removeFirst()
        }
        buffer.addLast(
            DiagnosticLogEntry(
                timestampMs = clockMs(),
                level = level.uppercase().take(12),
                tag = tag.take(48),
                message = PiiRedactor.redact(message).take(512),
            ),
        )
    }

    fun snapshot(): List<DiagnosticLogEntry> = lock.withLock { buffer.toList() }

    fun clear() = lock.withLock { buffer.clear() }
}

/**
 * Read-only system probe runner powering the "Diagnose & Auto-Fix" Health Center.
 */
class SystemHealthDiagnosticsRunner(
    private val commandRunner: (List<String>) -> Int = { argv ->
        runCatching {
            val proc = ProcessBuilder(argv).redirectErrorStream(true).start()
            proc.inputStream.close()
            if (proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) proc.exitValue() else -1
        }.getOrDefault(-1)
    },
    private val uinputFile: File = File("/dev/uinput"),
    private val v4l2ModuleDir: File = File("/sys/module/v4l2loopback"),
    private val keystoreBackendProvider: () -> KeySealerBackend = { KeySealerBackend.FILE_0600 },
    private val multicastProbe: () -> Boolean = { defaultMulticastProbe() },
) {
    fun runAllChecks(): List<DiagnosticCheckResult> = listOf(
        checkKeystoreBackend(),
        checkMdnsMulticast(),
        checkUinputPermissions(),
        checkPipeWireAudio(),
        checkV4l2Loopback(),
        checkAdbAndScrcpy(),
    )

    private fun checkKeystoreBackend(): DiagnosticCheckResult {
        val backend = keystoreBackendProvider()
        val ok = backend != KeySealerBackend.FILE_0600
        return DiagnosticCheckResult(
            id = "keystore",
            title = "Identity Key Sealing",
            severity = if (ok) DiagnosticSeverity.OK else DiagnosticSeverity.WARN,
            summary = "Active backend: ${backend.displayName}",
            fixHint = if (ok) "" else "Install libsecret-tools (`secret-tool`) and unlock GNOME Keyring / KWallet for OS-sealed master key storage.",
        )
    }

    private fun checkMdnsMulticast(): DiagnosticCheckResult {
        val ok = runCatching { multicastProbe() }.getOrDefault(false)
        return DiagnosticCheckResult(
            id = "mdns",
            title = "mDNS LAN Discovery (224.0.0.251:5353)",
            severity = if (ok) DiagnosticSeverity.OK else DiagnosticSeverity.WARN,
            summary = if (ok) "Multicast socket ready for peer discovery." else "Multicast probe restricted; fallback port-walk & QR pairing still work.",
            fixHint = if (ok) "" else "Allow UDP 5353 and TCP 27160-27165 in your firewall (e.g. `sudo ufw allow 5353/udp && sudo ufw allow 27160:27165/tcp`).",
        )
    }

    private fun checkUinputPermissions(): DiagnosticCheckResult {
        val exists = uinputFile.exists()
        val writable = exists && uinputFile.canWrite()
        return when {
            writable -> DiagnosticCheckResult(
                id = "uinput",
                title = "Universal Control / Input Injection (/dev/uinput)",
                severity = DiagnosticSeverity.OK,
                summary = "/dev/uinput is accessible for rootless input injection.",
            )
            exists -> DiagnosticCheckResult(
                id = "uinput",
                title = "Universal Control / Input Injection (/dev/uinput)",
                severity = DiagnosticSeverity.WARN,
                summary = "/dev/uinput exists but is not writable by current user.",
                fixHint = "Install packaging/udev/99-ec0-uinput.rules and add your user to the `uinput` or `input` group.",
            )
            else -> DiagnosticCheckResult(
                id = "uinput",
                title = "Universal Control / Input Injection (/dev/uinput)",
                severity = DiagnosticSeverity.WARN,
                summary = "/dev/uinput module not loaded.",
                fixHint = "Run `sudo modprobe uinput` and install packaging/udev/99-ec0-uinput.rules.",
            )
        }
    }

    private fun checkPipeWireAudio(): DiagnosticCheckResult {
        val rc = commandRunner(listOf("pactl", "info"))
        return DiagnosticCheckResult(
            id = "audio",
            title = "PipeWire / PulseAudio Handoff & Virtual Mic",
            severity = if (rc == 0) DiagnosticSeverity.OK else DiagnosticSeverity.WARN,
            summary = if (rc == 0) "pactl connected to audio server." else "pactl unavailable; Audio Sink, Virtual Mic, and Meeting Auto-Focus disabled.",
            fixHint = if (rc == 0) "" else "Install `pipewire-pulse` or `pulseaudio-utils` so `pactl` is on PATH.",
        )
    }

    private fun checkV4l2Loopback(): DiagnosticCheckResult {
        val loaded = v4l2ModuleDir.isDirectory
        return DiagnosticCheckResult(
            id = "v4l2loopback",
            title = "Continuity Webcam (v4l2loopback)",
            severity = if (loaded) DiagnosticSeverity.OK else DiagnosticSeverity.WARN,
            summary = if (loaded) "v4l2loopback kernel module is loaded." else "v4l2loopback module not loaded; Continuity Camera virtual webcam unavailable.",
            fixHint = if (loaded) "" else "Run `sudo modprobe v4l2loopback exclusive_caps=1 card_label=\"ec0 Continuity Camera\"`.",
        )
    }

    private fun checkAdbAndScrcpy(): DiagnosticCheckResult {
        val adbOk = commandRunner(listOf("adb", "--version")) == 0
        val scrcpyOk = commandRunner(listOf("scrcpy", "--version")) == 0
        return when {
            adbOk && scrcpyOk -> DiagnosticCheckResult(
                id = "adb_scrcpy",
                title = "USB Tunnel & App Mirroring (adb + scrcpy)",
                severity = DiagnosticSeverity.OK,
                summary = "Both adb and scrcpy are installed and reachable.",
            )
            else -> DiagnosticCheckResult(
                id = "adb_scrcpy",
                title = "USB Tunnel & App Mirroring (adb + scrcpy)",
                severity = DiagnosticSeverity.WARN,
                summary = "Missing tools: ${listOfNotNull("adb".takeIf { !adbOk }, "scrcpy".takeIf { !scrcpyOk }).joinToString(", ")}.",
                fixHint = "Install `android-tools` (adb) and `scrcpy` from your distro package manager.",
            )
        }
    }

    companion object {
        private fun defaultMulticastProbe(): Boolean {
            return runCatching {
                MulticastSocket().use { sock ->
                    sock.soTimeout = 200
                    val group = InetAddress.getByName("224.0.0.251")
                    group.isMulticastAddress
                }
            }.getOrDefault(false)
        }
    }
}

/**
 * Exports a strictly PII-redacted JSON support bundle inside `0700` `<configDir>/diagnostics/`.
 */
object SupportBundleExporter {
    fun exportRedactedBundle(
        configDir: File,
        appVersion: String,
        checks: List<DiagnosticCheckResult>,
        logs: List<DiagnosticLogEntry>,
        nowMs: Long = System.currentTimeMillis(),
    ): File {
        val diagDir = File(configDir, "diagnostics")
        EncryptedIdentityStore.ensureDirectoryPermissions(configDir)
        EncryptedIdentityStore.ensureDirectoryPermissions(diagDir)

        val root = buildJsonObject {
            put("schema", "ec0-support-bundle-v1")
            put("generated_at_ms", nowMs)
            put("app_version", PiiRedactor.redact(appVersion))
            put("os_name", System.getProperty("os.name", "unknown"))
            put("os_arch", System.getProperty("os.arch", "unknown"))
            put("java_version", System.getProperty("java.version", "unknown"))
            put(
                "checks",
                buildJsonArray {
                    for (c in checks) {
                        add(
                            buildJsonObject {
                                put("id", c.id)
                                put("title", c.title)
                                put("severity", c.severity.name)
                                put("summary", PiiRedactor.redact(c.summary))
                                put("fix_hint", PiiRedactor.redact(c.fixHint))
                            },
                        )
                    }
                },
            )
            put(
                "logs",
                buildJsonArray {
                    for (entry in logs) {
                        add(
                            buildJsonObject {
                                put("ts", entry.timestampMs)
                                put("level", entry.level)
                                put("tag", entry.tag)
                                put("msg", PiiRedactor.redact(entry.message))
                            },
                        )
                    }
                },
            )
        }

        val target = File(diagDir, "ec0-support-$nowMs.json")
        EncryptedIdentityStore.atomicWriteText(target, CanonicalJson.canonicalizeToString(root))
        return target
    }
}

/**
 * Sliding-window crash-loop watchdog for foreground services and headless daemons.
 * Protects Android devices and Linux systemd sessions from infinite restart storms.
 */
class ServiceCrashLoopWatchdog(
    private val maxCrashesInWindow: Int = 5,
    private val windowMs: Long = 60_000L,
    private val baseBackoffMs: Long = 1_000L,
    private val maxBackoffMs: Long = 60_000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = ReentrantLock()
    private val crashTimestamps = ArrayDeque<Long>()
    private var consecutiveCrashes = 0
    private var circuitTripped = false

    fun recordHealthyStart() = lock.withLock {
        consecutiveCrashes = 0
    }

    /**
     * Records a failure and returns the recommended backoff delay in ms, or `-1L`
     * if the crash-loop circuit breaker has tripped (caller should enter safe mode
     * and surface a user notification instead of spinning).
     */
    fun recordFailure(nowMs: Long = clockMs()): Long = lock.withLock {
        pruneLocked(nowMs)
        crashTimestamps.addLast(nowMs)
        consecutiveCrashes += 1
        if (crashTimestamps.size >= maxCrashesInWindow) {
            circuitTripped = true
            return@withLock -1L
        }
        val shift = (consecutiveCrashes - 1).coerceIn(0, 10)
        val delay = baseBackoffMs * (1L shl shift)
        delay.coerceAtMost(maxBackoffMs)
    }

    fun isCircuitTripped(nowMs: Long = clockMs()): Boolean = lock.withLock {
        pruneLocked(nowMs)
        if (crashTimestamps.isEmpty()) {
            circuitTripped = false
        }
        circuitTripped
    }

    fun recentCrashCount(nowMs: Long = clockMs()): Int = lock.withLock {
        pruneLocked(nowMs)
        crashTimestamps.size
    }

    fun reset() = lock.withLock {
        crashTimestamps.clear()
        consecutiveCrashes = 0
        circuitTripped = false
    }

    private fun pruneLocked(nowMs: Long) {
        while (crashTimestamps.isNotEmpty() && nowMs - crashTimestamps.first() > windowMs) {
            crashTimestamps.removeFirst()
        }
    }
}
