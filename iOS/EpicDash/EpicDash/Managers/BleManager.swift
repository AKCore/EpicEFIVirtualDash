import Foundation
import CoreBluetooth
import Combine

class BleManager: NSObject, ObservableObject {
    // MARK: - UUIDs (must match ESP32 firmware)
    static let serviceUUID = CBUUID(string: "4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    static let buttonCharUUID = CBUUID(string: "beb5483e-36e1-4688-b7f5-ea07361b26a8")
    static let varDataCharUUID = CBUUID(string: "beb5483e-36e1-4688-b7f5-ea07361b26a9")
    static let varRequestCharUUID = CBUUID(string: "beb5483e-36e1-4688-b7f5-ea07361b26aa")
    static let gpsDataCharUUID = CBUUID(string: "beb5483e-36e1-4688-b7f5-ea07361b26ab")
    
    // GPS variable hashes for CAN transmission
    static let VAR_HASH_GPS_HMSD_PACKED: Int32 = 703958849
    static let VAR_HASH_GPS_MYQSAT_PACKED: Int32 = -1519914092
    static let VAR_HASH_GPS_ACCURACY: Int32 = -1489698215
    static let VAR_HASH_GPS_ALTITUDE: Int32 = -2100224086
    static let VAR_HASH_GPS_COURSE: Int32 = 1842893663
    static let VAR_HASH_GPS_LATITUDE: Int32 = 1524934922
    static let VAR_HASH_GPS_LONGITUDE: Int32 = -809214087
    static let VAR_HASH_GPS_SPEED: Int32 = -1486968225
    
    // Virtual ADC variable hashes (A0-A15)
    static let VAR_HASH_ADC: [Int32] = [
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
    ]
    
    private static let deviceName = "ESP32 Dashboard"
    
    // MARK: - Published State
    @Published var isConnected = false
    @Published var isScanning = false
    @Published var logMessages: [String] = []
    
    // MARK: - Callbacks
    var onVariableData: ((Int, Float) -> Void)?
    
    // MARK: - Private
    private var centralManager: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var buttonCharacteristic: CBCharacteristic?
    private var varDataCharacteristic: CBCharacteristic?
    private var varRequestCharacteristic: CBCharacteristic?
    private var gpsDataCharacteristic: CBCharacteristic?
    
    private var buttonWriteQueue: [Data] = []
    private var varRequestQueue: [Data] = []
    private var gpsDataQueue: [Data] = []
    private var isWritingButton = false
    private var isWritingVarRequest = false
    private var isWritingGpsData = false
    
    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }
    
    // MARK: - Public Methods
    func startScan() {
        guard centralManager.state == .poweredOn else {
            log("Bluetooth not ready")
            return
        }
        
        guard !isScanning else {
            log("Already scanning")
            return
        }
        
        isScanning = true
        log("Starting BLE scan...")
        
        centralManager.scanForPeripherals(
            withServices: [Self.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
        
        // Stop scan after 10 seconds
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
            self?.stopScan()
        }
    }
    
    func stopScan() {
        guard isScanning else { return }
        isScanning = false
        centralManager.stopScan()
        log("Scan stopped")
    }
    
    func disconnect() {
        if let peripheral = peripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        cleanup()
    }
    
    func sendButtonMask(_ mask: UInt16) {
        guard isConnected, buttonCharacteristic != nil else { return }
        
        var data = Data(count: 2)
        data[0] = UInt8(mask & 0xFF)
        data[1] = UInt8((mask >> 8) & 0xFF)
        
        buttonWriteQueue.append(data)
        processButtonQueue()
    }
    
    func requestVariable(_ varHash: Int) {
        guard isConnected, varRequestCharacteristic != nil else { return }
        
        var data = Data(count: 4)
        // Big-endian
        data[0] = UInt8((varHash >> 24) & 0xFF)
        data[1] = UInt8((varHash >> 16) & 0xFF)
        data[2] = UInt8((varHash >> 8) & 0xFF)
        data[3] = UInt8(varHash & 0xFF)
        
        varRequestQueue.append(data)
        processVarRequestQueue()
    }
    
    func requestVariablesBatch(_ varHashes: [Int]) {
        guard isConnected, varRequestCharacteristic != nil, !varHashes.isEmpty else { return }
        
        var data = Data(count: varHashes.count * 4)
        for (index, hash) in varHashes.enumerated() {
            let offset = index * 4
            data[offset] = UInt8((hash >> 24) & 0xFF)
            data[offset + 1] = UInt8((hash >> 16) & 0xFF)
            data[offset + 2] = UInt8((hash >> 8) & 0xFF)
            data[offset + 3] = UInt8(hash & 0xFF)
        }
        
        varRequestQueue.append(data)
        processVarRequestQueue()
    }
    
    // MARK: - GPS Data Methods
    func sendGpsData(varHash: Int32, value: Float) {
        guard isConnected, gpsDataCharacteristic != nil else { return }
        
        var data = Data(count: 8)
        // Hash (big-endian)
        data[0] = UInt8((varHash >> 24) & 0xFF)
        data[1] = UInt8((varHash >> 16) & 0xFF)
        data[2] = UInt8((varHash >> 8) & 0xFF)
        data[3] = UInt8(varHash & 0xFF)
        // Value (big-endian float)
        let valueBits = value.bitPattern.bigEndian
        withUnsafeBytes(of: valueBits) { bytes in
            data[4] = bytes[0]
            data[5] = bytes[1]
            data[6] = bytes[2]
            data[7] = bytes[3]
        }
        
        gpsDataQueue.append(data)
        processGpsDataQueue()
    }
    
    func sendGpsDataBatch(_ entries: [(hash: Int32, value: Float)]) {
        guard isConnected, gpsDataCharacteristic != nil, !entries.isEmpty else { return }
        
        var data = Data(count: entries.count * 8)
        for (index, entry) in entries.enumerated() {
            let offset = index * 8
            // Hash (big-endian)
            data[offset] = UInt8((entry.hash >> 24) & 0xFF)
            data[offset + 1] = UInt8((entry.hash >> 16) & 0xFF)
            data[offset + 2] = UInt8((entry.hash >> 8) & 0xFF)
            data[offset + 3] = UInt8(entry.hash & 0xFF)
            // Value (big-endian float)
            let valueBits = entry.value.bitPattern.bigEndian
            withUnsafeBytes(of: valueBits) { bytes in
                data[offset + 4] = bytes[0]
                data[offset + 5] = bytes[1]
                data[offset + 6] = bytes[2]
                data[offset + 7] = bytes[3]
            }
        }
        
        gpsDataQueue.append(data)
        processGpsDataQueue()
    }
    
    // MARK: - ADC Methods
    
    /// Send a single ADC value to ECU
    /// - Parameters:
    ///   - channel: ADC channel (0-15)
    ///   - value: ADC value (0-1023)
    func sendAdcValue(channel: Int, value: Float) {
        guard channel >= 0 && channel < Self.VAR_HASH_ADC.count else { return }
        sendGpsData(varHash: Self.VAR_HASH_ADC[channel], value: value)
    }
    
    /// Send all ADC values to ECU
    /// - Parameter values: Array of 16 ADC values (0-1023)
    func sendAllAdcValues(_ values: [Float]) {
        guard values.count == 16 else { return }
        
        var entries: [(hash: Int32, value: Float)] = []
        for (index, value) in values.enumerated() {
            entries.append((hash: Self.VAR_HASH_ADC[index], value: value))
        }
        sendGpsDataBatch(entries)
    }
    
    // MARK: - Private Methods
    private func cleanup() {
        peripheral = nil
        buttonCharacteristic = nil
        varDataCharacteristic = nil
        varRequestCharacteristic = nil
        gpsDataCharacteristic = nil
        buttonWriteQueue.removeAll()
        varRequestQueue.removeAll()
        gpsDataQueue.removeAll()
        isWritingButton = false
        isWritingVarRequest = false
        isWritingGpsData = false
        
        DispatchQueue.main.async {
            self.isConnected = false
        }
    }
    
    private func processButtonQueue() {
        guard !isWritingButton, !buttonWriteQueue.isEmpty,
              let char = buttonCharacteristic, let peripheral = peripheral else { return }
        
        let data = buttonWriteQueue.removeFirst()
        isWritingButton = true
        peripheral.writeValue(data, for: char, type: .withoutResponse)
        
        // For withoutResponse, manually trigger next
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.01) { [weak self] in
            self?.isWritingButton = false
            self?.processButtonQueue()
        }
    }
    
    private func processVarRequestQueue() {
        guard !isWritingVarRequest, !varRequestQueue.isEmpty,
              let char = varRequestCharacteristic, let peripheral = peripheral else { return }
        
        let data = varRequestQueue.removeFirst()
        isWritingVarRequest = true
        peripheral.writeValue(data, for: char, type: .withoutResponse)
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.01) { [weak self] in
            self?.isWritingVarRequest = false
            self?.processVarRequestQueue()
        }
    }
    
    private func processGpsDataQueue() {
        guard !isWritingGpsData, !gpsDataQueue.isEmpty,
              let char = gpsDataCharacteristic, let peripheral = peripheral else { return }
        
        let data = gpsDataQueue.removeFirst()
        isWritingGpsData = true
        peripheral.writeValue(data, for: char, type: .withoutResponse)
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.005) { [weak self] in
            self?.isWritingGpsData = false
            self?.processGpsDataQueue()
        }
    }
    
    private func log(_ message: String) {
        print("[BLE] \(message)")
        DispatchQueue.main.async {
            self.logMessages.append(message)
            if self.logMessages.count > 50 {
                self.logMessages.removeFirst()
            }
        }
    }
}

// MARK: - CBCentralManagerDelegate
extension BleManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            log("Bluetooth powered on")
        case .poweredOff:
            log("Bluetooth powered off")
            cleanup()
        case .unauthorized:
            log("Bluetooth unauthorized")
        case .unsupported:
            log("Bluetooth unsupported")
        default:
            log("Bluetooth state: \(central.state.rawValue)")
        }
    }
    
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let name = peripheral.name ?? "Unknown"
        
        if name == Self.deviceName || name.contains("ESP32") {
            log("Found device: \(name)")
            stopScan()
            
            self.peripheral = peripheral
            peripheral.delegate = self
            central.connect(peripheral, options: nil)
        }
    }
    
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        log("Connected to \(peripheral.name ?? "device")")
        peripheral.discoverServices([Self.serviceUUID])
    }
    
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        log("Disconnected")
        cleanup()
    }
    
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        log("Failed to connect: \(error?.localizedDescription ?? "unknown")")
        cleanup()
    }
}

// MARK: - CBPeripheralDelegate
extension BleManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        
        for service in services {
            if service.uuid == Self.serviceUUID {
                log("Found service")
                peripheral.discoverCharacteristics([
                    Self.buttonCharUUID,
                    Self.varDataCharUUID,
                    Self.varRequestCharUUID,
                    Self.gpsDataCharUUID
                ], for: service)
            }
        }
    }
    
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }
        
        for char in characteristics {
            switch char.uuid {
            case Self.buttonCharUUID:
                buttonCharacteristic = char
                log("Found button characteristic")
            case Self.varDataCharUUID:
                varDataCharacteristic = char
                peripheral.setNotifyValue(true, for: char)
                log("Found variable data characteristic")
            case Self.varRequestCharUUID:
                varRequestCharacteristic = char
                log("Found variable request characteristic")
            case Self.gpsDataCharUUID:
                gpsDataCharacteristic = char
                log("Found GPS data characteristic")
            default:
                break
            }
        }
        
        if buttonCharacteristic != nil {
            log("Ready")
            DispatchQueue.main.async {
                self.isConnected = true
            }
        }
    }
    
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == Self.varDataCharUUID,
              let data = characteristic.value else { return }
        
        // Parse batched response: multiple 8-byte entries [hash(4) + value(4)]
        var offset = 0
        while offset + 8 <= data.count {
            let hash = Int(Int32(bigEndian: data.subdata(in: offset..<offset+4).withUnsafeBytes { $0.load(as: Int32.self) }))
            let value = data.subdata(in: offset+4..<offset+8).withUnsafeBytes { $0.load(as: UInt32.self) }
            let floatValue = Float(bitPattern: UInt32(bigEndian: value))
            
            DispatchQueue.main.async {
                self.onVariableData?(hash, floatValue)
            }
            offset += 8
        }
    }
}
