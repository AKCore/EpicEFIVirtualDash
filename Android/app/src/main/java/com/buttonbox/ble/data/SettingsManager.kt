package com.buttonbox.ble.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages persistent settings for the dashboard
 */
class SettingsManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("dashboard_settings", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val KEY_BUTTON_COUNT = "button_count"
        private const val KEY_BUTTON_COLUMNS = "button_columns"
        private const val KEY_SPEED_UNIT = "speed_unit"
        private const val KEY_GAUGES = "gauges"
        private const val KEY_BUTTONS = "buttons"
        private const val KEY_ECU_ID = "ecu_id"
        private const val KEY_SHOW_SPEEDOMETER = "show_speedometer"
        private const val KEY_DATA_RATE = "data_rate"
    }
    
    var buttonCount: Int
        get() = prefs.getInt(KEY_BUTTON_COUNT, 16)
        set(value) = prefs.edit().putInt(KEY_BUTTON_COUNT, value.coerceIn(1, 16)).apply()
    
    var buttonColumns: Int
        get() = prefs.getInt(KEY_BUTTON_COLUMNS, 4)
        set(value) = prefs.edit().putInt(KEY_BUTTON_COLUMNS, value.coerceIn(2, 4)).apply()
    
    var speedUnit: SpeedUnit
        get() = SpeedUnit.valueOf(prefs.getString(KEY_SPEED_UNIT, SpeedUnit.MPH.name) ?: SpeedUnit.MPH.name)
        set(value) = prefs.edit().putString(KEY_SPEED_UNIT, value.name).apply()
    
    var ecuId: Int
        get() = prefs.getInt(KEY_ECU_ID, 1)
        set(value) = prefs.edit().putInt(KEY_ECU_ID, value.coerceIn(0, 255)).apply()
    
    var showSpeedometer: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SPEEDOMETER, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_SPEEDOMETER, value).apply()
    
    // Data polling rate in Hz (requests per second)
    var dataRateHz: Int
        get() = prefs.getInt(KEY_DATA_RATE, 20)  // Default 20Hz
        set(value) = prefs.edit().putInt(KEY_DATA_RATE, value.coerceIn(1, 60)).apply()
    
    // Convert Hz to milliseconds delay between requests
    val dataDelayMs: Long
        get() = (1000L / dataRateHz)
    
    var buttons: List<ButtonConfig>
        get() {
            val json = prefs.getString(KEY_BUTTONS, null) ?: return (0 until 16).map { ButtonConfig(id = it) }
            return try {
                val type = object : TypeToken<List<ButtonConfig>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                (0 until 16).map { ButtonConfig(id = it) }
            }
        }
        set(value) {
            val json = gson.toJson(value)
            prefs.edit().putString(KEY_BUTTONS, json).apply()
        }
    
    fun updateButton(buttonId: Int, config: ButtonConfig) {
        val current = buttons.toMutableList()
        val index = current.indexOfFirst { it.id == buttonId }
        if (index >= 0) {
            current[index] = config
        } else {
            current.add(config)
        }
        buttons = current
    }
    
    fun getButton(buttonId: Int): ButtonConfig {
        return buttons.find { it.id == buttonId } ?: ButtonConfig(id = buttonId)
    }
    
    var gauges: List<GaugeConfig>
        get() {
            val json = prefs.getString(KEY_GAUGES, null) 
                ?: return listOf(VariableRepository.GPS_SPEED_GAUGE) + VariableRepository.COMMON_GAUGES.take(1)
            return try {
                val type = object : TypeToken<List<GaugeConfig>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                listOf(VariableRepository.GPS_SPEED_GAUGE) + VariableRepository.COMMON_GAUGES.take(1)
            }
        }
        set(value) {
            val json = gson.toJson(value)
            prefs.edit().putString(KEY_GAUGES, json).apply()
        }
    
    fun addGauge(gauge: GaugeConfig) {
        val current = gauges.toMutableList()
        // Don't add duplicates
        if (current.none { it.variableHash == gauge.variableHash }) {
            current.add(gauge)
            gauges = current
        }
    }
    
    fun removeGauge(variableHash: Int) {
        gauges = gauges.filter { it.variableHash != variableHash }
    }
    
    fun getDashboardConfig(): DashboardConfig {
        return DashboardConfig(
            buttonCount = buttonCount,
            buttonColumns = buttonColumns,
            buttons = buttons,
            gauges = gauges,
            showSpeedometer = showSpeedometer,
            speedUnit = speedUnit
        )
    }
}
