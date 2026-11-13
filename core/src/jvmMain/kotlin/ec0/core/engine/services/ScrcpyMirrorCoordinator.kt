/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Scrcpy Screen Mirroring Coordinator (`docs/engine/design.md` §6.7, Amendment 5):
 *   - Negotiates `mirror_start` / `mirror_stop` over the authenticated control plane.
 *   - Normative Amendment 5 sequence on Desktop:
 *       1. Run `adb connect <host>:<adb_port>` and verify exit code == 0 and stdout
 *          contains `"connected to"` (or `"already connected to"`).
 *       2. Spawn external `scrcpy` binary (`--serial <host>:<adb_port> --window-title <title>
 *          --max-fps <fps> --video-bit-rate <bitrateKbps>K`).
 *   - Keeps `scrcpy` as an unmodified external process per GPL-3.0 / README.md §License.
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.protocol.PresenceProtocol
import ec0.core.engine.protocol.ScrcpyMirrorStartCommand
import ec0.core.engine.protocol.ScrcpyMirrorStopCommand
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class CommandExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String = "",
)

interface RunningProcessHandle : Closeable {
    val isAlive: Boolean
    fun stop()
    override fun close() = stop()
}

interface ExternalProcessLauncher {
    /** Runs a short-lived command (such as `adb connect <host>:<port>`) and returns its output. */
    fun runBlockingCommand(command: List<String>, timeoutMs: Long = 5_000L): CommandExecResult

    /** Spawns a long-lived background process (such as `scrcpy --serial ...`). */
    fun spawnProcess(command: List<String>): RunningProcessHandle
}

object SystemProcessLauncher : ExternalProcessLauncher {
    override fun runBlockingCommand(command: List<String>, timeoutMs: Long): CommandExecResult {
        val proc = ProcessBuilder(command).start()
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw IOException("Command timed out after ${timeoutMs}ms: ${command.joinToString(" ")}")
        }
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        return CommandExecResult(proc.exitValue(), stdout, stderr)
    }

    override fun spawnProcess(command: List<String>): RunningProcessHandle {
        val proc = ProcessBuilder(command).start()
        return object : RunningProcessHandle {
            override val isAlive: Boolean
                get() = proc.isAlive

            override fun stop() {
                if (proc.isAlive) {
                    proc.destroy()
                }
            }
        }
    }
}

class ScrcpyMirrorCoordinator(
    private val launcher: ExternalProcessLauncher = SystemProcessLauncher,
    private val adbBinary: String = "adb",
    private val scrcpyBinary: String = "scrcpy",
) : Closeable {

    private val lock = ReentrantLock()
    private var activeProcess: RunningProcessHandle? = null
    private var activeSerial: String? = null

    val isMirroring: Boolean
        get() = lock.withLock { activeProcess?.isAlive == true }

    val currentSerial: String?
        get() = lock.withLock { activeSerial }

    fun requestStartMirror(
        cmd: ScrcpyMirrorStartCommand,
        sendCmd: (JsonObject) -> Unit,
    ) {
        sendCmd(cmd.toJsonObject())
    }

    fun requestStopMirror(
        reason: String = "stopped_by_user",
        sendCmd: (JsonObject) -> Unit,
    ) {
        stopLocalMirror()
        sendCmd(ScrcpyMirrorStopCommand(reason).toJsonObject())
    }

    /**
     * Builds the exact `adb connect <host>:<adb_port>` command line (Amendment 5).
     */
    fun buildAdbConnectArgs(cmd: ScrcpyMirrorStartCommand): List<String> =
        listOf(adbBinary, "connect", cmd.serial)

    /**
     * Builds the exact `scrcpy` command line after `adb connect` succeeds.
     */
    fun buildScrcpyArgs(cmd: ScrcpyMirrorStartCommand): List<String> = listOf(
        scrcpyBinary,
        "--serial",
        cmd.serial,
        "--window-title",
        cmd.windowTitle,
        "--max-fps",
        cmd.maxFps.toString(),
        "--video-bit-rate",
        "${cmd.bitrateKbps}K",
    )

    /**
     * Executes the normative Amendment 5 sequence:
     *   1. `adb connect <host>:<adb_port>`
     *   2. Verify exitCode == 0 and stdout contains `"connected to"` (case-insensitive)
     *   3. Spawn `scrcpy --serial <host>:<adb_port> ...`
     */
    fun launchMirrorSession(cmd: ScrcpyMirrorStartCommand): RunningProcessHandle = lock.withLock {
        stopLocalMirrorLocked()

        val adbArgs = buildAdbConnectArgs(cmd)
        val adbResult = launcher.runBlockingCommand(adbArgs)
        val outLower = adbResult.stdout.lowercase()
        if (adbResult.exitCode != 0 || !outLower.contains("connected to") || outLower.contains("unable to connect") || outLower.contains("failed to connect")) {
            throw IOException(
                "adb connect ${cmd.serial} failed (exit=${adbResult.exitCode}): ${adbResult.stdout.trim()} ${adbResult.stderr.trim()}".trim(),
            )
        }

        val scrcpyArgs = buildScrcpyArgs(cmd)
        val handle = launcher.spawnProcess(scrcpyArgs)
        activeProcess = handle
        activeSerial = cmd.serial
        handle
    }

    fun handleControlEnvelope(envelope: Ec0Envelope): Boolean {
        val body = envelope.innerBodyObject ?: return false
        return when (envelope.bodyType) {
            PresenceProtocol.CMD_MIRROR_START -> {
                val startCmd = ScrcpyMirrorStartCommand.fromJsonObject(body)
                launchMirrorSession(startCmd)
                true
            }
            PresenceProtocol.CMD_MIRROR_STOP -> {
                ScrcpyMirrorStopCommand.fromJsonObject(body)
                stopLocalMirror()
                true
            }
            else -> false
        }
    }

    fun stopLocalMirror() {
        lock.withLock {
            stopLocalMirrorLocked()
        }
    }

    private fun stopLocalMirrorLocked() {
        runCatching { activeProcess?.stop() }
        activeProcess = null
        activeSerial = null
    }

    override fun close() {
        stopLocalMirror()
    }
}
