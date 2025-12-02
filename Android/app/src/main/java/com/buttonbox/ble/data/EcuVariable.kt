package com.buttonbox.ble.data

import com.google.gson.annotations.SerializedName

/**
 * Represents a variable from the ECU that can be monitored
 */
data class EcuVariable(
    @SerializedName("name") val name: String,
    @SerializedName("hash") val hash: Int,
    @SerializedName("source") val source: String  // "output" or "config"
) {
    val isOutput: Boolean get() = source == "output"
}

/**
 * Represents a gauge/indicator configuration for the dashboard
 */
data class GaugeConfig(
    val variableHash: Int,
    val variableName: String,
    val label: String,
    val unit: String = "",
    val minValue: Float = 0f,
    val maxValue: Float = 100f,
    val warningThreshold: Float? = null,
    val criticalThreshold: Float? = null,
    val displayType: DisplayType = DisplayType.GAUGE,
    val position: GaugePosition = GaugePosition.SECONDARY,
    val isGpsSpeed: Boolean = false  // Special flag for GPS speed gauge
)

enum class DisplayType {
    GAUGE,      // Circular gauge
    BAR,        // Horizontal bar
    NUMBER,     // Large number display
    INDICATOR   // On/off indicator light
}

enum class GaugePosition {
    TOP,        // Top row (2 columns, larger)
    SECONDARY   // Secondary row (4 columns, smaller)
}

/**
 * Button behavior mode
 */
enum class ButtonMode {
    MOMENTARY,  // Active only while pressed
    TOGGLE      // Toggle on/off with each press
}

/**
 * Represents a button configuration
 */
data class ButtonConfig(
    val id: Int,                          // 0-15
    val label: String = "",               // Custom label (empty = show number)
    val mode: ButtonMode = ButtonMode.MOMENTARY,
    val colorOff: Int? = null,            // Color when off/released
    val colorOn: Int? = null              // Color when on/pressed
)

/**
 * Dashboard layout configuration
 */
data class DashboardConfig(
    val buttonCount: Int = 16,
    val buttonColumns: Int = 4,
    val buttons: List<ButtonConfig> = (0 until 16).map { ButtonConfig(id = it) },
    val gauges: List<GaugeConfig> = emptyList(),
    val showSpeedometer: Boolean = true,
    val speedUnit: SpeedUnit = SpeedUnit.MPH
)

enum class SpeedUnit {
    MPH, KMH, MS
}
