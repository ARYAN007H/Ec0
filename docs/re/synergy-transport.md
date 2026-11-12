# Synergy WebSocket transport — design notes (T10)

Companion to the T2 catalog (`synergy-catalog.yaml`, 76/76 reviewer-verified)
and the T4 dynamic-capture plan. Code:
`core/src/jvmMain/kotlin/ec0/core/synergy/` — tests in `core/src/jvmTest/`.

## Components

| File | Role | Upstream evidence |
|---|---|---|
| `SynergyEnvelope.kt` | wire-accurate envelope codec + base signing | `protocol/SynergyMessage.java` (`@SerializedName` keys), base path `WSCM:558` |
| `SynergyPortWalk.kt` | per-profile port walk | `RetryAndChangePortInterceptor.java` + call sites `WSCM:267/:481` |
| `SynergyTrust.kt` | pinned-cert TLS trust + permissive hostname | `trustManagerForCertificates` `WSCM:390-410`, verifier `oq3` `WSCM:466` |
| `Rfc6455.kt` | HTTP Upgrade + frame codec (masking, fragmentation, ping/pong/close) | RFC 6455; upstream delegated to OkHttp |
| `SynergyWsClient.kt` | connect(): port walk → TLS → upgrade → handshake policy → envelope send/recv | `WSCM:447-470` timeouts (connect 3 s, io 50 s, retryOnConnectionFailure) |

## Envelope (wire truth)

`{"msg_type":"cmd"|"info","msg_body":{…},"src_role":"synergy","category":…,
"timestamp":<ms>,"noncestr":<long>,"signature":<long>}` — keys are the
`@SerializedName` values of `protocol/SynergyMessage.java`; body schema per
`protocol/SynergyCmd.java` / `SynergyInfo.java` (T2 catalog).

**Base signing path** (WSCM:558, mirrored exactly and unit-pinned):
- `src_role = "synergy"` (`Config.SYNERGY_NOTIFICATION_CHANNEL_NAME`)
- `timestamp = System.currentTimeMillis()`
- `noncestr = new java.util.Random(timestamp).nextLong()` — **seeded**, hence deterministic and testable
- `signature = 0x0FFFFFFF` (268435455, constant on this path)

Non-base signature schemes are a T4 unknown; codec takes `SignedMeta`
overrides.

⚠ **commonMain divergence (agent-0 action queued)**: the pre-T2-review
`commonMain/ec0/core/synergy/SynergyMessage.kt` models `msg_type` as Int and
`noncestr`/`signature` as Strings, and lacks `category` — contradicting the
reviewer-verified schema. The jvmMain codec here is the wire truth; logged in
the hub for agent-0 to align or bless.

## Port-walk semantics

Call sites: `RetryAndChangePortInterceptor(list[0], list, list.size())` —
retry count = list size (5 for both profiles). Attempt sequence: initial
`firstPort`, then `SERVERS[i]` for each round — the interceptor's
`FirstPort = SERVERS[i]` reset is faithfully carried. The 1-arg vs 2-arg
`switchServer` decompile is ambiguous (JADX artifact); the **2-arg reading**
(replace current URL port with `SERVERS[i]`) reproduces the catalog's
reviewer-verified effective orders:
- computer: attempts `8080, 8080, 39888, 8080, 49888, 59888` → effective `8080, 39888, 49888, 59888`
- pad: attempts `8792, 8792, 39888, 8792, 49888, 59888` → effective `8792, 39888, 49888, 59888`
(the 1-arg reading never reaches the last port within RetryCount — recorded
here so T4 capture can adjudicate.)

## TLS trust

Trust anchor = the cert agent-0 extracted from the APK (`assets/server.cer`,
vendored `docs/re/certs/server_computer.pem`): self-signed RSA, CN=192.168.137.1
(the phone's SoftAP gateway IP), valid 2020→2030, **identical for both
profiles** (server_pad.cer = server.cer). Security rests on **SHA-256
fingerprint pinning** (`A8:92:CC:…:68:D9`) — chain validation is meaningless
for a self-signed OEM cert. Hostname verification is disabled at the raw
SSLSocket layer (`endpointIdentificationAlgorithm = null`), mirroring
upstream's custom verifier: the CN names the hotspot gateway but peers also
connect over Wi-Fi Direct/LAN addresses.

## Deviations (reviewed in PR)

1. **Raw SSLSocket + own RFC 6455 instead of ktor-client CIO**: the pinned
   trust + permissive hostname + per-attempt port walk combination isn't
   cleanly expressible through CIO's TLS settings on our pinned ktor version,
   and raw sockets give exact upstream parity (OkHttp + custom TrustManager +
   `oq3`). Wire behavior identical; the upgrade handshake is unit-tested
   (RFC 6455 §1.3 accept-key vector included).
2. **Blocking I/O** (no coroutines): the upstream interceptor is a blocking
   retry loop; session usage is a read loop. Coroutine adaptation can wrap
   this later on Dispatchers.IO without changing the wire layer.
3. **Envelope codec in jvmMain**, not commonMain: needs `java.util.Random`
   (seeded nonce) and `java.security` (TLS). commonMain keeps the platform-
   free constants (`SynergyTransport`).

## Unknowns → T4

1. WS URL path (default "/", never observed).
2. First-message handshake after upgrade — `SynergyHandshake.None|Raw`
   (kdeconnect's identity handshake does NOT apply here).
3. Non-base signature/nonce derivation for cast/transfer message classes.
4. Whether protocol v8 moves identity exchange post-TLS (upstream TODO at
   `LanLinkProvider`-adjacent code suggests a similar direction for Synergy).
