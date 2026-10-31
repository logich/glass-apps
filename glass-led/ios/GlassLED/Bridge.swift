import SwiftUI
import Network

/// Glass MJPEG -> FrameConverter -> UDP -> Matrix Portal.
final class Bridge: ObservableObject {
    @AppStorage("glassURL") var glassURL = "http://172.20.10.3:8080/stream" // iPhone hotspot default range
    @AppStorage("matrixHost") var matrixHost = ""
    @AppStorage("matrixPort") var matrixPort = 7777
    @AppStorage("width") var width = 64
    @AppStorage("height") var height = 32
    @AppStorage("brightness") var brightness = 0.4
    @AppStorage("mirror") var mirror = false
    @AppStorage("maxFPS") var maxFPS = 15.0

    @Published var status = "Idle"
    @Published var preview: UIImage?
    @Published var fps = 0.0
    @Published var running = false

    private let client = MJPEGClient()
    private var conn: NWConnection?
    private let queue = DispatchQueue(label: "bridge")
    private var lastSent = Date.distantPast
    private var count = 0, windowStart = Date()

    func start() {
        guard let url = URL(string: glassURL), !matrixHost.isEmpty,
              let port = NWEndpoint.Port(rawValue: UInt16(matrixPort)) else { status = "Check settings"; return }
        conn = NWConnection(host: NWEndpoint.Host(matrixHost), port: port, using: .udp)
        conn?.start(queue: queue)
        client.onStatus = { [weak self] s in DispatchQueue.main.async { self?.status = s } }
        client.onFrame = { [weak self] jpeg in self?.queue.async { self?.handle(jpeg) } }
        client.start(url: url)
        UIApplication.shared.isIdleTimerDisabled = true
        running = true
    }

    func stop() {
        client.stop(); conn?.cancel(); conn = nil
        UIApplication.shared.isIdleTimerDisabled = false
        running = false; status = "Stopped"
    }

    private func handle(_ jpeg: Data) {
        let now = Date()
        guard now.timeIntervalSince(lastSent) >= 1 / maxFPS else { return } // drop frames over the cap
        lastSent = now
        let conv = FrameConverter(width: width, height: height, brightness: Float(brightness), mirror: mirror)
        guard let (packets, img) = conv.rows(fromJPEG: jpeg) else { return }
        for p in packets { conn?.send(content: p, completion: .idempotent) }
        count += 1
        if now.timeIntervalSince(windowStart) >= 1 {
            let f = Double(count) / now.timeIntervalSince(windowStart)
            count = 0; windowStart = now
            DispatchQueue.main.async { self.fps = f; self.status = "Streaming" }
        }
        DispatchQueue.main.async { self.preview = img }
    }
}
