import SwiftUI
import CoreImage.CIFilterBuiltins

/// Google sign-in for TVs: a short code on screen, entered on the phone at google.com/device.
struct GoogleSignInView: View {
    @EnvironmentObject var state: AppState
    @State private var code: Cloud.DeviceCode?
    @State private var status = ""
    @State private var failed = false
    @State private var attempt = 0

    var body: some View {
        ZStack {
            Color(red: 0.02, green: 0.02, blue: 0.035).ignoresSafeArea()
            GlowBackground()
            VStack(spacing: Theme.isTV ? 30 : 18) {
                HStack(spacing: 14) {
                    RoundedRectangle(cornerRadius: 14).fill(Theme.gradient).frame(width: 52, height: 52)
                        .overlay(Image(systemName: "play.fill").font(.title2).foregroundStyle(.white))
                    Text("NOVA").font(.system(size: Theme.isTV ? 48 : 34, weight: .black)).kerning(6)
                }
                Text(L.t("signInGoogle")).font(.system(size: Theme.isTV ? 54 : 30, weight: .heavy))
                Text(L.t("googleWhy")).foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: 900)
                if let c = code {
                    HStack(alignment: .center, spacing: Theme.isTV ? 70 : 24) {
                        if let qr = qrImage(c.url) {
                            Image(uiImage: qr).interpolation(.none).resizable().frame(width: Theme.isTV ? 280 : 150, height: Theme.isTV ? 280 : 150)
                                .padding(14).background(.white, in: RoundedRectangle(cornerRadius: 20))
                        }
                        VStack(alignment: .leading, spacing: 14) {
                            Text("1. " + L.t("googleStep1")).font(.title3)
                            Text(c.url.replacingOccurrences(of: "https://", with: "")).font(.system(size: Theme.isTV ? 40 : 24, weight: .bold)).foregroundStyle(Theme.blue)
                            Text("2. " + L.t("googleStep2")).font(.title3)
                            Text(c.user).font(.system(size: Theme.isTV ? 64 : 34, weight: .black, design: .monospaced)).kerning(6)
                                .padding(.horizontal, 24).padding(.vertical, 10).background(Theme.surface2, in: RoundedRectangle(cornerRadius: 16))
                        }
                    }
                    HStack(spacing: 10) { ProgressView(); Text(status.isEmpty ? L.t("googleWaiting") : status).foregroundStyle(.secondary) }
                } else if failed {
                    Text(status).foregroundStyle(.orange)
                } else {
                    ProgressView()
                }
                HStack(spacing: 20) {
                    if failed || code == nil {
                        Button(L.t("retry")) { attempt += 1 }.buttonStyle(.borderedProminent)
                    } else {
                        Button(L.t("newCode")) { attempt += 1 }.buttonStyle(.bordered)
                    }
                    Button(L.t(state.subs.isEmpty ? "addManually" : "later")) {
                        state.phase = state.subs.isEmpty ? .addSubscription(nil) : .profiles
                    }.buttonStyle(.bordered)
                }
            }
            .padding(Theme.isTV ? 80 : 24)
        }
        .task(id: attempt) { await run() }
    }

    private func run() async {
        failed = false; status = ""; code = nil
        guard let c = await Cloud.shared.startGoogle() else { failed = true; status = L.t("googleFailed"); return }
        code = c
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: UInt64(c.interval) * 1_000_000_000)
            if Task.isCancelled { return }
            switch await Cloud.shared.pollGoogle(c) {
            case .ok(let email):
                status = L.t("signedInAs", email)
                await state.didSignIn(email)
                return
            case .pending: continue
            case .denied: failed = true; code = nil; status = L.t("googleDenied"); return
            case .expired: attempt += 1; return
            case .failed: failed = true; code = nil; status = L.t("googleFailed"); return
            }
        }
    }

    private func qrImage(_ text: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
