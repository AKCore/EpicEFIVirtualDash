import Foundation
import Combine

class SettingsManager: ObservableObject {
    private let defaults = UserDefaults.standard
    
    private enum Keys {
        static let buttonCount = "button_count"
        static let buttonColumns = "button_columns"
        static let speedUnit = "speed_unit"
        static let gauges = "gauges"
        static let buttons = "buttons"
        static let ecuId = "ecu_id"
        static let showSpeedometer = "show_speedometer"
        static let dataRate = "data_rate"
        static let disclaimerAccepted = "disclaimer_accepted"
        static let lastVersionCode = "last_version_code"
    }
    
    // MARK: - Published Properties
    @Published var buttonCount: Int {
        didSet { defaults.set(buttonCount, forKey: Keys.buttonCount) }
    }
    
    @Published var buttonColumns: Int {
        didSet { defaults.set(buttonColumns, forKey: Keys.buttonColumns) }
    }
    
    @Published var speedUnit: SpeedUnit {
        didSet { defaults.set(speedUnit.rawValue, forKey: Keys.speedUnit) }
    }
    
    @Published var ecuId: Int {
        didSet { defaults.set(ecuId, forKey: Keys.ecuId) }
    }
    
    @Published var showSpeedometer: Bool {
        didSet { defaults.set(showSpeedometer, forKey: Keys.showSpeedometer) }
    }
    
    @Published var dataRateHz: Int {
        didSet { defaults.set(dataRateHz, forKey: Keys.dataRate) }
    }
    
    var dataDelayMs: Int {
        1000 / dataRateHz
    }
    
    var disclaimerAccepted: Bool {
        get { defaults.bool(forKey: Keys.disclaimerAccepted) }
        set { defaults.set(newValue, forKey: Keys.disclaimerAccepted) }
    }
    
    var lastVersionCode: Int {
        get { defaults.integer(forKey: Keys.lastVersionCode) }
        set { defaults.set(newValue, forKey: Keys.lastVersionCode) }
    }
    
    // MARK: - Buttons
    @Published var buttons: [ButtonConfig] {
        didSet { saveButtons() }
    }
    
    // MARK: - Gauges
    @Published var gauges: [GaugeConfig] {
        didSet { saveGauges() }
    }
    
    // MARK: - Init
    init() {
        self.buttonCount = defaults.object(forKey: Keys.buttonCount) as? Int ?? 16
        self.buttonColumns = defaults.object(forKey: Keys.buttonColumns) as? Int ?? 4
        self.speedUnit = SpeedUnit(rawValue: defaults.string(forKey: Keys.speedUnit) ?? "MPH") ?? .mph
        self.ecuId = defaults.object(forKey: Keys.ecuId) as? Int ?? 1
        self.showSpeedometer = defaults.object(forKey: Keys.showSpeedometer) as? Bool ?? true
        self.dataRateHz = defaults.object(forKey: Keys.dataRate) as? Int ?? 20
        
        // Load buttons
        if let data = defaults.data(forKey: Keys.buttons),
           let decoded = try? JSONDecoder().decode([ButtonConfig].self, from: data) {
            self.buttons = decoded
        } else {
            self.buttons = (0..<16).map { ButtonConfig(id: $0) }
        }
        
        // Load gauges - start empty, user adds what they want
        if let data = defaults.data(forKey: Keys.gauges),
           let decoded = try? JSONDecoder().decode([GaugeConfig].self, from: data) {
            self.gauges = decoded
        } else {
            // Default: GPS speed in top row
            self.gauges = [VariableRepository.gpsSpeedGauge]
        }
    }
    
    // MARK: - Button Methods
    func updateButton(_ buttonId: Int, config: ButtonConfig) {
        if let index = buttons.firstIndex(where: { $0.id == buttonId }) {
            buttons[index] = config
        }
    }
    
    func getButton(_ buttonId: Int) -> ButtonConfig {
        buttons.first { $0.id == buttonId } ?? ButtonConfig(id: buttonId)
    }
    
    private func saveButtons() {
        if let data = try? JSONEncoder().encode(buttons) {
            defaults.set(data, forKey: Keys.buttons)
        }
    }
    
    // MARK: - Gauge Methods
    func addGauge(_ gauge: GaugeConfig) {
        guard !gauges.contains(where: { $0.variableHash == gauge.variableHash }) else { return }
        gauges.append(gauge)
    }
    
    func removeGauge(variableHash: Int) {
        gauges.removeAll { $0.variableHash == variableHash }
    }
    
    private func saveGauges() {
        if let data = try? JSONEncoder().encode(gauges) {
            defaults.set(data, forKey: Keys.gauges)
        }
    }
}
