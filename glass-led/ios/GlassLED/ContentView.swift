import SwiftUI

struct ContentView: View {
    @EnvironmentObject var b: Bridge

    var body: some View {
        NavigationView {
            Form {
                Section("Glass (glass-stream app)") {
                    TextField("http://<glass-ip>:8080/stream", text: $b.glassURL)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                }
                Section("Matrix Portal") {
                    TextField("IP address (shown on serial console)", text: $b.matrixHost)
                        .textInputAutocapitalization(.never).keyboardType(.decimalPad)
                    Stepper("UDP port \(b.matrixPort)", value: $b.matrixPort, in: 1024...65535)
                    Stepper("Width \(b.width)", value: $b.width, in: 16...128, step: 16)
                    Stepper("Height \(b.height)", value: $b.height, in: 16...64, step: 16)
                }
                Section("Image") {
                    HStack { Text("Brightness"); Slider(value: $b.brightness, in: 0.05...1) }
                    Toggle("Mirror", isOn: $b.mirror)
                    Stepper("Max \(Int(b.maxFPS)) fps", value: $b.maxFPS, in: 5...30, step: 5)
                }
                Section {
                    Button(b.running ? "Stop" : "Start") { b.running ? b.stop() : b.start() }
                    Text("\(b.status)  \(b.running ? String(format: "%.1f fps", b.fps) : "")")
                    if let p = b.preview {
                        Image(uiImage: p).interpolation(.none).resizable()
                            .aspectRatio(CGFloat(b.width) / CGFloat(b.height), contentMode: .fit)
                    }
                }
            }
            .navigationTitle("Glass LED")
        }
    }
}
