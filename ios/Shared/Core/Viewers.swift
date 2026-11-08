import Foundation
import CryptoKit

/// A person watching on a subscription (up to 5): own progress, favorites and recent channels, optional 4-digit PIN.
struct Viewer: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var color: Int
    var pinHash: String? = nil
    var updated: Double = Date().timeIntervalSince1970
    var deleted: Bool? = nil
    var hasPIN: Bool { !(pinHash ?? "").isEmpty }
}

enum Hash {
    static func sha256(_ s: String) -> String {
        SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

/// Firebase Realtime Database over REST with anonymous auth. Data lives under nova/v1/<subscription key>, where the
/// key is a hash of the subscription's own server + username + password: any device signed in to the same
/// subscription finds the same viewers and progress, without another account.
actor Cloud {
    static let shared = Cloud()
    nonisolated var enabled: Bool { !CloudConfig.dbURL.isEmpty && !CloudConfig.apiKey.isEmpty }

    private var idToken = ""
    private var expiry = Date.distantPast

    private func token() async -> String? {
        if !idToken.isEmpty && Date() < expiry { return idToken }
        let key = CloudConfig.apiKey
        if let refresh = Keychain.get("fbRefresh"),
           let j = await post("https://securetoken.googleapis.com/v1/token?key=\(key)",
                              body: "grant_type=refresh_token&refresh_token=\(refresh)", form: true),
           let t = j["id_token"] as? String {
            idToken = t
            expiry = Date().addingTimeInterval(Double(jint(j["expires_in"])) - 120)
            if let r = j["refresh_token"] as? String { Keychain.set(r, for: "fbRefresh") }
            return t
        }
        guard let j = await post("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=\(key)",
                                 body: #"{"returnSecureToken":true}"#, form: false),
              let t = j["idToken"] as? String else { return nil }
        idToken = t
        expiry = Date().addingTimeInterval(Double(jint(j["expiresIn"])) - 120)
        if let r = j["refreshToken"] as? String { Keychain.set(r, for: "fbRefresh") }
        return t
    }

    private func post(_ url: String, body: String, form: Bool) async -> [String: Any]? {
        guard let u = URL(string: url) else { return nil }
        var r = URLRequest(url: u)
        r.httpMethod = "POST"
        r.timeoutInterval = 15
        r.setValue(form ? "application/x-www-form-urlencoded" : "application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = body.data(using: .utf8)
        guard let (d, resp) = try? await URLSession.shared.data(for: r), (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
    }

    private func call(_ method: String, _ path: String, _ value: Any? = nil) async -> Any? {
        guard enabled, let t = await token() else { return nil }
        var base = CloudConfig.dbURL
        while base.hasSuffix("/") { base.removeLast() }
        guard let u = URL(string: "\(base)/\(path).json?auth=\(t)") else { return nil }
        var r = URLRequest(url: u)
        r.httpMethod = method
        r.timeoutInterval = 15
        if let value {
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed])
        }
        guard let (d, resp) = try? await URLSession.shared.data(for: r) else { return nil }
        if (resp as? HTTPURLResponse)?.statusCode == 401 { idToken = ""; return nil }
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed])
    }

    func get(_ path: String) async -> Any? { await call("GET", path) }
    func put(_ path: String, _ value: Any) async { _ = await call("PUT", path, value) }
    func patch(_ path: String, _ value: [String: Any]) async { _ = await call("PATCH", path, value) }

    /// Firebase keys can't contain . $ # [ ] /
    nonisolated static func safe(_ s: String) -> String {
        String(s.map { ".$#[]/".contains($0) ? "_" : $0 })
    }
}

/// Wire format shared by the Apple, Android and Samsung apps.
enum CloudCodec {
    static func viewer(_ v: Viewer) -> [String: Any] {
        var d: [String: Any] = ["n": v.name, "c": v.color, "u": v.updated]
        if let p = v.pinHash, !p.isEmpty { d["p"] = p }
        if v.deleted == true { d["x"] = true }
        return d
    }
    static func viewer(_ id: String, _ d: [String: Any]) -> Viewer {
        Viewer(id: id, name: jstr(d["n"]), color: jint(d["c"]), pinHash: (d["p"] as? String).flatMap { $0.isEmpty ? nil : $0 },
               updated: Double(jstr(d["u"])) ?? 0, deleted: (d["x"] as? Bool) == true ? true : nil)
    }

    static func historyKey(_ h: HistoryItem) -> String { Cloud.safe("\(h.kind.rawValue)_\(h.stream.id)_\(h.episodeID)") }

    static func history(_ h: HistoryItem) -> [String: Any] {
        ["k": h.kind.rawValue, "i": h.stream.id, "n": h.stream.name, "l": h.stream.logo, "c": h.stream.category,
         "x": h.stream.ext, "y": h.stream.year, "e": h.episodeID, "ex": h.episodeExt, "s": h.subtitle,
         "p": h.position, "d": h.duration, "u": h.updated.timeIntervalSince1970]
    }
    static func history(_ d: [String: Any], owner: String) -> HistoryItem? {
        guard let kind = MediaKind(rawValue: jstr(d["k"])), !jstr(d["i"]).isEmpty else { return nil }
        let item = MediaItem(id: jstr(d["i"]), name: jstr(d["n"]), logo: jstr(d["l"]), category: jstr(d["c"]), ext: jstr(d["x"]), year: jstr(d["y"]))
        return HistoryItem(subID: owner, kind: kind, stream: item, episodeID: jstr(d["e"]), episodeExt: jstr(d["ex"]), subtitle: jstr(d["s"]),
                           position: jint(d["p"]), duration: jint(d["d"]), updated: Date(timeIntervalSince1970: Double(jstr(d["u"])) ?? 0))
    }

    static func favKey(_ kind: MediaKind, _ id: String) -> String { Cloud.safe("\(kind.rawValue)_\(id)") }
    static func fav(_ f: FavItem, deleted: Bool = false) -> [String: Any] {
        var d: [String: Any] = ["k": f.kind.rawValue, "i": f.stream.id, "n": f.stream.name, "l": f.stream.logo, "c": f.stream.category,
                                "x": f.stream.ext, "y": f.stream.year, "u": Date().timeIntervalSince1970]
        if deleted { d["del"] = true }
        return d
    }
}
