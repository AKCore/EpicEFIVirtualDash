import SwiftUI

struct AdcSlidersView: View {
    @EnvironmentObject var bleManager: BleManager
    @Binding var adcValues: [Float]
    
    private let accentColor = Color(hex: "FF6B00")
    private let bgSurface = Color(hex: "1E1E1E")
    
    var body: some View {
        VStack(spacing: 4) {
            ForEach(0..<16, id: \.self) { channel in
                AdcSliderRow(
                    channel: channel,
                    value: $adcValues[channel],
                    onValueChanged: { newValue in
                        if bleManager.isConnected {
                            bleManager.sendAdcValue(channel: channel, value: newValue)
                        }
                    }
                )
            }
        }
    }
}

struct AdcSliderRow: View {
    let channel: Int
    @Binding var value: Float
    var onValueChanged: (Float) -> Void
    
    @State private var isDragging = false
    
    private let accentColor = Color(hex: "FF6B00")
    
    var body: some View {
        HStack(spacing: 8) {
            Text("A\(channel)")
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(.gray)
                .frame(width: 28, alignment: .leading)
            
            Slider(
                value: $value,
                in: 0...1023,
                step: 1,
                onEditingChanged: { editing in
                    isDragging = editing
                    if !editing {
                        // Send value when user stops dragging
                        onValueChanged(value)
                    }
                }
            )
            .accentColor(accentColor)
            
            Text("\(Int(value))")
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundColor(accentColor)
                .frame(width: 40, alignment: .trailing)
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 2)
    }
}

#Preview {
    AdcSlidersView(adcValues: .constant(Array(repeating: 0, count: 16)))
        .environmentObject(BleManager())
        .background(Color(hex: "121212"))
}
