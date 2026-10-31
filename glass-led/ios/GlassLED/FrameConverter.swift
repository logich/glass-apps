import UIKit

/// JPEG -> centre-cropped WxH -> gamma + brightness -> RGB565 rows.
/// Wire format per row: [row index][W x RGB565 little-endian]. Must match firmware/code.py.
struct FrameConverter {
    var width = 64, height = 32
    var brightness: Float = 0.5
    var mirror = false

    private static let gamma: [Float] = (0..<256).map { pow(Float($0) / 255, 2.2) * 255 }

    func rows(fromJPEG jpeg: Data) -> (packets: [Data], preview: UIImage)? {
        guard let cg = UIImage(data: jpeg)?.cgImage else { return nil }
        var px = [UInt8](repeating: 0, count: width * height * 4)
        let ok = px.withUnsafeMutableBytes { raw -> Bool in
            guard let ctx = CGContext(data: raw.baseAddress, width: width, height: height,
                                      bitsPerComponent: 8, bytesPerRow: width * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            ctx.interpolationQuality = .medium
            // aspect-fill: scale so the image covers the matrix, centred
            let s = max(CGFloat(width) / CGFloat(cg.width), CGFloat(height) / CGFloat(cg.height))
            let w = CGFloat(cg.width) * s, h = CGFloat(cg.height) * s
            if mirror { ctx.translateBy(x: CGFloat(width), y: 0); ctx.scaleBy(x: -1, y: 1) }
            ctx.draw(cg, in: CGRect(x: (CGFloat(width) - w) / 2, y: (CGFloat(height) - h) / 2, width: w, height: h))
            return true
        }
        guard ok else { return nil }

        var packets: [Data] = []
        for y in 0..<height {
            var d = Data([UInt8(y)]); d.reserveCapacity(1 + width * 2)
            for x in 0..<width {
                let i = (y * width + x) * 4
                let r = UInt16(Self.gamma[Int(px[i])] * brightness)
                let g = UInt16(Self.gamma[Int(px[i + 1])] * brightness)
                let b = UInt16(Self.gamma[Int(px[i + 2])] * brightness)
                let v = (r >> 3) << 11 | (g >> 2) << 5 | (b >> 3)
                d.append(UInt8(v & 0xFF)); d.append(UInt8(v >> 8))
            }
            packets.append(d)
        }
        let preview = px.withUnsafeBytes { raw -> UIImage? in
            guard let ctx = CGContext(data: UnsafeMutableRawPointer(mutating: raw.baseAddress), width: width, height: height,
                                      bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue),
                  let out = ctx.makeImage() else { return nil }
            return UIImage(cgImage: out)
        } ?? UIImage()
        return (packets, preview)
    }
}
