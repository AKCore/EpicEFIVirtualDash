import Foundation

// MARK: - Speed Unit
enum SpeedUnit: String, Codable, CaseIterable {
    case mph = "MPH"
    case kph = "KPH"
    
    var multiplier: Double {
        switch self {
        case .mph: return 2.23694  // m/s to mph
        case .kph: return 3.6      // m/s to kph
        }
    }
}

// MARK: - Button Configuration
struct ButtonConfig: Codable, Identifiable {
    var id: Int
    var label: String
    var isToggle: Bool
    
    init(id: Int, label: String? = nil, isToggle: Bool = false) {
        self.id = id
        self.label = label ?? "BTN \(id + 1)"
        self.isToggle = isToggle
    }
}

// MARK: - Gauge Position
enum GaugePosition: String, Codable, CaseIterable {
    case top = "TOP"
    case secondary = "SECONDARY"
}

// MARK: - Gauge Configuration
struct GaugeConfig: Codable, Identifiable, Equatable {
    var id: UUID = UUID()
    var name: String
    var variableHash: Int
    var variableName: String
    var unit: String
    var minValue: Float
    var maxValue: Float
    var isGpsSpeed: Bool
    var position: GaugePosition
    
    init(name: String, variableHash: Int, variableName: String = "", unit: String = "", minValue: Float = 0, maxValue: Float = 100, isGpsSpeed: Bool = false, position: GaugePosition = .top) {
        self.name = name
        self.variableHash = variableHash
        self.variableName = variableName.isEmpty ? name : variableName
        self.unit = unit
        self.minValue = minValue
        self.maxValue = maxValue
        self.isGpsSpeed = isGpsSpeed
        self.position = position
    }
    
    static func == (lhs: GaugeConfig, rhs: GaugeConfig) -> Bool {
        lhs.variableHash == rhs.variableHash
    }
}

// MARK: - Variable Definition (from JSON)
struct VariableDefinition: Codable {
    let name: String
    let hash: Int
    let source: String
}

// MARK: - Variable Repository
class VariableRepository: ObservableObject {
    static let shared = VariableRepository()
    
    // GPS Speed pseudo-gauge (hash = 0)
    static let gpsSpeedGauge = GaugeConfig(
        name: "GPS Speed",
        variableHash: 0,
        variableName: "GPS",
        unit: "MPH",
        minValue: 0,
        maxValue: 200,
        isGpsSpeed: true,
        position: .top
    )
    
    @Published private(set) var variables: [VariableDefinition] = []
    @Published private(set) var outputVariables: [VariableDefinition] = []
    
    init() {
        loadVariables()
    }
    
    func loadVariables() {
        guard let url = Bundle.main.url(forResource: "variables", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode([VariableDefinition].self, from: data) else {
            print("[VariableRepository] Failed to load variables.json")
            return
        }
        variables = decoded
        outputVariables = decoded.filter { $0.source == "output" }
        print("[VariableRepository] Loaded \(variables.count) variables, \(outputVariables.count) outputs")
    }
    
    func getVariable(byHash hash: Int) -> VariableDefinition? {
        variables.first { $0.hash == hash }
    }
    
    func getVariable(byName name: String) -> VariableDefinition? {
        variables.first { $0.name.lowercased() == name.lowercased() }
    }
    
    func searchVariables(_ query: String) -> [VariableDefinition] {
        guard !query.isEmpty else { return outputVariables }
        let lowercased = query.lowercased()
        return outputVariables.filter { $0.name.lowercased().contains(lowercased) }
    }
}

// MARK: - Dashboard State
class DashboardState: ObservableObject {
    @Published var buttonMask: UInt16 = 0
    @Published var toggleStates: [Int: Bool] = [:]
    @Published var gaugeValues: [Int: Float] = [:]
    @Published var gpsSpeed: Double = 0
    
    func setButton(_ index: Int, pressed: Bool) {
        if pressed {
            buttonMask |= (1 << index)
        } else {
            buttonMask &= ~(1 << index)
        }
    }
    
    func toggleButton(_ index: Int) -> Bool {
        let newState = !(toggleStates[index] ?? false)
        toggleStates[index] = newState
        return newState
    }
    
    func updateGaugeValue(hash: Int, value: Float) {
        gaugeValues[hash] = value
    }
}
