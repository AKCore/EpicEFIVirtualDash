package com.buttonbox.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.buttonbox.ble.data.ButtonConfig
import com.buttonbox.ble.data.ButtonMode
import com.buttonbox.ble.data.DashboardConfig
import com.buttonbox.ble.data.DisplayType
import com.buttonbox.ble.data.GaugeConfig
import com.buttonbox.ble.data.GaugePosition
import com.buttonbox.ble.data.SettingsManager
import com.buttonbox.ble.data.SpeedUnit
import com.buttonbox.ble.data.VariableRepository
import com.buttonbox.ble.databinding.ActivityMainBinding
import com.google.android.gms.location.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity(), BleManager.BleCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var variableRepository: VariableRepository
    private lateinit var settingsManager: SettingsManager
    
    private val buttons = mutableListOf<Button>()
    private val buttonConfigs = mutableListOf<ButtonConfig>()
    private val toggleStates = mutableMapOf<Int, Boolean>() // buttonId -> isOn
    private val gaugeViews = ConcurrentHashMap<Int, TextView>() // hash -> TextView
    private var gpsSpeedView: TextView? = null  // Special view for GPS speed
    private var currentButtonMask = 0
    
    // Configuration - loaded from SettingsManager
    private var config = DashboardConfig()
    
    // Variable values cache
    private val variableValues = ConcurrentHashMap<Int, Float>()

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startConnection()
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { updateSpeed(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bleManager = BleManager(this)
        bleManager.setCallback(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        variableRepository = VariableRepository(this)
        settingsManager = SettingsManager(this)

        if (!bleManager.initialize()) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Load config from settings
        config = settingsManager.getDashboardConfig()
        
        setupUI()
        checkPermissionsAndStart()
    }

    private fun setupUI() {
        setupButtons()
        setupGauges()
        
        binding.btnConnect.setOnClickListener {
            if (bleManager.isConnected) {
                bleManager.disconnect()
            } else {
                checkPermissionsAndConnect()
            }
        }
        
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupButtons() {
        val grid = binding.buttonGrid
        grid.removeAllViews()
        buttons.clear()
        buttonConfigs.clear()
        
        val buttonCount = config.buttonCount.coerceIn(1, 16)
        val columns = config.buttonColumns.coerceIn(2, 4)
        val rows = (buttonCount + columns - 1) / columns
        
        grid.columnCount = columns
        grid.rowCount = rows
        
        for (i in 0 until buttonCount) {
            val btnConfig = config.buttons.find { it.id == i } ?: ButtonConfig(id = i)
            buttonConfigs.add(btnConfig)
            
            // Initialize toggle state if not set
            if (!toggleStates.containsKey(i)) {
                toggleStates[i] = false
            }
            
            val isToggleOn = toggleStates[i] == true && btnConfig.mode == ButtonMode.TOGGLE
            val defaultOffColor = ContextCompat.getColor(this, R.color.button_normal)
            val defaultOnColor = ContextCompat.getColor(this, R.color.button_pressed)
            
            val button = MaterialButton(this).apply {
                // Show label or number
                text = if (btnConfig.label.isNotEmpty()) btnConfig.label else "${i + 1}"
                textSize = if (buttonCount > 8) 14f else 18f
                setTextColor(if (isToggleOn) Color.BLACK else Color.WHITE)
                
                // Set initial color based on toggle state
                val bgColor = if (isToggleOn) {
                    btnConfig.colorOn ?: defaultOnColor
                } else {
                    btnConfig.colorOff ?: defaultOffColor
                }
                setBackgroundColor(bgColor)
                
                strokeColor = android.content.res.ColorStateList.valueOf(
                    if (isToggleOn) ContextCompat.getColor(context, R.color.accent_orange)
                    else ContextCompat.getColor(context, R.color.button_border)
                )
                strokeWidth = 2
                cornerRadius = 12
                elevation = 0f
                stateListAnimator = null
                
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(i % columns, 1f)
                    rowSpec = GridLayout.spec(i / columns, 1f)
                    setMargins(6, 6, 6, 6)
                }
                
                post {
                    minimumHeight = width
                    requestLayout()
                }
                
                setOnTouchListener { _, event ->
                    handleButtonTouch(i, event)
                    true
                }
                
                // Long press to edit button
                setOnLongClickListener {
                    showButtonEditDialog(i)
                    true
                }
            }
            
            buttons.add(button)
            grid.addView(button)
        }
    }

    private fun setupGauges() {
        val topContainer = binding.topGaugesContainer
        val secondaryContainer = binding.gaugesContainer
        
        topContainer.removeAllViews()
        secondaryContainer.removeAllViews()
        gaugeViews.clear()
        gpsSpeedView = null
        
        // Split gauges by position
        val topGauges = config.gauges.filter { it.position == GaugePosition.TOP }
        val secondaryGauges = config.gauges.filter { it.position == GaugePosition.SECONDARY }
        
        // Setup top gauges (2 columns, larger)
        if (topGauges.isEmpty()) {
            topContainer.visibility = View.GONE
        } else {
            topContainer.visibility = View.VISIBLE
            topContainer.columnCount = 2
            
            for ((index, gauge) in topGauges.withIndex()) {
                val card = createGaugeCard(gauge, index, 2, isTopRow = true)
                topContainer.addView(card)
            }
        }
        
        // Setup secondary gauges (4 columns, smaller)
        if (secondaryGauges.isEmpty()) {
            secondaryContainer.visibility = View.GONE
        } else {
            secondaryContainer.visibility = View.VISIBLE
            val columns = minOf(4, secondaryGauges.size)
            secondaryContainer.columnCount = columns
            
            for ((index, gauge) in secondaryGauges.withIndex()) {
                val card = createGaugeCard(gauge, index, columns, isTopRow = false)
                secondaryContainer.addView(card)
            }
        }
    }

    private fun createGaugeCard(gauge: GaugeConfig, index: Int, columns: Int, isTopRow: Boolean = false): View {
        val card = MaterialCardView(this).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(index % columns, 1f)
                rowSpec = GridLayout.spec(index / columns)
                setMargins(4, 4, 4, 4)
            }
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_dark))
            strokeColor = ContextCompat.getColor(context, R.color.button_border)
            strokeWidth = 1
            radius = if (isTopRow) 20f else 16f
            elevation = 0f
        }
        
        // Larger padding for top row
        val vertPadding = if (isTopRow) 24 else 12
        val horzPadding = if (isTopRow) 16 else 8
        
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(horzPadding, vertPadding, horzPadding, vertPadding)
        }
        
        // Label at top
        val labelView = TextView(this).apply {
            text = gauge.label.uppercase()
            textSize = if (isTopRow) 11f else 9f
            setTextColor(ContextCompat.getColor(context, R.color.gauge_label))
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
        }
        
        // Large value in center - top row uses uniform large size
        val baseSize = if (isTopRow) {
            56f  // All top row gauges same size
        } else {
            when (gauge.displayType) {
                DisplayType.GAUGE -> 32f
                DisplayType.NUMBER -> 28f
                DisplayType.BAR -> 24f
                DisplayType.INDICATOR -> 20f
            }
        }
        
        val valueView = TextView(this).apply {
            text = if (gauge.isGpsSpeed) "0" else "--"
            textSize = baseSize
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            includeFontPadding = false
        }
        
        // Unit below value - always show for top row to maintain consistent height
        val unitView = TextView(this).apply {
            text = gauge.unit.ifEmpty { if (isTopRow) " " else "" }  // Placeholder for top row
            textSize = if (isTopRow) 14f else 10f
            setTextColor(ContextCompat.getColor(context, R.color.accent_orange))
            gravity = Gravity.CENTER
        }
        
        // Store reference for updates
        if (gauge.isGpsSpeed) {
            gpsSpeedView = valueView
        } else {
            gaugeViews[gauge.variableHash] = valueView
        }
        
        content.addView(labelView)
        content.addView(valueView)
        // Always add unit for top row (for consistent height), only if non-empty for secondary
        if (isTopRow || gauge.unit.isNotEmpty()) {
            content.addView(unitView)
        }
        card.addView(content)
        
        return card
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleButtonTouch(buttonIndex: Int, event: MotionEvent) {
        val button = buttons.getOrNull(buttonIndex) as? MaterialButton ?: return
        val btnConfig = buttonConfigs.getOrNull(buttonIndex) ?: return
        
        val defaultOffColor = ContextCompat.getColor(this, R.color.button_normal)
        val defaultOnColor = ContextCompat.getColor(this, R.color.button_pressed)
        val offColor = btnConfig.colorOff ?: defaultOffColor
        val onColor = btnConfig.colorOn ?: defaultOnColor
        
        when (btnConfig.mode) {
            ButtonMode.MOMENTARY -> {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        currentButtonMask = currentButtonMask or (1 shl buttonIndex)
                        button.setBackgroundColor(onColor)
                        button.strokeColor = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.accent_orange)
                        )
                        button.setTextColor(Color.BLACK)
                        
                        if (bleManager.isConnected) {
                            bleManager.sendButtonMask(currentButtonMask)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        currentButtonMask = currentButtonMask and (1 shl buttonIndex).inv()
                        button.setBackgroundColor(offColor)
                        button.strokeColor = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.button_border)
                        )
                        button.setTextColor(Color.WHITE)
                        
                        if (bleManager.isConnected) {
                            bleManager.sendButtonMask(currentButtonMask)
                        }
                    }
                }
            }
            ButtonMode.TOGGLE -> {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    // Toggle visual state on press
                    val wasOn = toggleStates[buttonIndex] == true
                    val isNowOn = !wasOn
                    toggleStates[buttonIndex] = isNowOn
                    
                    // Update button appearance (visual only)
                    button.setBackgroundColor(if (isNowOn) onColor else offColor)
                    button.strokeColor = android.content.res.ColorStateList.valueOf(
                        if (isNowOn) ContextCompat.getColor(this, R.color.accent_orange)
                        else ContextCompat.getColor(this, R.color.button_border)
                    )
                    button.setTextColor(if (isNowOn) Color.BLACK else Color.WHITE)
                    
                    // Send single press (like momentary) - button ON then OFF
                    if (bleManager.isConnected) {
                        val pressedMask = currentButtonMask or (1 shl buttonIndex)
                        bleManager.sendButtonMask(pressedMask)
                    }
                }
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    // Send release - toggle doesn't hold the mask
                    if (bleManager.isConnected) {
                        bleManager.sendButtonMask(currentButtonMask)
                    }
                }
            }
        }
    }

    private fun showButtonEditDialog(buttonIndex: Int) {
        val currentConfig = buttonConfigs.getOrNull(buttonIndex) ?: ButtonConfig(id = buttonIndex)
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_button, null)
        val etLabel = dialogView.findViewById<android.widget.EditText>(R.id.etButtonLabel)
        val rgMode = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgButtonMode)
        val rbMomentary = dialogView.findViewById<android.widget.RadioButton>(R.id.rbMomentary)
        val rbToggle = dialogView.findViewById<android.widget.RadioButton>(R.id.rbToggle)
        
        // Set current values
        etLabel.setText(currentConfig.label)
        if (currentConfig.mode == ButtonMode.TOGGLE) {
            rbToggle.isChecked = true
        } else {
            rbMomentary.isChecked = true
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.DarkAlertDialog)
            .setTitle("Edit Button ${buttonIndex + 1}")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newLabel = etLabel.text.toString().trim()
                val newMode = if (rbToggle.isChecked) ButtonMode.TOGGLE else ButtonMode.MOMENTARY
                
                val newConfig = currentConfig.copy(
                    label = newLabel,
                    mode = newMode
                )
                
                settingsManager.updateButton(buttonIndex, newConfig)
                config = settingsManager.getDashboardConfig()
                
                // Reset toggle state if mode changed
                if (newMode != currentConfig.mode) {
                    toggleStates[buttonIndex] = false
                }
                
                setupButtons()
                Toast.makeText(this, "Button updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkPermissionsAndStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missing.isEmpty()) {
            startLocationUpdates()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkPermissionsAndConnect() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missing.isEmpty()) {
            startConnection()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500)
            .setMinUpdateIntervalMillis(200)
            .build()
        
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            log("Location permission denied")
        }
    }

    private fun updateSpeed(location: Location) {
        val speedMs = location.speed
        val speed = when (config.speedUnit) {
            SpeedUnit.MPH -> speedMs * 2.237f
            SpeedUnit.KMH -> speedMs * 3.6f
            SpeedUnit.MS -> speedMs
        }
        
        // Update GPS speed gauge if present
        gpsSpeedView?.text = speed.toInt().toString()
    }

    private fun startConnection() {
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_scanning))
        bleManager.startScan()
    }

    // BleManager.BleCallback
    override fun onConnectionStateChanged(connected: Boolean) {
        runOnUiThread {
            if (connected) {
                binding.tvStatus.text = "Online"
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
                binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_connected)
                // Start requesting variables
                startVariablePolling()
            } else {
                binding.tvStatus.text = "Offline"
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.statusIndicator.setBackgroundResource(R.drawable.status_dot)
            }
        }
    }

    private fun startVariablePolling() {
        lifecycleScope.launch {
            // Filter to only ECU gauges (not GPS)
            val ecuGauges = config.gauges.filter { !it.isGpsSpeed }
            if (ecuGauges.isEmpty()) return@launch
            
            // Get all hashes for batch request
            val hashes = ecuGauges.map { it.variableHash }
            
            while (bleManager.isConnected) {
                val cycleDelayMs = settingsManager.dataDelayMs
                
                // Send all variable requests in one batch
                bleManager.requestVariablesBatch(hashes)
                
                // Wait for cycle time before next batch
                kotlinx.coroutines.delay(cycleDelayMs)
            }
        }
    }

    override fun onVariableData(varHash: Int, value: Float) {
        variableValues[varHash] = value
        
        runOnUiThread {
            gaugeViews[varHash]?.let { tv ->
                tv.text = String.format("%.1f", value)
                
                // Update color based on thresholds
                val gauge = config.gauges.find { it.variableHash == varHash }
                gauge?.let { g ->
                    val color = when {
                        g.criticalThreshold != null && value >= g.criticalThreshold -> Color.RED
                        g.warningThreshold != null && value >= g.warningThreshold -> Color.YELLOW
                        else -> Color.WHITE
                    }
                    tv.setTextColor(color)
                }
            }
        }
    }

    override fun onLog(message: String) {
        runOnUiThread {
            val lines = binding.tvLog.text.toString().split("\n").takeLast(15)
            binding.tvLog.text = (lines + message).joinToString("\n")
            binding.scrollLog.post { binding.scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun log(message: String) {
        onLog(message)
    }

    override fun onScanResult(device: BluetoothDevice) {}

    override fun onResume() {
        super.onResume()
        // Reload config in case settings changed
        config = settingsManager.getDashboardConfig()
        setupButtons()
        setupGauges()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        }
        
        // Auto-reconnect if disconnected (e.g., after returning from settings)
        if (!bleManager.isConnected && hasAllPermissions()) {
            startConnection()
        }
    }
    
    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { 
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED 
        }
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rebuild UI when screen configuration changes (fold/unfold)
        binding.root.post {
            setupButtons()
            setupGauges()
        }
    }
}
