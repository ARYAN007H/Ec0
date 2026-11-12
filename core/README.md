# ec0-core (Kotlin Multiplatform)

Shared protocol core for the phone app (Android) and desktop client (JVM).

Contents (growing):

- `ec0.core.synergy` — O+ Synergy protocol types reverse-engineered from
  `com.oplus.linker` 17.7.35: control-plane envelope (`SynergyMessage`),
  transport constants (ports, subnets, scenes). Evidence:
  `docs/re/oplus-connect-findings.md`, reconstructed protos under
  `docs/re/protos/` (agent-1), catalog under `docs/re/synergy-catalog.yaml`
  (agent-2).
- Planned: `ec0.core.localsend` (LocalSend v2 port) and `ec0.core.kdeconnect`
  (KDE Connect protocol) as the fallback layer.

Targets: `jvm` now; `android.jvm` target added with the android-app module.
