package io.homeassistant.companion.android.sensors

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.LOCATION_SERVICE
import android.content.Context.WIFI_SERVICE
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.getSystemService
import android.text.TextUtils
import android.util.Log
import androidx.annotation.RequiresApi

//import com.amap.api.location.AMapLocation
//import com.amap.api.location.AMapLocationClient
//import com.amap.api.location.AMapLocationClientOption
//import com.amap.api.location.AMapLocationListener
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.GCJ2WGS
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.bluetooth.BluetoothUtils
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.UpdateLocation
import io.homeassistant.companion.android.common.data.prefs.PrefsRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.notifications.DeviceCommandData
import io.homeassistant.companion.android.common.sensors.ProvidesSensor
import io.homeassistant.companion.android.common.sensors.SensorManager
import io.homeassistant.companion.android.common.sensors.SensorManager.BasicSensor.Setting
import io.homeassistant.companion.android.common.sensors.SensorReceiverBase
import io.homeassistant.companion.android.common.sensors.SensorRepository
import io.homeassistant.companion.android.database.sensor.SensorSetting
import io.homeassistant.companion.android.database.sensor.SensorSettingType
import io.homeassistant.companion.android.common.util.DisabledLocationHandler

import io.homeassistant.companion.android.database.location.LocationHistoryItem
import io.homeassistant.companion.android.database.location.LocationHistoryItemResult
import io.homeassistant.companion.android.database.location.LocationHistoryItemTrigger
import io.homeassistant.companion.android.database.sensor.Attribute
import io.homeassistant.companion.android.database.sensor.toSensorWithAttributes
import io.homeassistant.companion.android.location.HighAccuracyLocationService
import io.homeassistant.companion.android.notifications.MessagingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import timber.log.Timber
import androidx.core.content.edit

var lastTime = 0L
var lastTime2 = 0L

@AndroidEntryPoint
class LocationSensorManager :  BroadcastReceiver(), SensorManager {

    companion object {
        private const val SETTING_SEND_LOCATION_AS = "location_send_as"
        private const val SETTING_ACCURACY = "location_minimum_accuracy"
        private const val SETTING_ACCURATE_UPDATE_TIME = "location_minimum_time_updates"
        private const val SETTING_INCLUDE_SENSOR_UPDATE = "location_include_sensor_update"
        private const val SETTING_HIGH_ACCURACY_MODE = "location_ham_enabled"
        private const val SETTING_HIGH_ACCURACY_MODE_UPDATE_INTERVAL =
            "location_ham_update_interval"
        private const val SETTING_HIGH_ACCURACY_MODE_BLUETOOTH_DEVICES = "location_ham_only_bt_dev"
        private const val SETTING_HIGH_ACCURACY_MODE_ZONE = "location_ham_only_enter_zone"
        private const val SETTING_HIGH_ACCURACY_BT_ZONE_COMBINED = "location_ham_zone_bt_combined"
        private const val SETTING_HIGH_ACCURACY_MODE_TRIGGER_RANGE_ZONE =
            "location_ham_trigger_range"

        private const val SEND_LOCATION_AS_EXACT = "exact"
        private const val SEND_LOCATION_AS_ZONE_ONLY = "zone_only"
        private const val DEFAULT_MINIMUM_ACCURACY = 200
        private const val DEFAULT_UPDATE_INTERVAL_HA_SECONDS = 5
        private const val MINIMUM_UPDATE_INTERVAL_HA_SECONDS = 5
        private const val DEFAULT_TRIGGER_RANGE_METERS = 300

        private const val DEFAULT_LOCATION_INTERVAL: Long = 60000
        private const val DEFAULT_ACCURATE_UPDATE_TIME_MILLIS = 60000
        private const val DEFAULT_LOCATION_FAST_INTERVAL: Long = 30000
        private const val DEFAULT_LOCATION_MAX_WAIT_TIME: Long = 200000

        private const val ZONE_NAME_NOT_HOME = "not_home"

        const val ACTION_REQUEST_LOCATION_UPDATES =
            "io.homeassistant.companion.android.background.REQUEST_UPDATES"
        const val ACTION_REQUEST_ACCURATE_LOCATION_UPDATE =
            "io.homeassistant.companion.android.background.REQUEST_ACCURATE_UPDATE"
        const val ACTION_PROCESS_LOCATION =
            "io.homeassistant.companion.android.background.PROCESS_UPDATES"
        const val ACTION_PROCESS_HIGH_ACCURACY_LOCATION =
            "io.homeassistant.companion.android.background.PROCESS_HIGH_ACCURACY_UPDATES"
        const val ACTION_PROCESS_GEO =
            "io.homeassistant.companion.android.background.PROCESS_GEOFENCE"
        const val ACTION_FORCE_HIGH_ACCURACY =
            "io.homeassistant.companion.android.background.FORCE_HIGH_ACCURACY"

        @ProvidesSensor
        val backgroundLocation = SensorManager.BasicSensor(
            "location_background",
            "",
            commonR.string.basic_sensor_name_location_background,
            commonR.string.sensor_description_location_background,
            "mdi:map-marker-multiple",
            updateType = SensorManager.BasicSensor.UpdateType.LOCATION,
            settings = listOf(
                Setting.Options(
                    SETTING_SEND_LOCATION_AS,
                    SEND_LOCATION_AS_EXACT,
                    entries = listOf(SEND_LOCATION_AS_EXACT, SEND_LOCATION_AS_ZONE_ONLY),
                ),
                Setting.Number(SETTING_ACCURACY, DEFAULT_MINIMUM_ACCURACY),
                Setting.Toggle(SETTING_HIGH_ACCURACY_MODE, default = false),
                Setting.Number(SETTING_HIGH_ACCURACY_MODE_UPDATE_INTERVAL, DEFAULT_UPDATE_INTERVAL_HA_SECONDS),
                Setting.BluetoothDevices(SETTING_HIGH_ACCURACY_MODE_BLUETOOTH_DEVICES),
                Setting.Zones(SETTING_HIGH_ACCURACY_MODE_ZONE),
                Setting.Toggle(SETTING_HIGH_ACCURACY_BT_ZONE_COMBINED, default = false),
                Setting.Number(SETTING_HIGH_ACCURACY_MODE_TRIGGER_RANGE_ZONE, DEFAULT_TRIGGER_RANGE_METERS),
            ),
        )

        @ProvidesSensor
        val zoneLocation = SensorManager.BasicSensor(
            "zone_background",
            "",
            commonR.string.basic_sensor_name_location_zone,
            commonR.string.sensor_description_location_zone,
            "mdi:map-marker-radius",
            updateType = SensorManager.BasicSensor.UpdateType.LOCATION,
            settings = listOf(
                Setting.Number(SETTING_ACCURACY, DEFAULT_MINIMUM_ACCURACY),
            ),
        )

        @ProvidesSensor
        val singleAccurateLocation = SensorManager.BasicSensor(
            "accurate_location",
            "",
            commonR.string.basic_sensor_name_location_accurate,
            commonR.string.sensor_description_location_accurate,
            "mdi:crosshairs-gps",
            updateType = SensorManager.BasicSensor.UpdateType.LOCATION,
            settings = listOf(
                Setting.Number(SETTING_ACCURACY, DEFAULT_MINIMUM_ACCURACY),
                Setting.Number(SETTING_ACCURATE_UPDATE_TIME, DEFAULT_ACCURATE_UPDATE_TIME_MILLIS),
                Setting.Toggle(SETTING_INCLUDE_SENSOR_UPDATE, default = false),
            ),
        )

        @ProvidesSensor
        val highAccuracyMode = SensorManager.BasicSensor(
            "high_accuracy_mode",
            "binary_sensor",
            commonR.string.basic_sensor_name_high_accuracy_mode,
            commonR.string.sensor_description_high_accuracy_mode,
            "mdi:crosshairs-gps",
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC,
            updateType = SensorManager.BasicSensor.UpdateType.INTENT,
        )

        @ProvidesSensor
        val highAccuracyUpdateInterval = SensorManager.BasicSensor(
            "high_accuracy_update_interval",
            "sensor",
            commonR.string.basic_sensor_name_high_accuracy_interval,
            commonR.string.sensor_description_high_accuracy_interval,
            "mdi:timer",
            unitOfMeasurement = "seconds",
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC,
            updateType = SensorManager.BasicSensor.UpdateType.INTENT,
        )

        private var isBackgroundLocationSetup = false
        private var isZoneLocationSetup = false

        private var lastLocationSend = mutableMapOf<Int, Long>()
        private var lastLocationReceived = mutableMapOf<Int, Long>()
        private var lastUpdateLocation = mutableMapOf<Int, String?>()

        private var zones = mutableMapOf<Int, List<Entity>>()
        private var zonesLastReceived = mutableMapOf<Int, Long>()

        private var geofenceRegistered = false

        private var lastHighAccuracyMode = false
        private var lastHighAccuracyUpdateInterval = DEFAULT_UPDATE_INTERVAL_HA_SECONDS
        private var forceHighAccuracyModeOn = false
        private var forceHighAccuracyModeOff = false
        private var highAccuracyModeEnabled = false

        private var lastEnteredGeoZones: MutableList<String> = ArrayList()
        private var lastExitedGeoZones: MutableList<String> = ArrayList()

        private var lastHighAccuracyTriggerRange: Int = 0
        private var lastHighAccuracyZones: List<String> = ArrayList()

        enum class LocationUpdateTrigger(val isGeofence: Boolean = false) {
            HIGH_ACCURACY_LOCATION,
            BACKGROUND_LOCATION,
            GEOFENCE_ENTER(isGeofence = true),
            GEOFENCE_EXIT(isGeofence = true),
            GEOFENCE_DWELL(isGeofence = true),
            SINGLE_ACCURATE_LOCATION,
        }

        fun createRequestAccurateLocationUpdateIntent(context: Context): Intent = Intent(
            context,
            LocationSensorManager::class.java,
        ).apply {
            action = ACTION_REQUEST_ACCURATE_LOCATION_UPDATE
        }

        fun setHighAccuracyModeSetting(context: Context, enabled: Boolean) {
            val sensorDao = EntryPointAccessors.fromApplication(
                context.applicationContext,
                SensorManager.SensorManagerEntryPoint::class.java,
            ).sensorDao()
            runBlocking {
                sensorDao.add(
                    SensorSetting(
                        backgroundLocation.id,
                        SETTING_HIGH_ACCURACY_MODE,
                        enabled.toString(),
                        SensorSettingType.TOGGLE,
                    ),
                )
            }
        }

        fun getHighAccuracyModeIntervalSetting(context: Context): Int {
            val sensorDao = EntryPointAccessors.fromApplication(
                context.applicationContext,
                SensorManager.SensorManagerEntryPoint::class.java,
            ).sensorDao()
            return runBlocking {
                val sensorSettings = sensorDao.getSettings(backgroundLocation.id)
                sensorSettings.firstOrNull { it.name == SETTING_HIGH_ACCURACY_MODE_UPDATE_INTERVAL }?.value?.toIntOrNull()
                    ?: DEFAULT_UPDATE_INTERVAL_HA_SECONDS
            }
        }

        fun setHighAccuracyModeIntervalSetting(context: Context, updateInterval: Int) {
            val sensorDao = EntryPointAccessors.fromApplication(
                context.applicationContext,
                SensorManager.SensorManagerEntryPoint::class.java,
            ).sensorDao()
            runBlocking {
                sensorDao.add(
                    SensorSetting(
                        backgroundLocation.id,
                        SETTING_HIGH_ACCURACY_MODE_UPDATE_INTERVAL,
                        updateInterval.toString(),
                        SensorSettingType.NUMBER,
                    ),
                )
            }
        }

        // Upstream-style extensions (2026.9 architecture): callers import these from the
        // Companion and invoke them on SensorRepository.
        suspend fun SensorRepository.setHighAccuracyModeSetting(enabled: Boolean) {
            updateSettingValue(
                backgroundLocation.id,
                SETTING_HIGH_ACCURACY_MODE,
                enabled.toString(),
            )
        }

        suspend fun SensorRepository.setHighAccuracyModeIntervalSetting(updateInterval: Int) {
            updateSettingValue(
                backgroundLocation.id,
                SETTING_HIGH_ACCURACY_MODE_UPDATE_INTERVAL,
                updateInterval.toString(),
            )
        }
    }

    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    lateinit var latestContext: Context


    override fun onReceive(context: Context, intent: Intent) {
        latestContext = context

        sensorWorkerScope.launch {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED,
                ACTION_REQUEST_LOCATION_UPDATES,
                -> setupLocationTracking()

                ACTION_PROCESS_LOCATION,
                ACTION_PROCESS_HIGH_ACCURACY_LOCATION,
                -> handleLocationUpdate(intent)

                ACTION_PROCESS_GEO -> handleLocationUpdate(intent)
                ACTION_REQUEST_ACCURATE_LOCATION_UPDATE -> requestSingleAccurateLocation()
                ACTION_FORCE_HIGH_ACCURACY -> {
                    when (val command = intent.extras?.getString("command")) {
                        DeviceCommandData.TURN_ON, DeviceCommandData.TURN_OFF, MessagingManager.FORCE_ON -> {
                            val turnOn = command != DeviceCommandData.TURN_OFF
                            if (turnOn) {
                                Timber.d("Forcing of high accuracy mode enabled")
                            } else {
                                Timber.d("Forcing of high accuracy mode disabled")
                            }
                            forceHighAccuracyModeOn = turnOn
                            forceHighAccuracyModeOff = false
                            sensorRepository.setHighAccuracyModeSetting(turnOn)
                            setupBackgroundLocation()
                        }

                        MessagingManager.FORCE_OFF -> {
                            Timber.d("High accuracy mode forced off")
                            forceHighAccuracyModeOn = false
                            forceHighAccuracyModeOff = true
                            setupBackgroundLocation()
                        }

                        MessagingManager.HIGH_ACCURACY_SET_UPDATE_INTERVAL -> {
                            if (lastHighAccuracyMode) {
                                restartHighAccuracyService(getHighAccuracyModeIntervalSetting())
                            }
                        }
                    }
                }

                else -> Timber.w("Unknown intent action: ${intent.action}!")
            }
        }
    }

    private suspend fun getHighAccuracyModeIntervalSetting(): Int {
        val sensorSettings = sensorRepository.getSettings(backgroundLocation.id)
        return sensorSettings.firstOrNull {
            it.name == SETTING_HIGH_ACCURACY_MODE_UPDATE_INTERVAL
        }?.value?.toIntOrNull()
            ?: DEFAULT_UPDATE_INTERVAL_HA_SECONDS
    }

    private suspend fun setupLocationTracking() {
        if (!checkPermission(backgroundLocation.id)) {
            Timber.w("Not starting location reporting because of permissions.")
            return
        }

        val backgroundEnabled = isEnabled(latestContext, backgroundLocation)
        val zoneEnabled = isEnabled(latestContext, zoneLocation)

        try {
            if (!backgroundEnabled && !zoneEnabled) {
                removeAllLocationUpdateRequests()
                isBackgroundLocationSetup = false
                isZoneLocationSetup = false
            }
            if (!zoneEnabled && isZoneLocationSetup) {
                //removeGeofenceUpdateRequests()
                isZoneLocationSetup = false
            }
            if (!backgroundEnabled && isBackgroundLocationSetup) {
                removeBackgroundUpdateRequests()
                stopHighAccuracyService()
                isBackgroundLocationSetup = false
            }
            if (zoneEnabled && !isZoneLocationSetup) {
                isZoneLocationSetup = true
                //requestZoneUpdates()
            }
//            if (zoneEnabled && isZoneLocationSetup && geofenceRegistered != zoneServers) {
//                Timber.d("Zone enabled servers changed. Reconfigure zones.")
//                removeGeofenceUpdateRequests()
//                requestZoneUpdates()
//            }

            val now = System.currentTimeMillis()
            if (
                (!highAccuracyModeEnabled && isBackgroundLocationSetup) &&
                (lastLocationReceived.all { (it.value + (DEFAULT_LOCATION_MAX_WAIT_TIME * 2L)) < now })
            ) {
                Timber.d("Background location updates appear to have stopped, restarting location updates")
                isBackgroundLocationSetup = false
                //fusedLocationProviderClient?.flushLocations()
                removeBackgroundUpdateRequests()
            } else if (
                highAccuracyModeEnabled &&
                (lastLocationReceived.all { (it.value + (getHighAccuracyModeUpdateInterval().toLong() * 2000L)) < now })
            ) {
                Timber.d("High accuracy mode appears to have stopped, restarting high accuracy mode")
                isBackgroundLocationSetup = false
                stopHighAccuracyService()
            }

            setupBackgroundLocation(backgroundEnabled, zoneEnabled)
        } catch (e: Exception) {
            Timber.e(e, "Issue setting up location tracking")
        }
    }

    private suspend fun setupBackgroundLocation(backgroundEnabled: Boolean? = null, zoneEnabled: Boolean? = null) {
        var isBackgroundEnabled = backgroundEnabled
        var isZoneEnable = zoneEnabled
        if (isBackgroundEnabled == null) isBackgroundEnabled = isEnabled(backgroundLocation)
        if (isZoneEnable == null) isZoneEnable = isEnabled(zoneLocation)

        if (isBackgroundEnabled) {
            val updateIntervalHighAccuracySeconds = getHighAccuracyModeUpdateInterval()
            highAccuracyModeEnabled = getHighAccuracyModeState()
            val highAccuracyTriggerRange = getHighAccuracyModeTriggerRange()
            val highAccuracyZones = getHighAccuracyModeZones(false)

            if (!isBackgroundLocationSetup) {
                isBackgroundLocationSetup = true
                if (highAccuracyModeEnabled) {
                    startHighAccuracyService(updateIntervalHighAccuracySeconds)
                } else {
                    requestLocationUpdates()
                }
            } else {
                if (highAccuracyModeEnabled != lastHighAccuracyMode ||
                    updateIntervalHighAccuracySeconds != lastHighAccuracyUpdateInterval
                ) {
                    if (highAccuracyModeEnabled) {
                        Timber.d("High accuracy mode parameters changed. Enable high accuracy mode.")
                        if (updateIntervalHighAccuracySeconds != lastHighAccuracyUpdateInterval) {
                            restartHighAccuracyService(updateIntervalHighAccuracySeconds)
                        } else {
                            removeBackgroundUpdateRequests()
                            startHighAccuracyService(updateIntervalHighAccuracySeconds)
                        }
                    } else {
                        Timber.d("High accuracy mode parameters changed. Disable high accuracy mode.")
                        stopHighAccuracyService()
                        requestLocationUpdates()
                    }
                }

//                if (highAccuracyTriggerRange != lastHighAccuracyTriggerRange ||
//                    highAccuracyZones != lastHighAccuracyZones
//                ) {
//                    Timber.d("High accuracy mode geo parameters changed. Reconfigure zones.")
//                    removeGeofenceUpdateRequests()
//                    requestZoneUpdates()
//                }
            }

            val highAccuracyModeSettingEnabled = getHighAccuracyModeSetting()
            enableDisableSetting(latestContext, backgroundLocation, SETTING_HIGH_ACCURACY_MODE_UPDATE_INTERVAL, highAccuracyModeSettingEnabled)
            enableDisableSetting(latestContext, backgroundLocation, SETTING_HIGH_ACCURACY_MODE_BLUETOOTH_DEVICES, highAccuracyModeSettingEnabled)
            enableDisableSetting(latestContext, backgroundLocation, SETTING_HIGH_ACCURACY_MODE_ZONE, highAccuracyModeSettingEnabled && isZoneEnable)
            enableDisableSetting(latestContext, backgroundLocation, SETTING_HIGH_ACCURACY_MODE_TRIGGER_RANGE_ZONE, highAccuracyModeSettingEnabled && isZoneEnable)
            enableDisableSetting(latestContext, backgroundLocation, SETTING_HIGH_ACCURACY_BT_ZONE_COMBINED, highAccuracyModeSettingEnabled && isZoneEnable)

            lastHighAccuracyZones = highAccuracyZones
            lastHighAccuracyTriggerRange = highAccuracyTriggerRange
            lastHighAccuracyMode = highAccuracyModeEnabled
            lastHighAccuracyUpdateInterval = updateIntervalHighAccuracySeconds

            serverManager.servers().forEach {
                getSendLocationAsSetting(it.id) // Sets up the setting, value isn't used right now
            }
        }
    }

    private suspend fun restartHighAccuracyService(intervalInSeconds: Int) {
        onSensorUpdated(
            highAccuracyUpdateInterval,
            intervalInSeconds,
            highAccuracyUpdateInterval.statelessIcon,
            mapOf(),
        )
        SensorReceiver.updateAllSensors(applicationContext)
        HighAccuracyLocationService.restartService(applicationContext, intervalInSeconds)
    }

    private suspend fun startHighAccuracyService(intervalInSeconds: Int) {
        onSensorUpdated(
            highAccuracyMode,
            true,
            highAccuracyMode.statelessIcon,
            mapOf(),
        )
        onSensorUpdated(
            highAccuracyUpdateInterval,
            intervalInSeconds,
            highAccuracyUpdateInterval.statelessIcon,
            mapOf(),
        )
        SensorReceiver.updateAllSensors(applicationContext)
        HighAccuracyLocationService.startService(applicationContext, intervalInSeconds)
    }

    private suspend fun stopHighAccuracyService() {
        onSensorUpdated(
            highAccuracyMode,
            false,
            highAccuracyMode.statelessIcon,
            mapOf(),
        )
        SensorReceiver.updateAllSensors(applicationContext)
        HighAccuracyLocationService.stopService(applicationContext)
    }

    private suspend fun getHighAccuracyModeUpdateInterval(): Int {
        val updateIntervalHighAccuracySeconds = getSetting(
            latestContext,
            backgroundLocation,
            SETTING_HIGH_ACCURACY_MODE_UPDATE_INTERVAL,
            SensorSettingType.NUMBER,
            DEFAULT_UPDATE_INTERVAL_HA_SECONDS.toString(),
        )

        var updateIntervalHighAccuracySecondsInt = if (updateIntervalHighAccuracySeconds.isEmpty()) DEFAULT_UPDATE_INTERVAL_HA_SECONDS else updateIntervalHighAccuracySeconds.toInt()
        if (updateIntervalHighAccuracySecondsInt < 5) {
            updateIntervalHighAccuracySecondsInt = DEFAULT_UPDATE_INTERVAL_HA_SECONDS

            sensorRepository.setHighAccuracyModeIntervalSetting(updateIntervalHighAccuracySecondsInt)
        }
        return updateIntervalHighAccuracySecondsInt
    }

    private suspend fun getHighAccuracyModeState(): Boolean {
        val highAccuracyMode = getHighAccuracyModeSetting()

        if (!highAccuracyMode) return false

        val shouldEnableHighAccuracyMode = shouldEnableHighAccuracyMode()

        // As soon as the high accuracy mode should be enabled, disable the force_on of high accuracy mode!
        if (shouldEnableHighAccuracyMode && forceHighAccuracyModeOn) {
            Timber.d("Forcing of high accuracy mode disabled, because high accuracy mode had to be enabled anyway.")
            forceHighAccuracyModeOn = false
        }

        // As soon as the high accuracy mode shouldn't be enabled, disable the force_off of high accuracy mode!
        if (!shouldEnableHighAccuracyMode && forceHighAccuracyModeOff) {
            Timber.d("Forcing off of high accuracy mode disabled, because high accuracy mode had to be disabled anyway.")
            forceHighAccuracyModeOff = false
        }

        return if (forceHighAccuracyModeOn) {
            Timber.d("High accuracy mode enabled, because command_high_accuracy_mode was used to turn it on")
            true
        } else if (forceHighAccuracyModeOff) {
            Timber.d("High accuracy mode disabled, because command_high_accuracy_mode was used to force it off")
            false
        } else {
            shouldEnableHighAccuracyMode
        }
    }

    private suspend fun shouldEnableHighAccuracyMode(): Boolean {
        val highAccuracyModeBTDevicesSetting =
            getSetting(backgroundLocation, SETTING_HIGH_ACCURACY_MODE_BLUETOOTH_DEVICES)
        val highAccuracyModeBTDevices = highAccuracyModeBTDevicesSetting
            .split(", ")
            .mapNotNull { it.trim().ifBlank { null } }
            .toMutableList()
        val highAccuracyBtZoneCombined = getHighAccuracyBTZoneCombinedSetting()

        val useTriggerRange = getHighAccuracyModeTriggerRange() > 0
        val highAccuracyZones = getHighAccuracyModeZones(false)
        var highAccuracyExpZones = highAccuracyZones
        if (useTriggerRange) {
            // Use a trigger range, if defined
            highAccuracyExpZones = getHighAccuracyModeZones(true)
        }

        var btDevConnected = false
        var inZone = false
        var constraintsUsed = false

        if (highAccuracyModeBTDevices.isNotEmpty()) {
            constraintsUsed = true

            val bluetoothDevices = BluetoothUtils.getBluetoothDevices(applicationContext)

            // If any of the stored devices aren't a Bluetooth device address, try to match them to a device
            var updatedBtDeviceNames = false
            highAccuracyModeBTDevices.filter { !BluetoothAdapter.checkBluetoothAddress(it) }.forEach {
                val foundDevices = bluetoothDevices.filter { btDevice -> btDevice.name == it }
                if (foundDevices.isNotEmpty()) {
                    highAccuracyModeBTDevices.remove(it)
                    foundDevices.forEach { btDevice ->
                        if (!highAccuracyModeBTDevices.contains(btDevice.address)) {
                            highAccuracyModeBTDevices.add(btDevice.address)
                        }
                    }
                    updatedBtDeviceNames = true
                }
            }
            if (updatedBtDeviceNames) {
                sensorRepository.updateSettingValue(
                    backgroundLocation.id,
                    SETTING_HIGH_ACCURACY_MODE_BLUETOOTH_DEVICES,
                    highAccuracyModeBTDevices.joinToString().replace("[", "").replace("]", ""),
                )
            }

            btDevConnected = bluetoothDevices.any { it.connected && highAccuracyModeBTDevices.contains(it.address) }

            if (!forceHighAccuracyModeOn && !forceHighAccuracyModeOff) {
                if (!btDevConnected) {
                    Timber.d("High accuracy mode disabled, because defined ($highAccuracyModeBTDevices) bluetooth device(s) not connected (Connected devices: $bluetoothDevices)")
                } else {
                    Timber.d("High accuracy mode enabled, because defined ($highAccuracyModeBTDevices) bluetooth device(s) connected (Connected devices: $bluetoothDevices)")
                }
            }
        }

        if (highAccuracyZones.isNotEmpty()) {
            constraintsUsed = true

            // (Expanded) Zone entered
            val zoneExpEntered = lastEnteredGeoZones.isNotEmpty() && highAccuracyExpZones.containsAll(lastEnteredGeoZones)

            // Exits events are only used if expended zones are used. The exit events are used to determine the enter of the expanded zone from the original zone
            // Zone exited
            val zoneExited = useTriggerRange && lastExitedGeoZones.isNotEmpty() && highAccuracyZones.containsAll(lastExitedGeoZones)

            inZone = zoneExpEntered || zoneExited

            if (!forceHighAccuracyModeOn && !forceHighAccuracyModeOff) {
                if (!inZone) {
                    Timber.d("High accuracy mode disabled, because not in zone $highAccuracyExpZones")
                } else {
                    Timber.d("High accuracy mode enabled, because in zone $highAccuracyExpZones")
                }
            }
        }

        // true = High accuracy mode enabled
        // false = High accuracy mode disabled
        //
        // if BT device and zone are combined and BT device is connected AND in zone -> High accuracy mode enabled (true)
        // if BT device and zone are NOT combined and either BT Device is connected OR in Zone -> High accuracy mode enabled (true)
        // Else (NO BT dev connected and NOT in Zone), if min. one constraint is used ->  High accuracy mode disabled (false)
        //                                             if no constraint is used ->  High accuracy mode enabled (true)
        return when {
            highAccuracyBtZoneCombined && btDevConnected && inZone -> true
            !highAccuracyBtZoneCombined && (btDevConnected || inZone) -> true
            highAccuracyBtZoneCombined && !constraintsUsed -> false
            else -> !constraintsUsed
        }
    }

    private suspend fun getHighAccuracyModeSetting(): Boolean {
        return getToggleSetting(backgroundLocation, SETTING_HIGH_ACCURACY_MODE)
    }

    private suspend fun getHighAccuracyBTZoneCombinedSetting(): Boolean {
        return getToggleSetting(backgroundLocation, SETTING_HIGH_ACCURACY_BT_ZONE_COMBINED)
    }

    private suspend fun getSendLocationAsSetting(serverId: Int): String {
        return if (serverManager.getServer(serverId)?.version?.isAtLeast(2022, 2, 0) == true) {
            getSetting(backgroundLocation, SETTING_SEND_LOCATION_AS)
        } else {
            SEND_LOCATION_AS_EXACT
        }
    }

    private fun removeAllLocationUpdateRequests() {
        Timber.d("Removing all location requests.")
        removeBackgroundUpdateRequests()
    }

    private fun removeBackgroundUpdateRequests() {
        //mLocationClient?.stopLocation()
    }

    private suspend fun requestLocationUpdates() {
        if (!checkPermission(backgroundLocation.id)) {
            Timber.w("Not registering for location updates because of permissions.")
            return
        }
        Timber.d("Registering for location updates.")
        val amapKey = latestContext.getSharedPreferences("config", Context.MODE_PRIVATE)
            .getString("amapKey", "")
        //if (amapKey.isNullOrEmpty() || amapKey == "0") {
            getLocation(latestContext, amapKey == "0")
//        } else {
//            AMapLocationClient.updatePrivacyShow(latestContext, true, true)
//            AMapLocationClient.updatePrivacyAgree(latestContext, true)
//            AMapLocationClient.setApiKey(amapKey)
//
//            mLocationClient = AMapLocationClient(latestContext)
//
//            mLocationClient!!.setLocationListener(mLocationListener)
//            val mLocationOption = AMapLocationClientOption()
//
//            if (lastHighAccuracyMode) {
//                mLocationOption.locationMode =
//                    AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
//            } else {
//                mLocationOption.locationMode =
//                    AMapLocationClientOption.AMapLocationMode.Battery_Saving
//            }
//            if (lastHighAccuracyUpdateInterval > 999999) {
//                mLocationOption.isOnceLocation = true
//                mLocationOption.isOnceLocationLatest = true
//            } else {
//                if (lastHighAccuracyUpdateInterval < 10) lastHighAccuracyUpdateInterval = 10
//                mLocationOption.interval = lastHighAccuracyUpdateInterval * 1000L
//                mLocationOption.isOnceLocation = false
//
//            }
//            mLocationClient!!.setLocationOption(mLocationOption)
//            mLocationClient!!.startLocation()
//        }

    }

    var canCloseGps = 0
    private fun getLocation(context: Context, wifi: Boolean) {
        val locationManager =
            context.getSystemService(LOCATION_SERVICE) as LocationManager

        if (lastTime != 0L && System.currentTimeMillis() - lastTime < 30000) return
        lastTime = System.currentTimeMillis()
        canCloseGps = latestContext.getSharedPreferences("config", Context.MODE_PRIVATE)
            .getInt("canCloseGps", 0)
        checkGps(wifi)
        if (canCloseGps > 15) return

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            120000,
            5f,
            object : LocationListener {
                override fun onLocationChanged(it: Location) {
                    if (canCloseGps > 2) return
                    runBlocking {
                        getEnabledServers(
                            latestContext,
                            singleAccurateLocation,
                        ).forEach { serverId ->
                            sendLocationUpdate(it, serverId, wifi)
                        }
                    }
                }

            },
            Looper.getMainLooper(),
        )

        if (lastTime2 == 0L) {
            lastTime2 = System.currentTimeMillis()
        }
        if (System.currentTimeMillis() - lastTime2 > 180000 && canCloseGps < 2) {
            locationManager.requestSingleUpdate(
                LocationManager.NETWORK_PROVIDER,
                {
                    runBlocking {
                        getEnabledServers(
                            latestContext,
                            singleAccurateLocation,
                        ).forEach { serverId ->
                            sendLocationUpdate(it, serverId, wifi, true)
                        }
                    }
                },
                Looper.getMainLooper(),
            )
        }

        runBlocking {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?.let {
                    Timber.e("${it.latitude}:${it.longitude}")
                    if (lastTime2 != 0L && System.currentTimeMillis() - lastTime2 < 180000) return@let
                    getEnabledServers(
                        latestContext,
                        singleAccurateLocation,
                    ).forEach { serverId ->
                        sendLocationUpdate(it, serverId, wifi)
                    }
                }

        }
        // gps
        // return locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        // 网络定位
        //return locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    }

    private fun checkGps(wifi: Boolean) {
        if (isUsingWifi() && wifi) {
            canCloseGps++
        } else {
            canCloseGps = 0
        }
        latestContext.getSharedPreferences("config", Context.MODE_PRIVATE).edit {
            putInt("canCloseGps", canCloseGps)
        }
    }

    private fun isUsingWifi(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return isWifiQ29()
        }
        return isWifi()
    }

    private fun isWifi(): Boolean {
        val cm = latestContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
    }


    @RequiresApi(Build.VERSION_CODES.M)
    private fun isWifiQ29(): Boolean {
        val cm = latestContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }


    private suspend fun getGeocodedLocation(it: Location) {
        try {
            val latitude: Double = it.latitude
            val longitude: Double = it.longitude
            // 地理编辑器  如果想获取地理位置 使用地理编辑器将经纬度转换为省市区
            val geocoder = Geocoder(latestContext, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(latitude, longitude, 1) {
                    val address: Address = it[0]
                    runBlocking { sendGeocodedLocation(address) }
                }
            } else {
                runBlocking {
                    val it = geocoder.getFromLocation(latitude, longitude, 1)
                    if (!it.isNullOrEmpty()) {
                        val address: Address = it[0]
                        sendGeocodedLocation(address)
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private suspend fun sendGeocodedLocation(address: Address) {
        var mAddressLine: String? = address.getAddressLine(0)
        if (mAddressLine.isNullOrEmpty()) {
            mAddressLine =
                address.countryName + address.locality + address.subLocality + address.thoroughfare
        }
        onSensorUpdated(
            latestContext,
            GeocodeSensorManager.geocodedLocation,
            mAddressLine,
            "mdi:map",
            mapOf(
                "Latitude" to address.latitude,
                "Longitude" to address.longitude,
                "AdministrativeArea" to address.adminArea,
                "Country" to address.countryName,
                "CountryCode" to address.countryCode,
                "Locality" to address.locality,
                "SubLocality" to address.subLocality,
                "Thoroughfare" to address.thoroughfare,
            ),
        )
    }

    private suspend fun handleLocationUpdate(intent: Intent) {
        Timber.d("Received location update.")
        val serverIds = getEnabledServers(backgroundLocation)
        serverIds.forEach {
            lastLocationReceived[it] = System.currentTimeMillis()
        }
//        if (mLocationClient != null) {
//            mLocationClient?.startLocation()
//        } else {
            requestLocationUpdates()
      //  }
    }

    override suspend fun getAvailableSensors(context: Context): List<SensorManager.BasicSensor> {
        return if (DisabledLocationHandler.hasGPS(context)) {
            listOf(singleAccurateLocation, backgroundLocation, zoneLocation, highAccuracyMode, highAccuracyUpdateInterval)
        } else {
            listOf(backgroundLocation, zoneLocation, highAccuracyMode, highAccuracyUpdateInterval)
        }
    }

    private suspend fun sendLocationUpdate(
        location: Location,
        serverId: Int,
        wifi: Boolean,
        ignoreAccuracy: Boolean = false,
    ) {
        Timber.d(
                "Last Location: \nCoords:(${location.latitude}, ${location.longitude})\nAccuracy: ${location.accuracy}\nBearing: ${location.bearing}",
        )
        var accuracy = 0
        if (location.accuracy.toInt() >= 0) {
            accuracy = location.accuracy.toInt()
            val sensorDao = sensorDao(latestContext)
            val sensorSettings = sensorDao.getSettings(backgroundLocation.id)
            val minAccuracy = sensorSettings
                .firstOrNull { it.name == SETTING_ACCURACY }?.value?.toIntOrNull()
                ?: DEFAULT_MINIMUM_ACCURACY
            if (accuracy > minAccuracy ) return
        }
        val now = System.currentTimeMillis()

        val updateLocation: UpdateLocation
        val updateLocationString: String
        runBlocking {
            updateLocation = UpdateLocation(
                gps = listOf(location.latitude, location.longitude),
                gpsAccuracy = accuracy,
                locationName = null,
                inZones = null,
                speed = location.speed.toInt(),
                altitude = location.altitude.toInt(),
                course = location.bearing.toInt(),
                time = now,
                gpsTime = location.time,
                verticalAccuracy = if (Build.VERSION.SDK_INT >= 26) location.verticalAccuracyMeters.toInt() else 0,
            )
            updateLocationString = updateLocation.gps.toString()
        }

//        Log.d(TAG, "Begin evaluating if location update should be skipped")
//        if (now + 5000 < location.time && !highAccuracyModeEnabled) {
//            Log.d(
//                TAG,
//                "Skipping location update that came from the future. ${now + 5000} should always be greater than ${location.time}"
//            )
//            return
//        }

        if (location.time < (lastLocationSend[serverId]?:0) || (now - location.time) > 320000) {
            Timber.d(
                "Skipping old location update since time is before the last one we sent, received: ${location.time} last sent: $lastLocationSend",
            )
            return
        }

//        if (now - location.time < 320000) {
//            Log.d(
//                TAG,
//                "Received location that is ${now - location.time} milliseconds old, ${location.time} compared to $now with source ${location.provider}"
//            )
//            if (lastUpdateLocation == updateLocationString) {
//                if (now < lastLocationSend + 900000) {
//                    Log.d(TAG, "Duplicate location received, not sending to HA")
//                    return
//                }
//            } else {
//                if (now < lastLocationSend + 5000 && !highAccuracyModeEnabled) {
//                    Log.d(
//                        TAG,
//                        "New location update not possible within 5 seconds, not sending to HA"
//                    )
//                    return
//                }
//            }
//        } else {
//            Log.d(
//                TAG,
//                "Skipping location update due to old timestamp ${location.time} compared to $now"
//            )
//            return
//        }
        lastLocationSend[serverId] = now
        lastUpdateLocation[serverId] = updateLocationString
        lastTime2 = System.currentTimeMillis()
        getGeocodedLocation(location)
        checkGps(wifi)

        val geocodeIncludeLocation = getToggleSetting(
            GeocodeSensorManager.geocodedLocation,
            GeocodeSensorManager.SETTINGS_INCLUDE_LOCATION,
        )

        ioScope.launch {
            try {
                serverManager(latestContext).integrationRepository(serverId).updateLocation(updateLocation)
                //Timber.d("Location update sent successfully for $serverId as $updateLocationAs")
                lastLocationSend[serverId] = now
                lastUpdateLocation[serverId] = updateLocationString
                //logLocationUpdate(location, updateLocation, serverId, trigger, LocationHistoryItemResult.SENT)

                // Update Geocoded Location Sensor
                if (geocodeIncludeLocation) {
                    val intent = Intent(applicationContext, SensorReceiver::class.java)
                    intent.action = SensorReceiverBase.ACTION_UPDATE_SENSOR
                    intent.putExtra(
                        SensorReceiverBase.EXTRA_SENSOR_ID,
                        GeocodeSensorManager.geocodedLocation.id,
                    )
                    applicationContext.sendBroadcast(intent)
                }
            } catch (e: Exception) {
                Timber.e(e, "Could not update location for $serverId.")
                //logLocationUpdate(location, updateLocation, serverId, trigger, LocationHistoryItemResult.FAILED_SEND)
            }
        }
    }

    private fun getLocationUpdateIntent(isGeofence: Boolean): PendingIntent {
        val intent = Intent(applicationContext, LocationSensorManager::class.java)
        intent.action = if (isGeofence) ACTION_PROCESS_GEO else ACTION_PROCESS_LOCATION
        return PendingIntent.getBroadcast(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }


    private suspend fun getHighAccuracyModeTriggerRange(): Int {
        val enabled = isEnabled(zoneLocation)

        if (!enabled) return 0

        val highAccuracyTriggerRange = getSetting(
            latestContext,
            backgroundLocation,
            SETTING_HIGH_ACCURACY_MODE_TRIGGER_RANGE_ZONE,
            SensorSettingType.NUMBER,
            DEFAULT_TRIGGER_RANGE_METERS.toString(),
        )

        var highAccuracyTriggerRangeInt =
            highAccuracyTriggerRange.toIntOrNull() ?: DEFAULT_TRIGGER_RANGE_METERS
        if (highAccuracyTriggerRangeInt < 0) {
            highAccuracyTriggerRangeInt = DEFAULT_TRIGGER_RANGE_METERS

            val sensorDao = sensorDao(latestContext)
            sensorDao.add(
                SensorSetting(
                    backgroundLocation.id,
                    SETTING_HIGH_ACCURACY_MODE_TRIGGER_RANGE_ZONE,
                    highAccuracyTriggerRangeInt.toString(),
                    SensorSettingType.NUMBER,
                ),
            )
        }

        return highAccuracyTriggerRangeInt
    }

    private suspend fun getHighAccuracyModeZones(expandedZones: Boolean): List<String> {
        val enabled = isEnabled(zoneLocation)

        if (!enabled) return emptyList()

        val highAccuracyZones = getSetting(backgroundLocation, SETTING_HIGH_ACCURACY_MODE_ZONE)

        return if (highAccuracyZones.isNotEmpty()) {
            val expanded = if (expandedZones) "_expanded" else ""
            highAccuracyZones.split(",").map { it.trim() + expanded }
        } else {
            emptyList()
        }
    }

    private suspend fun requestSingleAccurateLocation() {
        if (!checkPermission(singleAccurateLocation.id)) {
            Timber.w("Not getting single accurate location because of permissions.")
            return
        }
        if (!isEnabled(singleAccurateLocation)) {
            Timber.w("Requested single accurate location but it is not enabled.")
            return
        }

        val now = System.currentTimeMillis()
        val sensorDao = sensorDao(latestContext)
        val fullSensor = sensorDao.getFull(singleAccurateLocation.id).toSensorWithAttributes()
        val latestAccurateLocation = fullSensor?.attributes?.firstOrNull { it.name == "lastAccurateLocationRequest" }?.value?.toLongOrNull() ?: 0L

        val sensorSettings = sensorDao.getSettings(singleAccurateLocation.id)
        val minAccuracy = sensorSettings
            .firstOrNull { it.name == SETTING_ACCURACY }?.value?.toIntOrNull()
            ?: DEFAULT_MINIMUM_ACCURACY
        sensorDao.add(SensorSetting(singleAccurateLocation.id, SETTING_ACCURACY, minAccuracy.toString(), SensorSettingType.NUMBER))
        val minTimeBetweenUpdates = sensorSettings
            .firstOrNull { it.name == SETTING_ACCURATE_UPDATE_TIME }?.value?.toIntOrNull()
            ?: 60000
        sensorDao.add(SensorSetting(singleAccurateLocation.id, SETTING_ACCURATE_UPDATE_TIME, minTimeBetweenUpdates.toString(), SensorSettingType.NUMBER))

        // Only update accurate location at most once a minute
        if (now < latestAccurateLocation + minTimeBetweenUpdates) {
            Timber.d("Not requesting accurate location, last accurate location was too recent")
            return
        }
        sensorDao.add(
            Attribute(
                singleAccurateLocation.id,
                "lastAccurateLocationRequest",
                now.toString(),
                "string",
            ),
        )
    }

    override fun docsLink(): String {
        return "https://companion.home-assistant.io/docs/core/location"
    }

    override val name: Int
        get() = commonR.string.sensor_name_location

    override fun requiredPermissions(sensorId: String): Array<String> {
        return when {
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) -> {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
            }
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) -> {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH,
                )
            }
            else -> {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH,
                )
            }
        }
    }

    override suspend fun requestSensorUpdate(
        context: Context,
    ) {
        latestContext = context
        if (isEnabled(context, zoneLocation) || isEnabled(context, backgroundLocation))
            setupLocationTracking()
        val sensorDao = sensorDao(latestContext)
        val sensorSetting = sensorDao.getSettings(singleAccurateLocation.id)
        val includeSensorUpdate =
            sensorSetting.firstOrNull { it.name == SETTING_INCLUDE_SENSOR_UPDATE }?.value ?: "false"
        if (includeSensorUpdate == "true") {
            if (isEnabled(context, singleAccurateLocation)) {
                context.sendBroadcast(
                    Intent(context, this.javaClass).apply {
                        action = ACTION_REQUEST_ACCURATE_LOCATION_UPDATE
                    },
                )
            }
        } else
            sensorDao.add(
                SensorSetting(
                    singleAccurateLocation.id,
                    SETTING_INCLUDE_SENSOR_UPDATE,
                    "false",
                    SensorSettingType.TOGGLE,
                ),
            )
    }

//    private fun addressUpdata(context: Context) {
//        var addressStr = amapLocation!!.address
//        val attributes = amapLocation.let {
//            mapOf(
//                "Administrative Area" to it!!.district,
//                "Country" to it.city,
//                "accuracy" to it.accuracy,
//                "altitude" to it.altitude,
//                "bearing" to it.bearing,
//                "provider" to it.provider,
//                "time" to it.time,
//                "Locality" to it.province,
//                "Latitude" to it.latitude,
//                "Longitude" to it.longitude,
//                "Postal Code" to it.cityCode,
//                "Thoroughfare" to it.street,
//                //"ISO Country Code" to it.cityCode,
//                "vertical_accuracy" to if (Build.VERSION.SDK_INT >= 26) it.verticalAccuracyMeters.toInt() else 0,
//            )
//        }
//        if (TextUtils.isEmpty(addressStr)) {
//            addressStr =
//                amapLocation!!.city + amapLocation!!.district + amapLocation!!.street + amapLocation!!.aoiName + amapLocation!!.floor
//        }
//        if (TextUtils.isEmpty(addressStr)) {
//            Timber.d("addressStr--${amapLocation!!.locationDetail}")
//            return
//        }
//        onSensorUpdated(
//            context,
//            GeocodeSensorManager.geocodedLocation,
//            addressStr,
//            "mdi:map",
//            attributes,
//        )
//    }

//    private fun logLocationUpdate(
//        location: Location?,
//        updateLocation: UpdateLocation?,
//        serverId: Int?,
//        trigger: LocationUpdateTrigger?,
//        result: LocationHistoryItemResult
//    ) = ioScope.launch {
//        if (location == null || !prefsRepository.isLocationHistoryEnabled()) return@launch
//
//        val historyTrigger = when (trigger) {
//            LocationUpdateTrigger.HIGH_ACCURACY_LOCATION -> LocationHistoryItemTrigger.FLP_FOREGROUND
//            LocationUpdateTrigger.BACKGROUND_LOCATION -> LocationHistoryItemTrigger.FLP_BACKGROUND
//            LocationUpdateTrigger.GEOFENCE_ENTER -> LocationHistoryItemTrigger.GEOFENCE_ENTER
//            LocationUpdateTrigger.GEOFENCE_EXIT -> LocationHistoryItemTrigger.GEOFENCE_EXIT
//            LocationUpdateTrigger.GEOFENCE_DWELL -> LocationHistoryItemTrigger.GEOFENCE_DWELL
//            LocationUpdateTrigger.SINGLE_ACCURATE_LOCATION -> LocationHistoryItemTrigger.SINGLE_ACCURATE_LOCATION
//            else -> LocationHistoryItemTrigger.UNKNOWN
//        }
//
//        try {
//            // Use updateLocation to preserve the 'send location as' setting
//            AppDatabase.getInstance(latestContext).locationHistoryDao().add(
//                LocationHistoryItem(
//                    trigger = historyTrigger,
//                    result = result,
//                    latitude = if (updateLocation != null) updateLocation.gps?.getOrNull(0) else location.latitude,
//                    longitude = if (updateLocation != null) updateLocation.gps?.getOrNull(1) else location.longitude,
//                    locationName = updateLocation?.locationName,
//                    accuracy = updateLocation?.gpsAccuracy ?: location.accuracy.toInt(),
//                    data = updateLocation?.toString(),
//                    serverId = serverId
//                )
//            )
//        } catch (e: Exception) {
//            // Context is null? Shouldn't happen but don't let the app crash.
//        }
//    }

    @Inject
    lateinit var prefsRepository: PrefsRepository

    private fun handleInject(context: Context) {
        // requestSensorUpdate is called outside onReceive, which usually handles injection.
        // Because we need the preferences for location history settings, inject it if required.
        if (!this::prefsRepository.isInitialized) {
            prefsRepository = EntryPointAccessors.fromApplication(
                context,
                LocationSensorManagerEntryPoint::class.java,
            ).prefsRepository()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface LocationSensorManagerEntryPoint {
        fun prefsRepository(): PrefsRepository
    }
}
