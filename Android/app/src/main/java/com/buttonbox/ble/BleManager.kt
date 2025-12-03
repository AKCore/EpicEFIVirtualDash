package com.buttonbox.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * BLE Manager for connecting to ESP32 Dashboard
 * Handles scanning, connecting, sending buttons, and receiving variable data
 */
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        
        // Must match ESP32 firmware UUIDs
        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val CHAR_BUTTON_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        val CHAR_VAR_DATA_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a9")
        val CHAR_VAR_REQUEST_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26aa")
        val CHAR_GPS_DATA_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26ab")
        
        // GPS variable hashes for CAN transmission
        const val VAR_HASH_GPS_HMSD_PACKED = 703958849
        const val VAR_HASH_GPS_MYQSAT_PACKED = -1519914092
        const val VAR_HASH_GPS_ACCURACY = -1489698215
        const val VAR_HASH_GPS_ALTITUDE = -2100224086
        const val VAR_HASH_GPS_COURSE = 1842893663
        const val VAR_HASH_GPS_LATITUDE = 1524934922
        const val VAR_HASH_GPS_LONGITUDE = -809214087
        const val VAR_HASH_GPS_SPEED = -1486968225
        
        // Virtual ADC variable hashes (A0-A15)
        val VAR_HASH_ADC = intArrayOf(
            595545759,   // A0
            595545760,   // A1
            595545761,   // A2
            595545762,   // A3
            595545763,   // A4
            595545764,   // A5
            595545765,   // A6
            595545766,   // A7
            595545767,   // A8
            595545768,   // A9
            -1821826352, // A10
            -1821826351, // A11
            -1821826350, // A12
            -1821826349, // A13
            -1821826348, // A14
            -1821826347  // A15
        )
        
        private const val DEVICE_NAME = "ESP32 Dashboard"
        private const val SCAN_TIMEOUT_MS = 10000L
    }

    interface BleCallback {
        fun onConnectionStateChanged(connected: Boolean)
        fun onLog(message: String)
        fun onScanResult(device: BluetoothDevice)
        fun onVariableData(varHash: Int, value: Float)
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var buttonCharacteristic: BluetoothGattCharacteristic? = null
    private var varDataCharacteristic: BluetoothGattCharacteristic? = null
    private var varRequestCharacteristic: BluetoothGattCharacteristic? = null
    private var gpsDataCharacteristic: BluetoothGattCharacteristic? = null
    private var callback: BleCallback? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    
    // Write queues for rapid operations
    private val buttonWriteQueue = ArrayDeque<ByteArray>()
    private val varRequestQueue = ArrayDeque<ByteArray>()
    private val gpsDataQueue = ArrayDeque<ByteArray>()
    private var isWritingButton = false
    private var isWritingVarRequest = false
    private var isWritingGpsData = false

    val isConnected: Boolean
        get() = bluetoothGatt != null && buttonCharacteristic != null

    fun setCallback(callback: BleCallback) {
        this.callback = callback
    }

    fun initialize(): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        return bluetoothAdapter != null
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (isScanning) {
            log("Already scanning")
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            log("BLE scanner not available")
            return
        }

        isScanning = true
        log("Starting BLE scan...")

        handler.postDelayed({
            stopScan()
        }, SCAN_TIMEOUT_MS)

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, scanSettings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        log("Scan stopped")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            
            if (name == DEVICE_NAME || name.contains("ESP32")) {
                log("Found device: $name (${device.address})")
                stopScan()
                callback?.onScanResult(device)
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            log("Scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        log("Connecting to ${device.name}...")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.let { gatt ->
            log("Disconnecting...")
            gatt.disconnect()
            gatt.close()
        }
        bluetoothGatt = null
        buttonCharacteristic = null
        varDataCharacteristic = null
        varRequestCharacteristic = null
        gpsDataCharacteristic = null
        buttonWriteQueue.clear()
        varRequestQueue.clear()
        gpsDataQueue.clear()
        isWritingButton = false
        isWritingVarRequest = false
        isWritingGpsData = false
        callback?.onConnectionStateChanged(false)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            log("Connection state: newState=$newState status=$status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("Connected to GATT server")
                    // Small delay before service discovery for stability
                    handler.postDelayed({ gatt.discoverServices() }, 100)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Disconnected from GATT server (status=$status)")
                    buttonCharacteristic = null
                    varDataCharacteristic = null
                    varRequestCharacteristic = null
                    gpsDataCharacteristic = null
                    handler.post { callback?.onConnectionStateChanged(false) }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: $status")
                return
            }
            
            log("Services discovered")
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                log("Service not found")
                return
            }
            
            buttonCharacteristic = service.getCharacteristic(CHAR_BUTTON_UUID)
            varDataCharacteristic = service.getCharacteristic(CHAR_VAR_DATA_UUID)
            varRequestCharacteristic = service.getCharacteristic(CHAR_VAR_REQUEST_UUID)
            gpsDataCharacteristic = service.getCharacteristic(CHAR_GPS_DATA_UUID)
            
            // Log which characteristics were found
            log("Button char: ${if (buttonCharacteristic != null) "OK" else "MISSING"}")
            log("VarData char: ${if (varDataCharacteristic != null) "OK" else "MISSING"}")
            log("VarRequest char: ${if (varRequestCharacteristic != null) "OK" else "MISSING"}")
            log("GPS char: ${if (gpsDataCharacteristic != null) "OK" else "MISSING"}")
            
            // Enable notifications for variable data
            varDataCharacteristic?.let { char ->
                gatt.setCharacteristicNotification(char, true)
                val descriptor = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                descriptor?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }
                log("Enabled variable data notifications")
            }
            
            if (buttonCharacteristic != null) {
                log("Found all characteristics")
                // Request high priority connection for faster throughput (7.5ms interval)
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                // Request larger MTU for efficiency (default is 23, request 517)
                gatt.requestMtu(517)
            }
        }
        
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU changed to $mtu (status: $status)")
            handler.post { callback?.onConnectionStateChanged(true) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHAR_VAR_DATA_UUID) {
                val data = characteristic.value
                // Parse batched response: multiple 8-byte entries [hash(4) + value(4)]
                var offset = 0
                while (offset + 8 <= data.size) {
                    val hash = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
                    val value = ByteBuffer.wrap(data, offset + 4, 4).order(ByteOrder.BIG_ENDIAN).float
                    handler.post { callback?.onVariableData(hash, value) }
                    offset += 8
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            when (characteristic.uuid) {
                CHAR_BUTTON_UUID -> {
                    isWritingButton = false
                    processButtonQueue()
                }
                CHAR_VAR_REQUEST_UUID -> {
                    isWritingVarRequest = false
                    processVarRequestQueue()
                }
                CHAR_GPS_DATA_UUID -> {
                    isWritingGpsData = false
                    processGpsDataQueue()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun processButtonQueue() {
        if (isWritingButton || buttonWriteQueue.isEmpty()) return
        
        val data = buttonWriteQueue.poll() ?: return
        val char = buttonCharacteristic ?: return
        val gatt = bluetoothGatt ?: return
        
        char.value = data
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        isWritingButton = gatt.writeCharacteristic(char)
    }

    @SuppressLint("MissingPermission")
    private fun processVarRequestQueue() {
        if (isWritingVarRequest || varRequestQueue.isEmpty()) return
        
        val data = varRequestQueue.poll() ?: return
        val char = varRequestCharacteristic ?: return
        val gatt = bluetoothGatt ?: return
        
        char.value = data
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        isWritingVarRequest = gatt.writeCharacteristic(char)
    }

    /**
     * Send button mask with queuing for rapid presses
     */
    fun sendButtonMask(buttonMask: Int): Boolean {
        if (!isConnected) return false
        
        val lowByte = (buttonMask and 0xFF).toByte()
        val highByte = ((buttonMask shr 8) and 0xFF).toByte()
        buttonWriteQueue.offer(byteArrayOf(lowByte, highByte))
        
        if (!isWritingButton) {
            processButtonQueue()
        }
        return true
    }

    /**
     * Request a variable value from ECU - queued for rapid requests
     */
    fun requestVariable(varHash: Int): Boolean {
        if (!isConnected) return false
        
        // Send hash as big-endian
        val data = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(varHash).array()
        varRequestQueue.offer(data)
        
        if (!isWritingVarRequest) {
            processVarRequestQueue()
        }
        return true
    }
    
    /**
     * Request multiple variables in one BLE write (batched for efficiency)
     * ESP32 will request each from CAN and batch the responses
     */
    fun requestVariablesBatch(varHashes: List<Int>): Boolean {
        if (!isConnected || varHashes.isEmpty()) return false
        
        // Pack all hashes into one buffer (4 bytes each, big-endian)
        val data = ByteBuffer.allocate(varHashes.size * 4).order(ByteOrder.BIG_ENDIAN)
        for (hash in varHashes) {
            data.putInt(hash)
        }
        varRequestQueue.offer(data.array())
        
        if (!isWritingVarRequest) {
            processVarRequestQueue()
        }
        return true
    }
    
    @SuppressLint("MissingPermission")
    private fun processGpsDataQueue() {
        if (isWritingGpsData || gpsDataQueue.isEmpty()) return
        
        val data = gpsDataQueue.poll() ?: return
        val char = gpsDataCharacteristic ?: return
        val gatt = bluetoothGatt ?: return
        
        char.value = data
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        isWritingGpsData = gatt.writeCharacteristic(char)
    }
    
    /**
     * Send GPS data to ESP32 for CAN transmission to ECU
     * Format: [0..3] VarHash (int32 BE), [4..7] Value (float32 BE)
     */
    fun sendGpsData(varHash: Int, value: Float): Boolean {
        if (!isConnected || gpsDataCharacteristic == null) return false
        
        val data = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(varHash)
            .putFloat(value)
            .array()
        gpsDataQueue.offer(data)
        
        if (!isWritingGpsData) {
            processGpsDataQueue()
        }
        return true
    }
    
    /**
     * Send packed GPS data (uint32) to ESP32 for CAN transmission
     * Used for HMSD and MYQSAT packed values
     * The packed value is sent as raw bytes (not as float)
     */
    fun sendGpsDataPacked(varHash: Int, packedValue: Int): Boolean {
        if (!isConnected || gpsDataCharacteristic == null) return false
        
        val data = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(varHash)
            .putInt(packedValue)
            .array()
        
        gpsDataQueue.offer(data)
        
        if (!isWritingGpsData) {
            processGpsDataQueue()
        }
        return true
    }
    
    /**
     * Send multiple GPS data entries in one BLE write (batched for efficiency)
     */
    fun sendGpsDataBatch(entries: List<Pair<Int, Float>>): Boolean {
        if (!isConnected) {
            log("GPS batch: not connected")
            return false
        }
        if (gpsDataCharacteristic == null) {
            log("GPS batch: no characteristic!")
            return false
        }
        if (entries.isEmpty()) return false
        
        val data = ByteBuffer.allocate(entries.size * 8).order(ByteOrder.BIG_ENDIAN)
        for ((hash, value) in entries) {
            data.putInt(hash)
            data.putFloat(value)
        }
        
        gpsDataQueue.offer(data.array())
        
        if (!isWritingGpsData) {
            processGpsDataQueue()
        }
        return true
    }
    
    /**
     * Send a single ADC value to ECU
     * @param channel ADC channel (0-15)
     * @param value ADC value (0-1023)
     */
    fun sendAdcValue(channel: Int, value: Float): Boolean {
        if (channel < 0 || channel >= VAR_HASH_ADC.size) return false
        return sendGpsData(VAR_HASH_ADC[channel], value)
    }
    
    /**
     * Send all ADC values to ECU (used on connect or bulk update)
     * @param values Array of 16 ADC values (0-1023)
     */
    fun sendAllAdcValues(values: FloatArray): Boolean {
        if (!isConnected || gpsDataCharacteristic == null) return false
        if (values.size != 16) return false
        
        val entries = values.mapIndexed { index, value -> 
            VAR_HASH_ADC[index] to value 
        }
        return sendGpsDataBatch(entries)
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        handler.post { callback?.onLog(message) }
    }
}
