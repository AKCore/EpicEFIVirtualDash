import SwiftUI

struct DashboardView: View {
    @EnvironmentObject var bleManager: BleManager
    @EnvironmentObject var settingsManager: SettingsManager
    @EnvironmentObject var locationManager: LocationManager
    
    @StateObject private var dashboardState = DashboardState()
    @State private var showSettings = false
    @State private var pollingTask: Task<Void, Never>?
    @State private var adcValues: [Float] = Array(repeating: 0, count: 16)
    @State private var adcValuesSentOnConnect = false
    
    private let accentColor = Color(hex: "FF6B00")
    private let bgDark = Color(hex: "121212")
    private let bgSurface = Color(hex: "1E1E1E")
    
    var body: some View {
        NavigationStack {
            ZStack {
                bgDark.ignoresSafeArea()
                
                VStack(spacing: 12) {
                    // Top Row Gauges (2 large)
                    topGaugesSection
                    
                    // Secondary Gauges (4 smaller)
                    secondaryGaugesSection
                    
                    // Button Grid
                    ButtonGridView(
                        dashboardState: dashboardState,
                        onButtonChanged: sendButtonMask
                    )
                    
                    // ADC Sliders
                    adcSlidersSection
                    
                    Spacer()
                    
                    // Log Panel
                    logPanel
                }
                .padding(12)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("EpicDash")
                        .font(.system(size: 22, weight: .bold, design: .default))
                        .foregroundColor(accentColor)
                }
                
                ToolbarItem(placement: .navigationBarTrailing) {
                    HStack(spacing: 8) {
                        // Connection status
                        Circle()
                            .fill(bleManager.isConnected ? Color.green : Color.red)
                            .frame(width: 10, height: 10)
                        
                        Text(bleManager.isConnected ? "Online" : "Offline")
                            .font(.caption)
                            .foregroundColor(.gray)
                        
                        // Connect button
                        Button {
                            if bleManager.isConnected {
                                bleManager.disconnect()
                            } else {
                                bleManager.startScan()
                            }
                        } label: {
                            Image(systemName: "antenna.radiowaves.left.and.right")
                                .foregroundColor(accentColor)
                        }
                        
                        // Settings button
                        Button {
                            showSettings = true
                        } label: {
                            Image(systemName: "gearshape")
                                .foregroundColor(.gray)
                        }
                    }
                }
            }
            .sheet(isPresented: $showSettings) {
                SettingsView()
            }
            .onAppear {
                setupBleCallbacks()
                locationManager.startUpdating()
                startPolling()
            }
            .onDisappear {
                pollingTask?.cancel()
            }
        }
    }
    
    // MARK: - Top Gauges (large, position = .top)
    private var topGaugesSection: some View {
        let topGauges = settingsManager.gauges.filter { $0.position == .top }
        
        return HStack(spacing: 8) {
            ForEach(topGauges.prefix(2)) { gauge in
                GaugeView(
                    config: gauge,
                    value: gaugeValue(for: gauge),
                    isLarge: true
                )
            }
            
            // Fill empty slots if less than 2 top gauges
            if topGauges.count < 2 {
                ForEach(0..<(2 - topGauges.count), id: \.self) { _ in
                    emptyGaugeSlot(isLarge: true)
                }
            }
        }
    }
    
    // MARK: - Secondary Gauges (small, position = .secondary)
    private var secondaryGaugesSection: some View {
        let secondaryGauges = settingsManager.gauges.filter { $0.position == .secondary }
        
        return LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
            ForEach(secondaryGauges.prefix(4)) { gauge in
                GaugeView(
                    config: gauge,
                    value: gaugeValue(for: gauge),
                    isLarge: false
                )
            }
        }
    }
    
    private func emptyGaugeSlot(isLarge: Bool) -> some View {
        VStack {
            Text("--")
                .font(.system(size: isLarge ? 48 : 24, weight: .bold, design: .rounded))
                .foregroundColor(.gray.opacity(0.3))
            Text("No Gauge")
                .font(.system(size: isLarge ? 12 : 8))
                .foregroundColor(.gray.opacity(0.3))
        }
        .frame(maxWidth: .infinity)
        .frame(height: isLarge ? 140 : 80)
        .padding(isLarge ? 16 : 8)
        .background(bgSurface)
        .cornerRadius(16)
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(Color.gray.opacity(0.2), lineWidth: 1)
        )
    }
    
    // MARK: - ADC Sliders
    private var adcSlidersSection: some View {
        ScrollView {
            AdcSlidersView(adcValues: $adcValues)
        }
        .frame(maxHeight: 200)
        .padding(8)
        .background(bgSurface)
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color.gray.opacity(0.3), lineWidth: 1)
        )
    }
    
    // MARK: - Log Panel
    private var logPanel: some View {
        ScrollView {
            ScrollViewReader { proxy in
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(bleManager.logMessages.enumerated()), id: \.offset) { index, message in
                        Text(message)
                            .font(.system(size: 9, design: .monospaced))
                            .foregroundColor(.gray)
                            .id(index)
                    }
                }
                .onChange(of: bleManager.logMessages.count) { _ in
                    if let lastIndex = bleManager.logMessages.indices.last {
                        proxy.scrollTo(lastIndex, anchor: .bottom)
                    }
                }
            }
        }
        .frame(height: 60)
        .padding(10)
        .background(bgSurface)
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color.gray.opacity(0.3), lineWidth: 1)
        )
    }
    
    // MARK: - Helpers
    private func gaugeValue(for gauge: GaugeConfig) -> Float {
        if gauge.isGpsSpeed {
            return Float(locationManager.getSpeed(in: settingsManager.speedUnit))
        }
        return dashboardState.gaugeValues[gauge.variableHash] ?? 0
    }
    
    private func setupBleCallbacks() {
        bleManager.onVariableData = { hash, value in
            dashboardState.updateGaugeValue(hash: hash, value: value)
        }
        
        // Setup GPS -> BLE -> CAN transmission
        locationManager.onGpsUpdate = { [weak bleManager, weak locationManager] location in
            guard let bleManager = bleManager, let locationManager = locationManager else { return }
            guard bleManager.isConnected else { return }
            
            // Build GPS entries (only changed values)
            let entries = locationManager.buildGpsEntries(from: location)
            
            // Send to BLE if there are changes
            if !entries.isEmpty {
                bleManager.sendGpsDataBatch(entries)
            }
        }
    }
    
    private func sendButtonMask() {
        bleManager.sendButtonMask(dashboardState.buttonMask)
    }
    
    private func startPolling() {
        pollingTask?.cancel()
        pollingTask = Task {
            while !Task.isCancelled {
                if bleManager.isConnected {
                    // Send all ADC values on first connect
                    if !adcValuesSentOnConnect {
                        adcValuesSentOnConnect = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                            bleManager.sendAllAdcValues(adcValues)
                        }
                    }
                    
                    // Get non-GPS gauge hashes
                    let hashes = settingsManager.gauges
                        .filter { !$0.isGpsSpeed }
                        .map { $0.variableHash }
                    
                    if !hashes.isEmpty {
                        bleManager.requestVariablesBatch(hashes)
                    }
                } else {
                    // Reset flag on disconnect
                    adcValuesSentOnConnect = false
                }
                
                try? await Task.sleep(nanoseconds: UInt64(settingsManager.dataDelayMs) * 1_000_000)
            }
        }
    }
}

#Preview {
    DashboardView()
        .environmentObject(BleManager())
        .environmentObject(SettingsManager())
        .environmentObject(LocationManager())
}
