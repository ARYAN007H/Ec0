# `ec0-engine` — ColorOS / OEM Background Resilience & Desktop Integration Guide

**Document Status:** NORMATIVE (`v1.0`)  
**Workstream:** `ec0-engine` (Slice 7)  
**Target Modules:** `ec0.core.engine.Ec0EngineNode`, `android-app/`, `desktop/`

---

## 1. Why Stock OEM Stacks Fail (and Why Third-Party Daemons Get Killed)

Static and dynamic analysis of ColorOS / OxygenOS / RealmeUI (`docs/re/oplus-connect-findings.md` and `docs/guides/coloros-setup.md`) revealed three distinct failure modes that break cross-device continuity on Android:

1. **Aggressive Process Freezing & Killing (`OplusQualityProtect` & Athena `HansManager`):**
   - ColorOS freezes background apps within seconds of screen-off unless they hold an active `connectedDevice` foreground service notification and an active socket or wake lock.
   - `com.oplus.linker` survives only because it is installed in `/product/priv-app` with system-signature exemptions (`pc_connect_support=1`).
2. **Multicast Filtering on SoftAP / Wi-Fi Direct / Screen-Off Roaming:**
   - When the phone screen turns off, Android's Wi-Fi chipset often enters power-save mode and drops incoming UDP multicast frames (`224.0.0.251:5353`), or the user switches from home LAN to phone hotspot (`192.168.137.1`) or Wi-Fi Direct (`192.168.49.1`).
3. **Transient Socket Resets During Network Handoffs:**
   - A 2-second Wi-Fi AP roam or Doze maintenance window resets TCP connections, causing naive clipboard/notification daemons to drop events copied while the socket was reconnecting.

---

## 2. `ec0-engine` Resilience Architecture

### 2.1 Single-Service State Machine (`EngineConnectionState`)

Instead of `com.oplus.linker`'s multi-process Binder/gRPC/WebSocket split, `Ec0EngineNode` manages each paired peer through a deterministic 6-state lifecycle:

```text
DISCONNECTED ──► DISCOVERING ──► CONNECTING ──► AUTHENTICATING ──► CONNECTED
      ▲                               │                 │              │
      └───────────────────────────────┴────────► BACKOFF ◄─────────────┘
```

- **`DISCONNECTED`**: Initial state or explicitly stopped by user.
- **`DISCOVERING`**: Listening for `_ec0._tcp.local.` mDNS announcements and resolving candidate endpoints via the 4-tier `EndpointResolver`.
- **`CONNECTING`**: Walking `(host, port)` candidates across `[49880, 39888, 49888, 59888]`:
  1. Explicit QR / manual host override
  2. Live mDNS SRV/A/AAAA cache
  3. Persisted last-known peer IP (survives UDP multicast filtering on screen-off)
  4. SoftAP / Wi-Fi Direct gateways (`192.168.137.1`, `192.168.49.1`, `192.168.43.1`)
- **`AUTHENTICATING`**: Performing the 4-message mutual X25519 + Ed25519 handshake (`CLIENT_HELLO` → `SERVER_HELLO` → `CLIENT_AUTH` → `SERVER_AUTH_CONFIRM`).
- **`CONNECTED`**: Active AEAD session (`ChaCha20-Poly1305`) with 5-second RFC 6455 `PING`/`PONG` heartbeat. Immediately flushes any pending items in the `OfflineOutboxQueue`.
- **`BACKOFF`**: Full-jitter exponential backoff (`sleepMs = random(0, min(30_000, 500 * 2^attempt))`), with **instant wake** as soon as an mDNS announcement arrives from the peer or the OS reports a network interface change.

### 2.2 Bounded Offline Outbox Queue & Cross-Session Deduplication

When a user copies text on their phone or receives a notification while the laptop lid is closed (or while the socket is mid-reconnect), `Ec0EngineNode` does not drop the event:

1. **Bounded Outbox (`OfflineOutboxQueue`):**
   - Signs the `Ec0Envelope` at the original creation timestamp (`timestamp`, `seq`, `nonce`, `sig`) and appends it to a per-peer bounded FIFO queue (`maxSize = 64`, `TTL = 1 hour / 3,600,000 ms`).
   - If the queue is full, the oldest item is evicted; any item older than 1 hour is pruned before flush.
2. **Batch Flush on Re-Authentication (`cmd_type = "offline_flush"`):**
   - Immediately after the 4-message handshake reaches `CONNECTED`, the node sends a single `"offline_flush"` command (`category = "sys"`) containing a strictly increasing per-session `batch_seq` (`1, 2, ...`) and the array of signed inner envelopes.
3. **Normative Cross-Session Deduplication (`OfflineFlushDeduplicator`, Amendment 2):**
   - Because each new WebSocket session resets `ReplayWindow`, the receiver validates every inner envelope's original `Ed25519` signature, checks the 1-hour creation TTL, verifies strictly increasing intra-batch `seq`, and deduplicates across sessions by both:
     - Origin tuple: `(peerDeviceId, inner.timestamp, inner.nonce)`
     - Domain content ID: `clip_sha256` (clipboard), `notif_id:ts_ms` (notifications), `transfer_id` (file offers)

### 2.3 Resumable Bulk Transfers Across Disconnects

For large files or folders (`FileTransferService`), chunks written before a disconnect are persisted in `<filename>.ec0part` alongside `<filename>.ec0part.meta` (`transfer_id`, `file_id`, `size_bytes`, `sha256`, `chunk_size`, `next_chunk`). When the session reconnects and the sender re-offers the `transfer_id`, the receiver replies with `resume_from_chunk[file_id] = next_chunk`, resuming from the exact 256 KiB boundary without re-transmitting earlier chunks.

---

## 3. Desktop (`desktop/`) & Android (`android-app/`) Integration Seam

`Ec0EngineNode` is self-contained inside `ec0.core.engine` and exposes reactive Kotlin `StateFlow`s that map directly onto `:desktop`'s `DeviceSource` and UI state models:

| `Ec0EngineNode` Surface | Type | Purpose |
|---|---|---|
| `discoveredPeers` | `StateFlow<List<PeerInfo>>` | Live LAN peers discovered via `_ec0._tcp.local.` mDNS |
| `pinnedPeers` | `StateFlow<List<PinnedPeer>>` | Paired peers stored in `PinStore` (`TRUSTED_QR` / `TOFU_PINNED`) |
| `connectionStates` | `StateFlow<Map<String, EngineConnectionState>>` | Per-peer connection state machine |
| `transfers` | `StateFlow<Map<String, TransferProgressSnapshot>>` | Live outgoing/incoming file & folder transfer progress |
| `notifications` | `StateFlow<List<NotificationPostInfo>>` | Mirrored Android notifications sorted newest-first |
| `peerPresence` | `StateFlow<Map<String, PresenceBeaconInfo>>` | Remote peer battery %, charging state, screen-on state |
