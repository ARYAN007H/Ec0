# ec0

Unified Android↔desktop ecosystem app: native **O+ Synergy protocol**
(reverse-engineered, see [docs/re/oplus-connect-findings.md](docs/re/oplus-connect-findings.md))
with **KDE Connect** and **LocalSend** protocols as the compatibility layer —
one app, Apple-Continuity-grade features, no OEM gating.

## Layout

| Path | What |
|---|---|
| `core/` | Kotlin Multiplatform protocol core shared by phone + desktop |
| `desktop/` | Compose Multiplatform desktop client (JVM) |
| `android-app/` | Android app (lands after first core milestone) |
| `docs/re/` | Reverse-engineering findings, protos, protocol catalog |
| `agentchat.md` | Multi-agent coordination hub — **agents start there** |

## Build

```bash
./gradlew build          # all modules
./gradlew :desktop:run   # desktop shell
```

Toolchain: JDK 17+ (Gradle toolchains pin the compile target). The Android
module additionally needs `local.properties` with `sdk.dir`.

## License

GPL-3.0 — see [LICENSE](LICENSE). scrcpy (Apache-2.0) and Syncthing (MPL-2.0)
ship as separate unmodified binaries, not merged source.
