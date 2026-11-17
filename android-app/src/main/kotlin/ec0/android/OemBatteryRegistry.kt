/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Feature A6: OEM battery-guidance registry (plan §P4). ColorOS guidance
 * (docs/guides/coloros-setup.md) generalized to a per-OEM registry detected by
 * Build.MANUFACTURER — Samsung One UI is a first-class member (the Samsung A03
 * is the live test device), MIUI/HyperOS and stock round out the set.
 *
 * Each [OemBatteryGuidance] carries:
 *  - the auto-launch / background-management direct-intent candidates (tried in
 *    order, first resolvable wins),
 *  - human setup hints matching docs/guides/coloros-setup.md's checklist.
 */

package ec0.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** One OEM's battery/autostart guidance. Pure data — detection is unit-tested. */
data class OemBatteryGuidance(
    val oem: OemFamily,
    val label: String,
    /** (packageName, activity) candidates for the auto-launch manager, in order. */
    val autoLaunchComponents: List<Pair<String, String>>,
    /** Direct battery-management activity candidates (e.g. One UI Device care). */
    val batteryComponents: List<Pair<String, String>>,
    /** Human hint shown on the setup card. */
    val setupHint: String,
)

enum class OemFamily(val manufacturers: List<String>) {
    COLOROS(listOf("oppo", "realme", "oneplus")),
    ONEUI(listOf("samsung")),
    MIUI(listOf("xiaomi", "redmi", "poco")),
    STOCK(listOf()),
}

/** Pure detection: Build.MANUFACTURER → family (unit-tested). */
fun detectOemFamily(manufacturer: String): OemFamily {
    val normalized = manufacturer.trim().lowercase()
    for (family in OemFamily.entries) {
        if (family.manufacturers.any { normalized.contains(it) }) return family
    }
    return OemFamily.STOCK
}

/**
 * The registry. Every list is ordered most-specific-first; each helper tries its
 * candidates and falls back to the app's system details page.
 */
object OemBatteryRegistry {

    fun detect(manufacturer: String): OemBatteryGuidance = when (detectOemFamily(manufacturer)) {
        OemFamily.COLOROS -> colorOs
        OemFamily.ONEUI -> oneUi
        OemFamily.MIUI -> miui
        OemFamily.STOCK -> stock
    }

    private val colorOs = OemBatteryGuidance(
        oem = OemFamily.COLOROS,
        label = "ColorOS",
        autoLaunchComponents = listOf(
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        ),
        batteryComponents = listOf(
            "com.oplus.battery" to "com.oplus.battery.MainActivity",
            "com.coloros.oppoguardelf" to "com.coloros.oppoguardelf.ui.NormalBatteryActivity",
        ),
        setupHint = "Allow background usage + Auto-launch + Associate start; lock ec0 in Recents " +
            "(docs/guides/coloros-setup.md checklist).",
    )

    private val oneUi = OemBatteryGuidance(
        oem = OemFamily.ONEUI,
        label = "One UI",
        autoLaunchComponents = listOf(
            "com.samsung.android.sm.ui" to "com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.samsung.android.sm.ui" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        ),
        batteryComponents = listOf(
            "com.samsung.android.sm.devicesecurity" to "com.samsung.android.sm.devicecare.ui.MainActivity",
            "com.samsung.android.sm.ui" to "com.samsung.android.sm.devicecare.ui.MainActivity",
        ),
        setupHint = "Device care → Battery → Background usage limits → remove ec0 from " +
            "\"Sleeping apps\" and disable \"Put unused apps to sleep\".",
    )

    private val miui = OemBatteryGuidance(
        oem = OemFamily.MIUI,
        label = "MIUI / HyperOS",
        autoLaunchComponents = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.securitycenter" to "com.miui.powercenter.PowerSettings",
        ),
        batteryComponents = listOf(
            "com.miui.securitycenter" to "com.miui.powercenter.PowerMainActivity",
        ),
        setupHint = "Security app → Autostart → allow ec0; Battery saver → No restrictions " +
            "(MIUI kills background services without both).",
    )

    private val stock = OemBatteryGuidance(
        oem = OemFamily.STOCK,
        label = "Android",
        autoLaunchComponents = listOf(),
        batteryComponents = listOf(),
        setupHint = "Battery → Unrestricted (battery optimization exemption).",
    )

    /** Opens the first resolvable intent of [pairs], else the app details page. */
    fun openFirstResolvable(
        context: Context,
        pairs: List<Pair<String, String>>,
    ): Boolean {
        for ((pkg, cls) in pairs) {
            val intent = Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) {
                return true
            }
        }
        val appPkg = runCatching { context.packageName }.getOrNull().orEmpty().ifBlank { "ec0.android" }
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$appPkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(fallback); true }.getOrDefault(false)
    }
}
