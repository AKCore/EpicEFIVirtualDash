package com.buttonbox.ble.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

/**
 * Repository for loading and searching ECU variables from variables.json
 */
class VariableRepository(private val context: Context) {
    
    private var variables: List<EcuVariable> = emptyList()
    private val gson = Gson()
    
    fun loadVariables(): List<EcuVariable> {
        if (variables.isNotEmpty()) return variables
        
        try {
            val inputStream = context.assets.open("variables.json")
            val reader = InputStreamReader(inputStream)
            val type = object : TypeToken<List<EcuVariable>>() {}.type
            variables = gson.fromJson(reader, type)
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
            variables = emptyList()
        }
        
        return variables
    }
    
    fun getOutputVariables(): List<EcuVariable> {
        return loadVariables().filter { it.isOutput }
    }
    
    fun searchVariables(query: String): List<EcuVariable> {
        val lowerQuery = query.lowercase()
        return loadVariables().filter { 
            it.name.lowercase().contains(lowerQuery) 
        }
    }
    
    fun getVariableByHash(hash: Int): EcuVariable? {
        return loadVariables().find { it.hash == hash }
    }
    
    fun getVariableByName(name: String): EcuVariable? {
        return loadVariables().find { it.name == name }
    }
    
    companion object {
        // GPS Speed gauge (special, not from ECU)
        val GPS_SPEED_GAUGE = GaugeConfig(
            variableHash = 0,
            variableName = "gpsSpeed",
            label = "GPS Speed",
            unit = "MPH",
            minValue = 0f,
            maxValue = 200f,
            position = GaugePosition.TOP,
            isGpsSpeed = true
        )
        
        // Common dashboard variables with their hashes
        val COMMON_GAUGES = listOf(
            GaugeConfig(-1093429509, "AFRValue", "AFR", "", 10f, 20f, 14.7f, 16f, DisplayType.GAUGE, GaugePosition.TOP),
            GaugeConfig(-2066867294, "baroPressure", "Baro", "kPa", 80f, 110f, null, null, DisplayType.NUMBER),
            GaugeConfig(309572379, "ambientTemp", "Ambient", "°C", -20f, 50f, null, null, DisplayType.NUMBER),
            GaugeConfig(-1777838088, "baseDwell", "Dwell", "ms", 0f, 10f, null, null, DisplayType.NUMBER),
            GaugeConfig(493641747, "baseIgnitionAdvance", "Timing", "°", -10f, 50f, null, null, DisplayType.GAUGE),
            GaugeConfig(459143268, "boostboostOutput", "Boost", "%", 0f, 100f, 80f, 95f, DisplayType.GAUGE),
        )
    }
}
