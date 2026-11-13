// 이미지 안의 QR 코드를 읽어 내용을 출력한다. 사용: swift qr.swift <png>
import Foundation
import Vision
import AppKit

let path = CommandLine.arguments[1]
guard let image = NSImage(contentsOfFile: path), let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
    print("cannot open"); exit(1)
}
let request = VNDetectBarcodesRequest()
request.symbologies = [.qr]
try VNImageRequestHandler(cgImage: cg, options: [:]).perform([request])
for result in request.results ?? [] {
    print(result.payloadStringValue ?? "")
}
