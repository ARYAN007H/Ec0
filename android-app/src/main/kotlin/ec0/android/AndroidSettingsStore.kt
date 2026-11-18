/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * DataStore (Preferences) persistence for Android [SettingsState]:
 *   - Persists alias, deviceModel, deviceType, port, protocol, downloadDir,
 *     requirePin, pin, synergyProbeEnabled, darkTheme (System/Light/Dark),
 *     and trustedFingerprints across process restarts.
 *   - Supports both the singleton Android `Context.ec0SettingsDataStore` delegate
 *     and explicit file-backed `PreferenceDataStoreFactory` creation for JVM unit tests.
 */
package ec0.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ec0.core.localsend.DeviceType
import ec0.core.localsend.ProtocolType
import ec0.ui.models.SettingsState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.ec0SettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ec0_settings",
)

class AndroidSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.ec0SettingsDataStore)

    /**
     * Reactive stream of persisted [SettingsState] merged onto [defaultState].
     */
    fun settingsFlow(defaultState: SettingsState = SettingsState()): Flow<SettingsState> =
        dataStore.data.map { prefs -> mapPreferencesToSettings(prefs, defaultState) }

    /**
     * Loads the current persisted [SettingsState] snapshot, falling back to [defaultState]
     * for any unpopulated fields.
     */
    suspend fun load(defaultState: SettingsState = SettingsState()): SettingsState {
        val prefs = runCatching { dataStore.data.first() }.getOrNull() ?: return defaultState
        return mapPreferencesToSettings(prefs, defaultState)
    }

    /**
     * Atomically writes [state] to DataStore (Preferences).
     */
    suspend fun save(state: SettingsState) {
        dataStore.edit { prefs ->
            prefs[KEY_ALIAS] = state.alias
            prefs[KEY_DEVICE_MODEL] = state.deviceModel
            prefs[KEY_DEVICE_TYPE] = state.deviceType.name
            prefs[KEY_PORT] = state.port
            prefs[KEY_PROTOCOL] = state.protocol.name
            prefs[KEY_DOWNLOAD_DIR] = state.downloadDir
            prefs[KEY_REQUIRE_PIN] = state.requirePin
            prefs[KEY_PIN] = state.pin
            prefs[KEY_SYNERGY_PROBE_ENABLED] = state.synergyProbeEnabled
            prefs[KEY_DARK_THEME] = when (state.darkTheme) {
                null -> THEME_SYSTEM
                true -> THEME_DARK
                false -> THEME_LIGHT
            }
            prefs[KEY_TRUSTED_FINGERPRINTS] = state.trustedFingerprints
            prefs[KEY_THEME_SEED] = state.themeSeed
            if (state.ownFingerprint.isNotBlank()) {
                prefs[KEY_OWN_FINGERPRINT] = state.ownFingerprint
            }
        }
    }

    /**
     * Loads the current state, applies [transform], saves the updated state, and returns it.
     */
    suspend fun update(
        defaultState: SettingsState = SettingsState(),
        transform: (SettingsState) -> SettingsState,
    ): SettingsState {
        var result = defaultState
        dataStore.edit { prefs ->
            val current = mapPreferencesToSettings(prefs, defaultState)
            val updated = transform(current)
            prefs[KEY_ALIAS] = updated.alias
            prefs[KEY_DEVICE_MODEL] = updated.deviceModel
            prefs[KEY_DEVICE_TYPE] = updated.deviceType.name
            prefs[KEY_PORT] = updated.port
            prefs[KEY_PROTOCOL] = updated.protocol.name
            prefs[KEY_DOWNLOAD_DIR] = updated.downloadDir
            prefs[KEY_REQUIRE_PIN] = updated.requirePin
            prefs[KEY_PIN] = updated.pin
            prefs[KEY_SYNERGY_PROBE_ENABLED] = updated.synergyProbeEnabled
            prefs[KEY_DARK_THEME] = when (updated.darkTheme) {
                null -> THEME_SYSTEM
                true -> THEME_DARK
                false -> THEME_LIGHT
            }
            prefs[KEY_TRUSTED_FINGERPRINTS] = updated.trustedFingerprints
            prefs[KEY_THEME_SEED] = updated.themeSeed
            result = updated
        }
        return result
    }

    /**
     * Deep-integration A5: per-device capability grants (device-management
     * screen). Stored as one JSON map (deviceId → set of capability wire names)
     * under a dedicated DataStore key — separate from SettingsState on purpose.
     */
    fun deviceCapabilityGrants(): Map<String, Set<String>> = kotlinx.coroutines.runBlocking {
        runCatching {
            val prefs = dataStore.data.first()
            parseDeviceCaps(prefs[KEY_DEVICE_CAPS])
        }.getOrDefault(emptyMap())
    }

    fun deviceCapabilityGrantsFlow(): Flow<Map<String, Set<String>>> =
        dataStore.data.map { prefs -> parseDeviceCaps(prefs[KEY_DEVICE_CAPS]) }

    suspend fun setDeviceCapability(deviceId: String, wireName: String, granted: Boolean) {
        dataStore.edit { prefs ->
            val current = parseDeviceCaps(prefs[KEY_DEVICE_CAPS]).toMutableMap()
            val caps = current[deviceId]?.toMutableSet() ?: mutableSetOf()
            if (granted) caps.add(wireName) else caps.remove(wireName)
            if (caps.isEmpty()) current.remove(deviceId) else current[deviceId] = caps
            prefs[KEY_DEVICE_CAPS] = serializeDeviceCaps(current)
        }
    }

    private fun mapPreferencesToSettings(
        prefs: Preferences,
        defaultState: SettingsState,
    ): SettingsState {
        val alias = prefs[KEY_ALIAS]?.takeIf { it.isNotBlank() } ?: defaultState.alias
        val deviceModel = prefs[KEY_DEVICE_MODEL]?.takeIf { it.isNotBlank() } ?: defaultState.deviceModel
        val deviceType = prefs[KEY_DEVICE_TYPE]?.let { raw ->
            runCatching { DeviceType.valueOf(raw) }.getOrNull()
        } ?: defaultState.deviceType
        val port = prefs[KEY_PORT]?.takeIf { it in 1..65535 } ?: defaultState.port
        val protocol = prefs[KEY_PROTOCOL]?.let { raw ->
            runCatching { ProtocolType.valueOf(raw) }.getOrNull()
        } ?: defaultState.protocol
        val downloadDir = prefs[KEY_DOWNLOAD_DIR] ?: defaultState.downloadDir
        val requirePin = prefs[KEY_REQUIRE_PIN] ?: defaultState.requirePin
        val pin = prefs[KEY_PIN] ?: defaultState.pin
        val synergyProbe = prefs[KEY_SYNERGY_PROBE_ENABLED] ?: defaultState.synergyProbeEnabled
        val darkTheme = when (prefs[KEY_DARK_THEME]) {
            THEME_DARK -> true
            THEME_LIGHT -> false
            THEME_SYSTEM -> null
            else -> defaultState.darkTheme
        }
        val trustedFingerprints = prefs[KEY_TRUSTED_FINGERPRINTS]?.toSet()
            ?: defaultState.trustedFingerprints
        val themeSeed = prefs[KEY_THEME_SEED]?.takeIf { it.isNotBlank() } ?: defaultState.themeSeed
        val ownFingerprint = prefs[KEY_OWN_FINGERPRINT]?.takeIf { it.isNotBlank() } ?: defaultState.ownFingerprint

        return defaultState.copy(
            alias = alias,
            deviceModel = deviceModel,
            deviceType = deviceType,
            port = port,
            protocol = protocol,
            downloadDir = downloadDir,
            requirePin = requirePin,
            pin = pin,
            synergyProbeEnabled = synergyProbe,
            darkTheme = darkTheme,
            themeSeed = themeSeed,
            ownFingerprint = ownFingerprint,
            trustedFingerprints = trustedFingerprints,
        )
    }

    companion object {
        internal val KEY_ALIAS = stringPreferencesKey("alias")
        internal val KEY_DEVICE_MODEL = stringPreferencesKey("device_model")
        internal val KEY_DEVICE_TYPE = stringPreferencesKey("device_type")
        internal val KEY_PORT = intPreferencesKey("port")
        internal val KEY_PROTOCOL = stringPreferencesKey("protocol")
        internal val KEY_DOWNLOAD_DIR = stringPreferencesKey("download_dir")
        internal val KEY_REQUIRE_PIN = booleanPreferencesKey("require_pin")
        internal val KEY_PIN = stringPreferencesKey("pin")
        internal val KEY_SYNERGY_PROBE_ENABLED = booleanPreferencesKey("synergy_probe_enabled")
        internal val KEY_DARK_THEME = stringPreferencesKey("dark_theme")
        internal val KEY_THEME_SEED = stringPreferencesKey("theme_seed")
        internal val KEY_TRUSTED_FINGERPRINTS = stringSetPreferencesKey("trusted_fingerprints")
        internal val KEY_OWN_FINGERPRINT = stringPreferencesKey("own_fingerprint")
        internal val KEY_DEVICE_CAPS = stringPreferencesKey("device_capability_grants")

        private const val THEME_SYSTEM = "system"
        private const val THEME_DARK = "dark"
        private const val THEME_LIGHT = "light"

        /**
         * Creates an [AndroidSettingsStore] backed by `<storageDir>/ec0_settings.preferences_pb`.
         * Used by unit tests and custom storage scopes.
         */
        fun create(
            storageDir: File,
            scope: CoroutineScope,
            fileName: String = "ec0_settings.preferences_pb",
        ): AndroidSettingsStore {
            storageDir.mkdirs()
            val ds = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(storageDir, fileName) },
            )
            return AndroidSettingsStore(ds)
        }
    }
}

internal fun parseDeviceCaps(raw: String?): Map<String, Set<String>> {
    if (raw.isNullOrBlank()) return emptyMap()
    val fallback = emptyMap<String, Set<String>>()
    return runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw)
            as kotlinx.serialization.json.JsonObject
        val result = mutableMapOf<String, MutableSet<String>>()
        for ((deviceId, element) in obj) {
            val array = element as? kotlinx.serialization.json.JsonArray ?: continue
            val caps = mutableSetOf<String>()
            for (item in array) {
                val primitive = item as? kotlinx.serialization.json.JsonPrimitive ?: continue
                if (primitive.isString) caps.add(primitive.content)
            }
            result[deviceId] = caps
        }
        result as Map<String, Set<String>>
    }.getOrDefault(fallback)
}

internal fun serializeDeviceCaps(grants: Map<String, Set<String>>): String =
    kotlinx.serialization.json.buildJsonObject {
        grants.forEach { (deviceId, caps) ->
            put(
                deviceId,
                kotlinx.serialization.json.JsonArray(caps.map { kotlinx.serialization.json.JsonPrimitive(it) }),
            )
        }
    }.toString()
