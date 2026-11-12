# ColorOS survival guide — ec0 background services on the OPPO A5x 5G

Device ground truth: **OPPO A5x 5G (CPH2733IN)**, ColorOS 15/16-era (Android 15+;
Oplus stack builds target SDK 36–37), 494 packages at session time.
Evidence: `docs/re/oplus-connect-findings.md` (§1–§3) + live adb observations
from the 2026-11-12 RE session. Items marked **[device-verified]** were
observed live on the A5x; **[standard]** are consistent ColorOS behaviors to
re-verify via the checklist at the bottom.

## Why ColorOS kills connectivity apps

ColorOS ships an aggressive background-management stack **[device-verified]**:

| Package | Role |
|---|---|
| `com.oplus.qualityprotect` (OplusQualityProtect, `/product/priv-app/`) | aggressive background app control ("quality" policing) |
| `com.oplus.battery` (`/system_ext/app/Battery/`) | battery app incl. background power management |
| `com.oplus.apprecover` (`/product/priv-app/MDSService/`) | app recovery/watchdog |

KDE Connect-style apps (persistent foreground service + `NotificationListener`
+ input-method service) are exactly the pattern this stack targets when
permissions are not granted. The good news **[device-verified]**: when granted,
both survive on this device — `enabled_notification_listeners` already carries
`org.kde.kdeconnect_tp/...NotificationReceiver` and
`com.sameerasw.essentials/...NotificationListener`, and KDE Connect's
`RemoteKeyboardService` is an enabled input method.

## Setup for ec0 (drives milestone M1 permission requests)

Work top to bottom; each step is a ColorOS Settings path **[standard]** plus
its adb check.

1. **Battery optimization exemption**
   - UI: Settings → Apps → App management → *ec0* → Battery usage →
     *Allow background usage* → choose *Allow* (not "Smart control").
     If present, also enable *Allow high background power consumption*.
   - adb check: `adb shell dumpsys deviceidle whitelist` — ec0's package
     should appear (with `org`/`all` line depending on how it was granted).

2. **Auto-launch (autostart) + associate start**
   - UI: Settings → Apps → *ec0* → *Auto-launch* → allow **Auto-launch**
     and **Associate start** (ColorOS gates background broadcasts/services
     behind these — the OplusQualityProtect behavior).
   - Without this, services die shortly after screen-off even with the
     battery exemption **[standard]**.

3. **Lock in Recents**
   - UI: Recents → long-press *ec0* card → *Lock*. Prevents the RAM-boost
     sweep from killing the process **[standard]**.

4. **Notification-listener access** (mirroring ec0's notification feature)
   - UI: Settings → Notifications → *Device & app notifications* → *ec0* →
     Allow.
   - adb check: `adb shell settings get secure enabled_notification_listeners`
     — ec0's `NotificationListener` must be listed (see pitfalls below).

5. **Keyboard service** (optional feature: PC-types-into-phone)
   - UI: Settings → Additional settings → Keyboard & input method → enable
     ec0's IME **[device-verified pattern]** — same mechanism KDE Connect's
     `RemoteKeyboardService` uses on this device.

6. **Sleep/standby optimization off for ec0**
   - UI: Settings → Battery → More settings → *Sleep standby optimization*
     OFF (affects deep doze behavior overnight) **[standard]**.

7. **Data saver** (if used)
   - UI: Settings → Mobile network → Data saver → allow *ec0* unrestricted,
     or ec0's LAN discovery (UDP) will be throttled on metered networks.

## adb-assisted commands (read-only or reversible)

```bash
# Verify notification listeners (read-only)
adb shell settings get secure enabled_notification_listeners

# Verify battery-optimization exemptions (read-only)
adb shell dumpsys deviceidle whitelist

# Open the battery-optimization settings screen directly
adb shell am start -a android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS

# Grant/deny doze whitelist from shell (reversible: '-' removes)
adb shell dumpsys deviceidle whitelist +<ec0.package>

# Verify the O+ Connect flag state (findings doc §2; do not change casually)
adb shell settings get secure pc_connect_support
```

## Known pitfalls we hit / expect on this device

1. **Notification-listener revocation** **[standard]** — ColorOS revokes
   `NotificationListenerService` access when the app is force-stopped, its
   data is cleared, or (sometimes) after app updates. Symptom: mirrored
   notifications silently stop. Detection: the listener disappears from
   `enabled_notification_listeners`. ec0 (M1) must detect this and surface a
   re-grant prompt instead of failing silently.
2. **Screen-off kill without autostart** **[standard]** — battery exemption
   alone is not enough; the Auto-launch whitelist (step 2) is what stops
   OplusQualityProtect from reaping the service after the screen turns off.
3. **OTA resets** **[standard]** — after system updates re-verify: battery
   exemptions, autostart, notification listeners, and (if you rely on it)
   the `pc_connect_support` flag — secure settings normally persist across
   OTAs, but verify with the checklist.
4. **App cloners / parallel space** **[standard]** — cloned instances get
   separate permissions; do not run ec0 in a clone/space if persistence
   matters.
5. **USB debugging + adb authorization** **[standard]** — may need
   re-authorization after OTA; ec0's docs should never require adb except for
   optional verification like the above.

## Verification checklist

| # | Check | Command / UI path | Expected |
|---|---|---|---|
| 1 | Notification listener active | `settings get secure enabled_notification_listeners` | ec0 listener present |
| 2 | Doze exemption | `dumpsys deviceidle whitelist` | ec0 package listed |
| 3 | Autostart allowed | Settings → Apps → ec0 → Auto-launch | both toggles on |
| 4 | Recents lock | Recents → ec0 card | lock icon shown |
| 5 | O+ Connect interop flag (optional) | `settings get secure pc_connect_support` | `1` (findings §2) |
| 6 | 24h survival | leave device overnight, then `adb shell pidof <ec0.package>` | PID stable across screen-off cycles |

## References

- `docs/re/oplus-connect-findings.md` §1–§3 — device packages, gating flags,
  running engine services (observed 2026-11-12).
- adb session observations 2026-11-12 (recorded in the findings doc and the
  project log): notification listeners, input methods, QS-tile customization,
  package inventory.
- Milestone M1 (ec0 plan): the permission requests this guide prepares for —
  foreground service, notification listener, battery-exemption prompt
  (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` flow), autostart onboarding.
