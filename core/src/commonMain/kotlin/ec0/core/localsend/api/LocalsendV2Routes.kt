package ec0.core.localsend.api

import ec0.core.localsend.LocalsendV2

/**
 * HTTP API contract of LocalSend v2 — method, path and query parameters of
 * every route, extracted from the reference route table
 * (`packages/core/src/http/server/mod.rs:631-676`) and the client
 * (`packages/core/src/http/client/v2.rs`).
 *
 * Bodies: request/response DTOs in `ec0.core.localsend.dto`.
 *
 * Status-code semantics (reference `server/v2.rs`):
 * - wrong/missing PIN → `401` (decline-by-recipient → `403`; sender-canceled
 *   during the decision window → `403` as well)
 * - prepare-upload with nothing accepted → `204` no-content (empty body)
 * - upload success → **empty `200`** (no body, not a text status)
 * - PIN rate-limit exceeded → `429`
 */
enum class LocalsendV2Route(
    val method: Method,
    val path: String,
    val requiredQuery: List<String>,
    val optionalQuery: List<String> = emptyList(),
) {
    /** Device registration; request = [ec0.core.localsend.dto.RegisterDtoV2]. */
    REGISTER(Method.POST, "${LocalsendV2.API_PATH_V2}/register", emptyList()),

    /** Initiate a transfer; request = PrepareUploadRequestDtoV2.
     *  Success (some accepted) → 200 PrepareUploadResponseDtoV2;
     *  nothing accepted → 204; declined by user → 403; wrong pin → 401. */
    PREPARE_UPLOAD(
        Method.POST,
        "${LocalsendV2.API_PATH_V2}/prepare-upload",
        emptyList(),
        listOf("pin"),
    ),

    /** Stream one file's bytes; the token is issued by prepare-upload.
     *  Success → empty 200. */
    UPLOAD(Method.POST, "${LocalsendV2.API_PATH_V2}/upload", listOf("sessionId", "fileId", "token")),

    /** Abort a session. */
    CANCEL(Method.POST, "${LocalsendV2.API_PATH_V2}/cancel", listOf("sessionId")),

    /** Download API: request an offered-files session; response = PrepareDownloadResponseDtoV2.
     *  `sessionId` is optional — omitted on first contact, supplied when
     *  re-requesting an existing session (browser refresh scenarios;
     *  reference client/v2.rs `session_id: Option<&str>`, web.rs:317). */
    PREPARE_DOWNLOAD(
        Method.POST,
        "${LocalsendV2.API_PATH_V2}/prepare-download",
        emptyList(),
        listOf("sessionId", "pin"),
    ),

    /** Download API: fetch one file's bytes. */
    DOWNLOAD(Method.GET, "${LocalsendV2.API_PATH_V2}/download", listOf("sessionId", "fileId")),

    /** Device info endpoint (also served at /api/localsend/v1/info); response = InfoResponseDtoV2. */
    INFO(Method.GET, "${LocalsendV2.API_PATH_V2}/info", emptyList());

    enum class Method { GET, POST }

    /** Full wire path including the v2 prefix. */
    val wirePath: String get() = path
}
