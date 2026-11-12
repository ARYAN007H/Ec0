package ec0.core.localsend.api

import ec0.core.localsend.LocalsendV2

/**
 * HTTP API contract of LocalSend v2 — method, path and query parameters of
 * every route, extracted from the reference route table
 * (`packages/core/src/http/server/mod.rs:631-676`) and the client
 * (`packages/core/src/http/client/v2.rs`).
 *
 * Bodies: request/response DTOs in `ec0.core.localsend.dto`. `upload` and
 * `download` carry raw file bytes as the body (upload: request body = bytes,
 * response = plain text status; download: response body = bytes).
 */
enum class LocalsendV2Route(
    val method: Method,
    val path: String,
    val requiredQuery: List<String>,
    val optionalQuery: List<String> = emptyList(),
) {
    /** Device registration; request = [ec0.core.localsend.dto.RegisterDtoV2]. */
    REGISTER(Method.POST, "${LocalsendV2.API_PATH_V2}/register", emptyList()),

    /** Initiate a transfer; request = PrepareUploadRequestDtoV2, response = PrepareUploadResponseDtoV2. */
    PREPARE_UPLOAD(Method.POST, "${LocalsendV2.API_PATH_V2}/prepare-upload", emptyList()),

    /** Stream one file's bytes; the token is issued by prepare-upload. */
    UPLOAD(Method.POST, "${LocalsendV2.API_PATH_V2}/upload", listOf("sessionId", "fileId", "token")),

    /** Abort a session. */
    CANCEL(Method.POST, "${LocalsendV2.API_PATH_V2}/cancel", listOf("sessionId")),

    /** Download API: request an offered-files session; response = PrepareDownloadResponseDtoV2. */
    PREPARE_DOWNLOAD(
        Method.POST,
        "${LocalsendV2.API_PATH_V2}/prepare-download",
        listOf("sessionId"),
        listOf("pin"),
    ),

    /** Download API: fetch one file's bytes. */
    DOWNLOAD(Method.GET, "${LocalsendV2.API_PATH_V2}/download", listOf("sessionId", "fileId")),

    /** Device info endpoint (also served at /api/localsend/v1/info); response = InfoResponseDtoV2. */
    INFO(Method.GET, "${LocalsendV2.API_PATH_V2}/info", emptyList());

    enum class Method { GET, POST }

    /** Full wire path including the v2 prefix. */
    val wirePath: String get() = path
}
