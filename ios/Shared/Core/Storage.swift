import Foundation
import Security

/// Small state lives in UserDefaults (tvOS has no persistent file storage);
/// the catalog lives in the caches folder and is re-downloaded if the system purges it.
enum Storage {
    private static let defaults = UserDefaults.standard
    private static let encoder = JSONEncoder()
    private static let decoder = JSONDecoder()

    static func load<T: Decodable>(_ key: String, as type: T.Type) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? decoder.decode(T.self, from: data)
    }

    static func save<T: Encodable>(_ key: String, _ value: T) {
        if let data = try? encoder.encode(value) { defaults.set(data, forKey: key) }
    }

    // MARK: large caches — files, never UserDefaults (Apple TV aborts the app when its defaults pass 4 MB)

    static func loadFile<T: Decodable>(_ key: String, as type: T.Type) -> T? {
        if let data = try? Data(contentsOf: cacheDir.appendingPathComponent(key + ".json")) { return try? decoder.decode(T.self, from: data) }
        return nil
    }

    private static let fileQueue = DispatchQueue(label: "nova.storage.files", qos: .utility)
    static func saveFile<T: Encodable>(_ key: String, _ value: T) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        let url = cacheDir.appendingPathComponent(key + ".json")
        fileQueue.async { try? data.write(to: url, options: .atomic) }
    }

    /// Moves caches that older builds kept in UserDefaults into files (run first thing at launch).
    static func moveLargeOutOfDefaults() {
        for key in ["tmdbCards3", "tmdbCards2", "tmdbCards"] {
            guard let data = defaults.data(forKey: key) else { continue }
            if key == "tmdbCards3" { try? data.write(to: cacheDir.appendingPathComponent(key + ".json"), options: .atomic) }
            defaults.removeObject(forKey: key)
        }
        // any other oversized value (> 512 KB) would put the app at risk again
        for (k, v) in defaults.dictionaryRepresentation() where (v as? Data)?.count ?? 0 > 512_000 { defaults.removeObject(forKey: k) }
        // trailer answers used to be kept here one key each: they live in a cache file now
        for k in defaults.dictionaryRepresentation().keys where k.hasPrefix("yt|") || k.hasPrefix("yt2|") || k.hasPrefix("yt3|") || k.hasPrefix("ytok|") { defaults.removeObject(forKey: k) }
    }

    static func string(_ key: String) -> String? { defaults.string(forKey: key) }
    static func setString(_ key: String, _ value: String?) { defaults.set(value, forKey: key) }

    private static var cacheDir: URL {
        #if os(tvOS)
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        #else
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        #endif
        let dir = base.appendingPathComponent("NOVA", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func saveCatalog(_ catalog: Catalog, for subID: String) {
        let url = cacheDir.appendingPathComponent("catalog-\(subID).json")
        DispatchQueue.global(qos: .utility).async {
            if let data = try? JSONEncoder().encode(catalog) { try? data.write(to: url, options: .atomic) }
        }
    }

    static func loadCatalog(for subID: String) -> Catalog? {
        let url = cacheDir.appendingPathComponent("catalog-\(subID).json")
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(Catalog.self, from: data)
    }

    static func deleteCatalog(for subID: String) {
        try? FileManager.default.removeItem(at: cacheDir.appendingPathComponent("catalog-\(subID).json"))
    }
}

/// Secrets (passwords, M3U links, API keys) are kept in the Keychain.
enum Keychain {
    static func set(_ value: String?, for key: String) {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key,
                                   kSecAttrService as String: "nova.iptv"]
        SecItemDelete(base as CFDictionary)
        guard let value, !value.isEmpty, let data = value.data(using: .utf8) else { return }
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
    }

    static func get(_ key: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key,
                                kSecAttrService as String: "nova.iptv", kSecReturnData as String: true,
                                kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
