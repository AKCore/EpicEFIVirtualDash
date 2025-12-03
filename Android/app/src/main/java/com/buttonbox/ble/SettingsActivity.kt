package com.buttonbox.ble

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.buttonbox.ble.data.ButtonConfig
import com.buttonbox.ble.data.ButtonMode
import com.buttonbox.ble.data.GaugeConfig
import com.buttonbox.ble.data.GaugePosition
import com.buttonbox.ble.data.SettingsManager
import com.buttonbox.ble.data.SpeedUnit
import com.buttonbox.ble.data.VariableRepository
import com.buttonbox.ble.databinding.ActivitySettingsBinding
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var variableRepository: VariableRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var gaugeAdapter: GaugeAdapter
    
    private val addGaugeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Refresh gauge list when returning from AddGaugeActivity
            gaugeAdapter.updateGauges(settingsManager.gauges)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        variableRepository = VariableRepository(this)
        settingsManager = SettingsManager(this)
        
        setupButtonCountSlider()
        setupButtonConfigGrid()
        setupSpeedUnitSpinner()
        setupDataRateSlider()
        setupGaugesList()  // Must be before setupVariableSearch
        setupVariableSearch()
        setupEcuId()
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh gauge list in case it was modified
        if (::gaugeAdapter.isInitialized) {
            gaugeAdapter.updateGauges(settingsManager.gauges)
        }
    }

    private fun setupButtonCountSlider() {
        val currentCount = settingsManager.buttonCount
        binding.sliderButtonCount.apply {
            max = 15 // 1-16 buttons
            progress = currentCount - 1
            
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    binding.tvButtonCount.text = "Buttons: ${progress + 1}"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    settingsManager.buttonCount = (seekBar?.progress ?: 15) + 1
                    setupButtonConfigGrid() // Rebuild grid when count changes
                }
            })
        }
        binding.tvButtonCount.text = "Button Count: $currentCount"
    }

    private fun setupDataRateSlider() {
        val currentRate = settingsManager.dataRateHz.coerceAtMost(60)
        binding.sliderDataRate.apply {
            max = 59  // 1-60 Hz
            progress = currentRate - 1
            
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    binding.tvDataRate.text = "${progress + 1} Hz"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    settingsManager.dataRateHz = (seekBar?.progress ?: 19) + 1
                }
            })
        }
        binding.tvDataRate.text = "$currentRate Hz"
    }

    private fun setupButtonConfigGrid() {
        val grid = binding.buttonConfigGrid
        grid.removeAllViews()
        
        val buttonCount = settingsManager.buttonCount
        val columns = 4
        grid.columnCount = columns
        
        for (i in 0 until buttonCount) {
            val btnConfig = settingsManager.getButton(i)
            
            val button = MaterialButton(this).apply {
                text = if (btnConfig.label.isNotEmpty()) btnConfig.label else "${i + 1}"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(ContextCompat.getColor(context, R.color.button_normal))
                strokeColor = android.content.res.ColorStateList.valueOf(
                    if (btnConfig.mode == ButtonMode.TOGGLE) 
                        ContextCompat.getColor(context, R.color.accent_orange)
                    else 
                        ContextCompat.getColor(context, R.color.button_border)
                )
                strokeWidth = 2
                cornerRadius = 8
                elevation = 0f
                stateListAnimator = null
                
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(i % columns, 1f)
                    rowSpec = GridLayout.spec(i / columns)
                    setMargins(4, 4, 4, 4)
                }
                
                setOnClickListener {
                    showButtonEditDialog(i)
                }
            }
            
            grid.addView(button)
        }
    }

    private fun showButtonEditDialog(buttonIndex: Int) {
        val currentConfig = settingsManager.getButton(buttonIndex)
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_button, null)
        val etLabel = dialogView.findViewById<android.widget.EditText>(R.id.etButtonLabel)
        val rbMomentary = dialogView.findViewById<android.widget.RadioButton>(R.id.rbMomentary)
        val rbToggle = dialogView.findViewById<android.widget.RadioButton>(R.id.rbToggle)
        
        // Set current values
        etLabel.setText(currentConfig.label)
        if (currentConfig.mode == ButtonMode.TOGGLE) {
            rbToggle.isChecked = true
        } else {
            rbMomentary.isChecked = true
        }
        
        AlertDialog.Builder(this, R.style.DarkAlertDialog)
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
                setupButtonConfigGrid() // Refresh grid
                Toast.makeText(this, "Button ${buttonIndex + 1} updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupSpeedUnitSpinner() {
        val units = arrayOf("MPH", "KMH", "M/S")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units)
        binding.spinnerSpeedUnit.adapter = adapter
        binding.spinnerSpeedUnit.setSelection(settingsManager.speedUnit.ordinal)
        
        binding.spinnerSpeedUnit.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                settingsManager.speedUnit = SpeedUnit.entries[position]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupVariableSearch() {
        // GPS Speed quick-add button
        binding.btnAddGpsSpeed.setOnClickListener {
            val gpsGauge = VariableRepository.GPS_SPEED_GAUGE.copy(
                unit = settingsManager.speedUnit.name
            )
            if (settingsManager.gauges.any { it.isGpsSpeed }) {
                Toast.makeText(this, "GPS Speed already added", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            settingsManager.addGauge(gpsGauge)
            gaugeAdapter.updateGauges(settingsManager.gauges)
            Toast.makeText(this, "Added GPS Speed", Toast.LENGTH_SHORT).show()
        }
        
        // Add ECU Variable button - opens full-screen search
        binding.btnAddEcuVariable.setOnClickListener {
            val intent = Intent(this, AddGaugeActivity::class.java)
            addGaugeLauncher.launch(intent)
        }
    }

    private fun setupGaugesList() {
        gaugeAdapter = GaugeAdapter(settingsManager.gauges) { gauge ->
            settingsManager.removeGauge(gauge.variableHash)
            gaugeAdapter.updateGauges(settingsManager.gauges)
            Toast.makeText(this, "Removed ${gauge.label}", Toast.LENGTH_SHORT).show()
        }
        
        binding.rvGauges.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = gaugeAdapter
        }
    }

    private fun setupEcuId() {
        binding.etEcuId.setText(settingsManager.ecuId.toString())
        
        binding.etEcuId.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val id = binding.etEcuId.text.toString().toIntOrNull() ?: 1
                settingsManager.ecuId = id
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
    
    // Simple adapter for gauge list
    inner class GaugeAdapter(
        private var gauges: List<GaugeConfig>,
        private val onRemove: (GaugeConfig) -> Unit
    ) : RecyclerView.Adapter<GaugeAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(android.R.id.text1)
            val btnRemove: MaterialButton = view.findViewById(android.R.id.button1)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                android.R.layout.simple_list_item_1, parent, false
            )
            // Add remove button dynamically
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 8, 0, 8)
            }
            
            val textView = TextView(parent.context).apply {
                id = android.R.id.text1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(Color.WHITE)
                textSize = 14f
            }
            
            val button = MaterialButton(parent.context).apply {
                id = android.R.id.button1
                text = "✕"
                textSize = 12f
                minimumWidth = 0
                minimumHeight = 0
                setPadding(16, 8, 16, 8)
            }
            
            container.addView(textView)
            container.addView(button)
            
            return ViewHolder(container)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val gauge = gauges[position]
            val posLabel = if (gauge.position == GaugePosition.TOP) "TOP" else "SEC"
            val typeLabel = if (gauge.isGpsSpeed) "GPS" else gauge.variableName
            holder.tvName.text = "[$posLabel] ${gauge.label} ($typeLabel)"
            holder.btnRemove.setOnClickListener { onRemove(gauge) }
        }
        
        override fun getItemCount() = gauges.size
        
        fun updateGauges(newGauges: List<GaugeConfig>) {
            gauges = newGauges
            notifyDataSetChanged()
        }
    }
}
