/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * VFS path hardening (walled-garden hardening): pure containment rules for the
 * phone↔desktop file-browsing protocol (B6). The phone-side receiver (future
 * slice) and the desktop download sink MUST share these exact rules so a
 * paired-but-compromised peer can never escape the explicitly granted roots.
 *
 * Pure string logic — unit-testable on every target; JVM callers additionally
 * re-verify with `File.canonicalFile` at the write site (canonical resolution
 * catches symlinks the string rule cannot see).
 */
package ec0.core.engine.protocol

/** POSIX path containment + download-filename sanitization for VFS surfaces. */
object VfsPathGuard {

    /** Longest raw phone-side path accepted in a request or reply (4096 = Linux PATH_MAX-ish). */
    const val MAX_PATH_CHARS: Int = 4096

    /**
     * True when [canonicalCandidate] is exactly [canonicalRoot] or lives under
     * it (string-prefix containment over canonical paths; both sides must be
     * produced by canonical resolution — `File.canonicalFile` on JVM).
     */
    fun isInsideRoot(canonicalRoot: String, canonicalCandidate: String): Boolean {
        val root = canonicalRoot.trimEnd('/')
        val candidate = canonicalCandidate.trimEnd('/')
        if (root.isEmpty()) return candidate.isNotEmpty() && candidate.startsWith("/")
        if (candidate == root) return true
        return candidate.startsWith(root) &&
            candidate.length > root.length &&
            candidate[root.length] == '/'
    }

    /**
     * Reduces a remote-supplied path to a safe basename for the desktop download
     * sink: strips every separator, rejects dot-only names, drops hidden-path
     * tricks (`..`), forbids control characters, and caps the length. Returns
     * `null` when nothing safe remains — callers must refuse rather than guess.
     */
    fun safeDownloadFileName(rawPath: String): String? {
        if (rawPath.isEmpty() || rawPath.length > MAX_PATH_CHARS) return null
        if (rawPath.any { it.code < 32 || it.code == 127 }) return null
        val base = rawPath.trim().substringAfterLast('/').substringAfterLast('\\')
        if (base.isEmpty() || base == "." || base == "..") return null
        val cleaned = base.replace(Regex("[^A-Za-z0-9._ +()'-]"), "_")
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null
        return cleaned.take(255)
    }

    /**
     * Validates a VFS list/download REQUEST path against the granted roots
     * before it is served: absolute-or-relative phone paths are accepted only
     * when their normalized form stays inside one of [grantedRoots] (a root
     * grants itself and everything under it). `..` segments are resolved
     * textually first; a path that escapes after resolution is rejected.
     */
    fun isRequestInsideGrantedRoots(rawPath: String, grantedRoots: Collection<String>): Boolean {
        if (rawPath.isEmpty() || rawPath.length > MAX_PATH_CHARS) return false
        if (rawPath.any { it.code < 32 || it.code == 127 }) return false
        if (rawPath.contains('\u0000')) return false
        val normalized = normalizeWithoutSymlinks(rawPath) ?: return false
        return grantedRoots.any { root ->
            isInsideRoot(normalizeWithoutSymlinks(root) ?: return@any false, normalized)
        }
    }

    /**
     * Lexical `..`/`.`/double-slash resolution (NOT symlink resolution — JVM
     * callers must re-check with canonical paths at the I/O boundary).
     */
    private fun normalizeWithoutSymlinks(rawPath: String): String? {
        val absolute = rawPath.startsWith("/")
        val out = ArrayDeque<String>()
        for (segment in rawPath.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (out.isEmpty()) {
                        if (absolute) return null // /.. escapes the filesystem root
                        return null // relative path escaping above the base is rejected
                    }
                    out.removeLast()
                }
                else -> out.addLast(segment)
            }
        }
        val joined = out.joinToString("/")
        return when {
            absolute -> "/$joined".let { if (it == "/") "/" else it }
            joined.isEmpty() -> null // empty relative path is not a usable location
            else -> joined
        }
    }
}
