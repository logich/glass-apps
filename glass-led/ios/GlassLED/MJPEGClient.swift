import Foundation

/// Streams an HTTP MJPEG source and emits each complete JPEG (found via SOI/EOI markers).
final class MJPEGClient: NSObject, URLSessionDataDelegate {
    var onFrame: ((Data) -> Void)?
    var onStatus: ((String) -> Void)?
    private var session: URLSession?
    private var buffer = Data()

    func start(url: URL) {
        stop()
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 10
        cfg.timeoutIntervalForResource = .infinity
        session = URLSession(configuration: cfg, delegate: self, delegateQueue: nil)
        session?.dataTask(with: url).resume()
        onStatus?("Connecting…")
    }

    func stop() {
        session?.invalidateAndCancel(); session = nil; buffer.removeAll()
    }

    func urlSession(_ s: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        buffer.append(data)
        while let soi = buffer.range(of: Data([0xFF, 0xD8])),
              let eoi = buffer.range(of: Data([0xFF, 0xD9]), in: soi.upperBound..<buffer.endIndex) {
            onFrame?(buffer.subdata(in: soi.lowerBound..<eoi.upperBound))
            buffer.removeSubrange(buffer.startIndex..<eoi.upperBound)
        }
        if buffer.count > 2_000_000 { buffer.removeAll() } // resync if garbage
    }

    func urlSession(_ s: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        onStatus?(error.map { "Disconnected: \($0.localizedDescription)" } ?? "Stream ended")
    }
}
