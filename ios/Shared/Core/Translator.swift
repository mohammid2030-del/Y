import Foundation

/**
 Arabic for every description: TMDB's own Arabic text is used first (see TMDB.details / person); what TMDB only has
 in another language is translated here, once — every translation is kept in a cache file and reused.

 Translation services, in order:
  1. Google Cloud Translation (with the GOOGLE_TRANSLATE_KEY secret, or the project's other Google keys when that
     API is enabled for them),
  2. MyMemory (free, no key; a daily limit — what isn't translated today shows in its language and is tried again).
 */
actor Translator {
    static let shared = Translator()

    private var cache: [String: String] = Storage.loadFile("translations1", as: [String: String].self) ?? [:]
    private var googleKeys: [String] = [CloudConfig.translateKey, CloudConfig.youtubeKey, CloudConfig.apiKey].filter { !$0.isEmpty }
    private var myMemoryPausedUntil = Date.distantPast
    private var inflight: [String: Task<String?, Never>] = [:]

    static func isArabic(_ s: String) -> Bool {
        let letters = s.unicodeScalars.filter { CharacterSet.letters.contains($0) }
        guard !letters.isEmpty else { return true }
        let ar = letters.filter { (0x0600...0x06FF).contains($0.value) || (0x0750...0x077F).contains($0.value) }
        return Double(ar.count) / Double(letters.count) > 0.5
    }

    /// The text in Arabic (unchanged when it already is, or when no translation could be made now).
    func arabic(_ text: String) async -> String {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, !Translator.isArabic(t) else { return text }
        if let hit = cache[t] { return hit }
        if let running = inflight[t] { return await running.value ?? text }
        let task = Task<String?, Never> { await self.translate(t) }
        inflight[t] = task
        let out = await task.value
        inflight[t] = nil
        guard let out, !out.isEmpty else { return text }
        cache[t] = out
        if cache.count > 3000 { cache = Dictionary(uniqueKeysWithValues: cache.suffix(2000).map { ($0.key, $0.value) }) }
        Storage.saveFile("translations1", cache)
        return out
    }

    private func translate(_ t: String) async -> String? {
        if let g = await google(t) { return g }
        return await myMemory(t)
    }

    private func google(_ t: String) async -> String? {
        while let key = googleKeys.first {
            guard var c = URLComponents(string: "https://translation.googleapis.com/language/translate/v2") else { return nil }
            c.queryItems = [.init(name: "key", value: key)]
            guard let url = c.url else { return nil }
            var r = URLRequest(url: url, timeoutInterval: 15)
            r.httpMethod = "POST"
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try? JSONSerialization.data(withJSONObject: ["q": t, "target": "ar", "format": "text"])
            guard let (data, resp) = try? await URLSession.shared.data(for: r) else { return nil }   // offline: try later
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if code == 200,
               let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let list = (o["data"] as? [String: Any])?["translations"] as? [[String: Any]],
               let out = list.first?["translatedText"] as? String, !out.isEmpty {
                return out
            }
            // this key can't use the Translation API (not enabled / restricted / no billing): don't ask it again
            if code == 400 || code == 401 || code == 403 { googleKeys.removeFirst(); continue }
            return nil
        }
        return nil
    }

    /// MyMemory takes up to ~500 bytes a request: the text goes sentence by sentence in pieces.
    private func myMemory(_ t: String) async -> String? {
        guard Date() >= myMemoryPausedUntil else { return nil }
        let src = Translator.sourceLanguage(t)
        var out: [String] = []
        for piece in Translator.pieces(t, maxBytes: 450) {
            guard var c = URLComponents(string: "https://api.mymemory.translated.net/get") else { return nil }
            c.queryItems = [.init(name: "q", value: piece), .init(name: "langpair", value: "\(src)|ar")]
            guard let url = c.url, let (data, resp) = try? await URLSession.shared.data(from: url),
                  (resp as? HTTPURLResponse)?.statusCode == 200,
                  let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
            let status = jint(o["responseStatus"])
            let text = ((o["responseData"] as? [String: Any])?["translatedText"] as? String) ?? ""
            if status == 429 || text.uppercased().contains("MYMEMORY WARNING") {
                myMemoryPausedUntil = Date().addingTimeInterval(3 * 3600)   // daily limit reached
                return nil
            }
            guard status == 200, !text.isEmpty else { return nil }
            out.append(text)
        }
        return out.isEmpty ? nil : out.joined(separator: " ")
    }

    private static func sourceLanguage(_ t: String) -> String {
        if t.range(of: "[çğışöüÇĞİŞÖÜ]", options: .regularExpression) != nil { return "tr" }
        return "en"
    }

    /// Sentences grouped into pieces of at most `maxBytes` UTF-8 bytes (long sentences are cut at spaces).
    static func pieces(_ t: String, maxBytes: Int) -> [String] {
        var sentences: [String] = []
        t.enumerateSubstrings(in: t.startIndex..., options: .bySentences) { s, _, _, _ in if let s { sentences.append(s) } }
        if sentences.isEmpty { sentences = [t] }
        var out: [String] = [], cur = ""
        func flush() { let c = cur.trimmingCharacters(in: .whitespaces); if !c.isEmpty { out.append(c) }; cur = "" }
        for s in sentences {
            if (cur + s).utf8.count <= maxBytes { cur += s; continue }
            flush()
            if s.utf8.count <= maxBytes { cur = s; continue }
            for w in s.split(separator: " ") {
                if (cur + " " + w).utf8.count > maxBytes { flush() }
                cur += (cur.isEmpty ? "" : " ") + w
            }
        }
        flush()
        return out
    }
}
