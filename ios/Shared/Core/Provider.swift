import Foundation

enum ProviderError: LocalizedError {
    case invalidURL, auth, expired, http(Int), parse, network

    var errorDescription: String? {
        let ar = L.isArabic
        switch self {
        case .invalidURL: return ar ? "تحقق من العنوان والحقول المطلوبة." : "Please check the address and required fields."
        case .auth: return ar ? "اسم المستخدم أو كلمة المرور غير صحيحة." : "Invalid username or password."
        case .expired: return ar ? "هذا الاشتراك منتهي أو معطّل." : "This subscription is expired or disabled."
        case .http(let c): return ar ? "الخادم أعاد خطأ (HTTP \(c))." : "The server responded with HTTP \(c)."
        case .parse: return ar ? "البيانات المستلمة غير مقروءة." : "The provider returned unreadable data."
        case .network: return ar ? "لا يوجد اتصال بالخادم." : "No connection to the server."
        }
    }
}

enum Net {
    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 60
        c.timeoutIntervalForResource = 240
        c.httpAdditionalHeaders = ["User-Agent": "NOVA-IPTV/1.0 (Apple)"]
        return URLSession(configuration: c)
    }()

    static func data(_ url: URL, headers: [String: String] = [:]) async throws -> Data {
        var req = URLRequest(url: url)
        headers.forEach { req.setValue($1, forHTTPHeaderField: $0) }
        let (data, resp): (Data, URLResponse)
        do { (data, resp) = try await session.data(for: req) } catch { throw ProviderError.network }
        if let h = resp as? HTTPURLResponse, !(200..<300).contains(h.statusCode) {
            throw h.statusCode == 401 || h.statusCode == 403 ? ProviderError.auth : ProviderError.http(h.statusCode)
        }
        return data
    }

    static func json(_ url: URL, headers: [String: String] = [:]) async throws -> Any {
        let d = try await data(url, headers: headers)
        guard let j = try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed]) else { throw ProviderError.parse }
        return j
    }
}

/// Loosely typed JSON helpers (providers mix numbers and strings).
func jstr(_ v: Any?) -> String {
    switch v {
    case let s as String: return s
    case let n as NSNumber: return n.stringValue
    default: return ""
    }
}
func jint(_ v: Any?) -> Int { Int(jstr(v)) ?? Int(Double(jstr(v)) ?? 0) }
func jlist(_ v: Any?) -> [[String: Any]] {
    if let a = v as? [[String: Any]] { return a }
    if let d = v as? [String: Any] { return d.values.compactMap { $0 as? [String: Any] } }
    return []
}

struct Xtream {
    let server: String
    let user: String
    let pass: String

    static func normalize(_ input: String) -> String {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if !s.lowercased().hasPrefix("http") { s = "http://" + s }
        for marker in ["/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php", "/c/"] {
            if let r = s.range(of: marker) { s = String(s[..<r.lowerBound]) }
        }
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    private func api(_ action: String? = nil, _ extra: [String: String] = [:]) throws -> URL {
        guard var c = URLComponents(string: server + "/player_api.php") else { throw ProviderError.invalidURL }
        var q = [URLQueryItem(name: "username", value: user), URLQueryItem(name: "password", value: pass)]
        if let action { q.append(URLQueryItem(name: "action", value: action)) }
        extra.forEach { q.append(URLQueryItem(name: $0, value: $1)) }
        c.queryItems = q
        guard let u = c.url else { throw ProviderError.invalidURL }
        return u
    }

    /// Verifies the account; returns the expiry date if the provider sends one.
    func auth() async throws -> Date? {
        let authURL = try api()
        let j = try await Net.json(authURL) as? [String: Any]
        guard let info = j?["user_info"] as? [String: Any] else { throw ProviderError.parse }
        if jint(info["auth"]) != 1 { throw ProviderError.auth }
        let status = jstr(info["status"]).lowercased()
        if !status.isEmpty && status != "active" { throw ProviderError.expired }
        let exp = jint(info["exp_date"])
        return exp > 0 ? Date(timeIntervalSince1970: TimeInterval(exp)) : nil
    }

    private func categories(_ action: String) async throws -> [Category] {
        let u = try api(action)
        return jlist(try await Net.json(u)).compactMap {
            let id = jstr($0["category_id"]); guard !id.isEmpty else { return nil }
            let n = jstr($0["category_name"])
            return Category(id: id, name: n.isEmpty ? id : n)
        }
    }

    func load(progress: @escaping (String) -> Void) async throws -> (Catalog, Date?) {
        progress(L.t("connecting"))
        let exp = try await auth()
        progress(L.t("loading"))
        let liveURL = try api("get_live_streams"), vodURL = try api("get_vod_streams"), serURL = try api("get_series")
        async let lc = categories("get_live_categories")
        async let mc = categories("get_vod_categories")
        async let sc = categories("get_series_categories")
        async let live = Net.json(liveURL)
        async let vod = Net.json(vodURL)
        async let ser = Net.json(serURL)
        var cat = Catalog()
        cat.liveCats = (try? await lc) ?? []
        cat.movieCats = (try? await mc) ?? []
        cat.seriesCats = (try? await sc) ?? []
        cat.live = jlist(try await live).enumerated().compactMap { i, s in
            let id = jstr(s["stream_id"]); guard !id.isEmpty else { return nil }
            let n = jint(s["num"])
            return MediaItem(id: id, name: jstr(s["name"]).trimmingCharacters(in: .whitespaces), logo: jstr(s["stream_icon"]),
                             category: jstr(s["category_id"]), num: n > 0 ? n : i + 1)
        }
        cat.movies = jlist(try await vod).compactMap { s in
            let id = jstr(s["stream_id"]); guard !id.isEmpty else { return nil }
            let y = jstr(s["year"]).isEmpty ? String(jstr(s["releaseDate"]).prefix(4)) : jstr(s["year"])
            return MediaItem(id: id, name: jstr(s["name"]).trimmingCharacters(in: .whitespaces), logo: jstr(s["stream_icon"]),
                             category: jstr(s["category_id"]), ext: jstr(s["container_extension"]),
                             rating: jstr(s["rating"]), year: y, added: jint(s["added"]))
        }
        cat.series = jlist(try await ser).compactMap { s in
            let id = jstr(s["series_id"]); guard !id.isEmpty else { return nil }
            let y = jstr(s["year"]).isEmpty ? String((jstr(s["releaseDate"]) + jstr(s["release_date"])).prefix(4)) : jstr(s["year"])
            return MediaItem(id: id, name: jstr(s["name"]).trimmingCharacters(in: .whitespaces), logo: jstr(s["cover"]),
                             category: jstr(s["category_id"]), rating: jstr(s["rating"]), year: y,
                             added: jint(s["last_modified"]), plot: jstr(s["plot"]))
        }
        return (cat, exp)
    }

    func vodInfo(_ id: String) async -> VodInfo? {
        guard let infoURL = try? api("get_vod_info", ["vod_id": id]),
              let j = try? await Net.json(infoURL) as? [String: Any],
              let i = j["info"] as? [String: Any] else { return nil }
        let bd = (i["backdrop_path"] as? [Any])?.first ?? i["backdrop_path"]
        return VodInfo(plot: jstr(i["plot"]).isEmpty ? jstr(i["description"]) : jstr(i["plot"]), genre: jstr(i["genre"]),
                       duration: jstr(i["duration"]), year: String((jstr(i["releasedate"]) + jstr(i["release_date"])).prefix(4)),
                       rating: jstr(i["rating"]), cast: jstr(i["cast"]).isEmpty ? jstr(i["actors"]) : jstr(i["cast"]),
                       director: jstr(i["director"]), image: jstr(i["movie_image"]).isEmpty ? jstr(i["cover_big"]) : jstr(i["movie_image"]),
                       backdrop: jstr(bd), tmdb: (jstr(i["tmdb_id"]) + jstr(i["tmdb"])).filter(\.isNumber))
    }

    func seriesInfo(_ id: String) async throws -> SeriesInfo {
        let infoURL = try api("get_series_info", ["series_id": id])
        guard let j = try await Net.json(infoURL) as? [String: Any] else { throw ProviderError.parse }
        let i = j["info"] as? [String: Any] ?? [:]
        var out = SeriesInfo()
        out.plot = jstr(i["plot"]); out.cover = jstr(i["cover"]); out.genre = jstr(i["genre"]); out.rating = jstr(i["rating"])
        out.year = String((jstr(i["releaseDate"]) + jstr(i["release_date"])).prefix(4))
        out.tmdb = (jstr(i["tmdb"]) + jstr(i["tmdb_id"])).filter(\.isNumber)
        out.backdrop = jstr((i["backdrop_path"] as? [Any])?.first ?? i["backdrop_path"])
        func add(_ e: [String: Any], _ key: Int) {
            let eid = jstr(e["id"]); guard !eid.isEmpty else { return }
            let s = jint(e["season"]) > 0 ? jint(e["season"]) : max(key, 1)
            let info = e["info"] as? [String: Any] ?? [:]
            out.seasons[s, default: []].append(Episode(id: eid, season: s, num: jint(e["episode_num"]), title: jstr(e["title"]),
                                                       ext: jstr(e["container_extension"]), plot: jstr(info["plot"]),
                                                       image: jstr(info["movie_image"]), duration: jint(info["duration_secs"])))
        }
        if let eps = j["episodes"] as? [String: Any] {
            for (k, v) in eps {
                if let arr = v as? [[String: Any]] { arr.forEach { add($0, Int(k) ?? 1) } }
                else if let d = v as? [String: Any] { add(d, Int(k) ?? 1) }
            }
        } else if let eps = j["episodes"] as? [[Any]] {
            eps.forEach { $0.compactMap { $0 as? [String: Any] }.forEach { add($0, 1) } }
        }
        for k in out.seasons.keys { out.seasons[k]?.sort { $0.num < $1.num } }
        return out
    }

    /// Now / next programmes of a live channel (title + description, base64 in the Xtream API).
    func shortEPG(_ streamID: String, limit: Int = 6) async -> [(start: Int, stop: Int, text: String)] {
        guard let u = try? api("get_short_epg", ["stream_id": streamID, "limit": "\(limit)"]),
              let j = try? await Net.json(u) as? [String: Any] else { return [] }
        func b64(_ s: String) -> String {
            guard let d = Data(base64Encoded: s, options: .ignoreUnknownCharacters), let t = String(data: d, encoding: .utf8) else { return s }
            return t
        }
        return jlist(j["epg_listings"]).map { e in
            (jint(e["start_timestamp"]), jint(e["stop_timestamp"]), b64(jstr(e["title"])) + " " + b64(jstr(e["description"])))
        }
    }

    func url(kind: MediaKind, item: MediaItem, episode: Episode? = nil) -> URL? {
        let u = user.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? user
        let p = pass.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? pass
        switch kind {
        case .live: return URL(string: "\(server)/live/\(u)/\(p)/\(item.id).ts")
        case .movie: return URL(string: "\(server)/movie/\(u)/\(p)/\(item.id).\(item.ext.isEmpty ? "mp4" : item.ext)")
        case .series:
            guard let e = episode else { return nil }
            return URL(string: "\(server)/series/\(u)/\(p)/\(e.id).\(e.ext.isEmpty ? "mp4" : e.ext)")
        }
    }
}

enum M3U {
    static func load(_ urlString: String, progress: @escaping (String) -> Void) async throws -> Catalog {
        guard let url = URL(string: urlString.trimmingCharacters(in: .whitespaces)) else { throw ProviderError.invalidURL }
        progress(L.t("loading"))
        let data = try await Net.data(url)
        guard let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1),
              text.contains("#EXT") else { throw ProviderError.parse }
        return parse(text)
    }

    static func parse(_ text: String) -> Catalog {
        var cat = Catalog()
        var cats: [MediaKind: [String: Category]] = [.live: [:], .movie: [:], .series: [:]]
        var pending: (name: String, logo: String, group: String)?
        var n = 0
        for raw in text.split(whereSeparator: \.isNewline) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("#EXTINF") {
                func attr(_ k: String) -> String {
                    guard let r = line.range(of: k + "=\"") else { return "" }
                    let rest = line[r.upperBound...]
                    return rest.firstIndex(of: "\"").map { String(rest[..<$0]) } ?? ""
                }
                let name = line.range(of: ",", options: .backwards).map { String(line[$0.upperBound...]).trimmingCharacters(in: .whitespaces) } ?? ""
                pending = (name, attr("tvg-logo"), attr("group-title"))
            } else if !line.isEmpty && !line.hasPrefix("#"), let p = pending {
                let lower = line.lowercased()
                let kind: MediaKind = lower.contains("/movie/") ? .movie : lower.contains("/series/") ? .series : .live
                let group = p.group.isEmpty ? (L.isArabic ? "أخرى" : "Other") : p.group
                let cid = String(group.unicodeScalars.reduce(UInt64(5381)) { ($0 &* 33) &+ UInt64($1.value) })
                cats[kind]?[cid] = Category(id: cid, name: group)
                n += 1
                var item = MediaItem(id: String(n), name: p.name, logo: p.logo, category: cid, num: n, url: line)
                if kind == .movie { item.ext = String(line.split(separator: ".").last ?? "mp4") }
                switch kind { case .live: cat.live.append(item); case .movie: cat.movies.append(item); case .series: cat.series.append(item) }
                pending = nil
            }
        }
        cat.liveCats = Array(cats[.live]!.values).sorted { $0.name < $1.name }
        cat.movieCats = Array(cats[.movie]!.values).sorted { $0.name < $1.name }
        cat.seriesCats = Array(cats[.series]!.values).sorted { $0.name < $1.name }
        return cat
    }
}
