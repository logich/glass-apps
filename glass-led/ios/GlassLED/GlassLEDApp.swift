import SwiftUI

@main
struct GlassLEDApp: App {
    @StateObject private var bridge = Bridge()
    var body: some Scene {
        WindowGroup { ContentView().environmentObject(bridge) }
    }
}
