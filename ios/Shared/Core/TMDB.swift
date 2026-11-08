import Foundation

struct TMDBCard: Codable, Hashable { var poster: String; var title: String; var id: Int }

struct TMDBPerson: Hashable { var name: String; var role: String; var photo: String }

struct TMDBInfo {
    var id: Int
    var title = "", original = "", overview = "", tagline = "", rating = "", year = ""
    var votes = 0, runtime = 0, seasons = 0
    var genres: [String] = []
    var backdrop = "", poster = "", logo = "", trailer = ""
    var cast: [TMDBPerson] = []
    var creators: [String] = []
}

struct TMDBEpisode { var name: String; var overview: String; var still: String }

/// Optional TMDB enrichment using the user's own key (Keychain). Responses are cached in memory and by URLCache.
actor TMDB {
    static let shared = TMDB()

    private var key: String = Keychain.get("tmdbKey") ?? ""
    private var cards: [String: TMDBCard] = Storage.load("tmdbCards", as: [String: TMDBCard].self) ?? [:]
    private var infos: [String: TMDBInfo] = [:]
    private var seasons: [String: [Int: TMDBEpisode]] = [:]
    private var dirty = 0
    private var running = 0

    nonisolated static var enabledNow: Bool { !(Keychain.get("tmdbKey") ?? "").isEmpty }
    private var lang: String { L.isArabic ? "ar" : "en" }

    func setKey(_ raw: String) async -> Bool {
        let k = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !k.isEmpty, (try? await get("configuration", [:], key: k)) != nil else { return false }
        key = k
        Keychain.set(k, for: "tmdbKey")
        cards = [:]; infos = [:]; seasons = [:]
        Storage.save("tmdbCards", cards)
        return true
    }

    func removeKey() {
        key = ""; cards = [:]; infos = [:]
        Keychain.set(nil, for: "tmdbKey")
        Storage.save("tmdbCards", cards)
    }

    private func get(_ path: String, _ params: [String: String], key override: String? = nil) async throws -> [String: Any] {
        let k = override ?? key
        let bearer = k.hasPrefix("eyJ")
        var c = URLComponents(string: "https://api.themoviedb.org/3/" + path)!
        var q = params.map { URLQueryItem(name: $0.key, value: $0.value) }
        if !bearer { q.append(URLQueryItem(name: "api_key", value: k)) }
        c.queryItems = q
        let j = try await Net.json(c.url!, headers: bearer ? ["Authorization": "Bearer \(k)"] : [:])
        return j as? [String: Any] ?? [:]
    }

    private func img(_ path: String, _ size: String) -> String { path.isEmpty ? "" : "https://image.tmdb.org/t/p/\(size)\(path)" }

    /// Localised poster + title for list cards.
    func card(kind: MediaKind, name: String, year: String) async -> TMDBCard? {
        guard !key.isEmpty, kind != .live else { return nil }
        let c = Clean.full(name)
        let y = year.prefix(4).allSatisfy(\.isNumber) && year.count >= 4 ? String(year.prefix(4)) : c.year
        let ck = "\(kind.rawValue)|\(lang)|\(c.title)|\(y)"
        if let hit = cards[ck] { return hit.id == 0 && hit.poster.isEmpty ? nil : hit }
        while running >= 4 { try? await Task.sleep(nanoseconds: 80_000_000) }
        running += 1
        defer { running -= 1 }
        let tv = kind == .series
        var params = ["query": c.title, "language": lang, "include_adult": "false"]
        if !y.isEmpty { params[tv ? "first_air_date_year" : "year"] = y }
        let path = tv ? "search/tv" : "search/movie"
        var results = ((try? await get(path, params))?["results"] as? [[String: Any]]) ?? []
        if results.isEmpty && !y.isEmpty {
            params.removeValue(forKey: tv ? "first_air_date_year" : "year")
            results = ((try? await get(path, params))?["results"] as? [[String: Any]]) ?? []
        }
        let f = results.first
        let card = TMDBCard(poster: img(jstr(f?["poster_path"]), "w342"), title: jstr(f?[tv ? "name" : "title"]), id: jint(f?["id"]))
        cards[ck] = card
        dirty += 1
        if dirty % 20 == 0 { Storage.save("tmdbCards", cards) }
        return card.id == 0 ? nil : card
    }

    func details(kind: MediaKind, tmdbID: String, name: String, year: String) async -> TMDBInfo? {
        guard !key.isEmpty, kind != .live else { return nil }
        let ik = "\(kind.rawValue)|\(lang)|\(tmdbID.isEmpty ? Clean.title(name) + "|" + year : tmdbID)"
        if let hit = infos[ik] { return hit }
        var id = Int(tmdbID) ?? 0
        if id == 0 { id = await card(kind: kind, name: name, year: year)?.id ?? 0 }
        guard id > 0 else { return nil }
        let tv = kind == .series
        let t = tv ? "tv" : "movie"
        guard let o = try? await get("\(t)/\(id)", ["language": lang,
                                                     "append_to_response": "credits,images,videos",
                                                     "include_image_language": "\(lang),en,null"]) else { return nil }
        var en: [String: Any]? = nil
        if lang != "en" && (jstr(o["overview"]).isEmpty || jstr(o[tv ? "name" : "title"]).isEmpty) {
            en = try? await get("\(t)/\(id)", ["language": "en-US"])
        }
        func pick(_ list: String, _ langs: [String?]) -> String {
            let arr = ((o["images"] as? [String: Any])?[list] as? [[String: Any]]) ?? []
            for l in langs {
                func score(_ d: [String: Any]) -> Double {
                    let avg: Double = Double(jstr(d["vote_average"])) ?? 0
                    let cnt: Double = Double(jint(d["vote_count"]))
                    return avg * 10 + cnt
                }
                let best = arr.filter { ($0["iso_639_1"] as? String) == l }.max { score($0) < score($1) }
                if let b = best { return jstr(b["file_path"]) }
            }
            return ""
        }
        var info = TMDBInfo(id: id)
        info.title = jstr(o[tv ? "name" : "title"]).isEmpty ? jstr(en?[tv ? "name" : "title"]) : jstr(o[tv ? "name" : "title"])
        info.original = jstr(o[tv ? "original_name" : "original_title"])
        info.overview = jstr(o["overview"]).isEmpty ? jstr(en?["overview"]) : jstr(o["overview"])
        info.tagline = jstr(o["tagline"])
        let va = Double(jstr(o["vote_average"])) ?? 0
        info.rating = va > 0 ? String(format: "%.1f", va) : ""
        info.votes = jint(o["vote_count"])
        info.year = String(jstr(o[tv ? "first_air_date" : "release_date"]).prefix(4))
        info.runtime = tv ? jint((o["episode_run_time"] as? [Any])?.first) : jint(o["runtime"])
        info.seasons = jint(o["number_of_seasons"])
        info.genres = ((o["genres"] as? [[String: Any]]) ?? []).map { jstr($0["name"]) }.filter { !$0.isEmpty }
        let bd = pick("backdrops", [nil]); info.backdrop = img(bd.isEmpty ? jstr(o["backdrop_path"]) : bd, "w1280")
        let ps = pick("posters", [lang]); info.poster = img(ps.isEmpty ? jstr(o["poster_path"]) : ps, "w500")
        info.logo = img(pick("logos", [lang, "en", nil]), "w500")
        let credits = o["credits"] as? [String: Any] ?? [:]
        info.cast = ((credits["cast"] as? [[String: Any]]) ?? []).prefix(15).map {
            TMDBPerson(name: jstr($0["name"]), role: jstr($0["character"]), photo: img(jstr($0["profile_path"]), "w185"))
        }
        info.creators = tv ? ((o["created_by"] as? [[String: Any]]) ?? []).map { jstr($0["name"]) }
            : ((credits["crew"] as? [[String: Any]]) ?? []).filter { jstr($0["job"]) == "Director" }.map { jstr($0["name"]) }
        func trailerKey(_ dict: [String: Any]?) -> String {
            var list: [[String: Any]] = []
            if let v = dict?["videos"] as? [String: Any], let r = v["results"] as? [[String: Any]] { list = r }
            else if let r = dict?["results"] as? [[String: Any]] { list = r }
            let vids = list.filter { jstr($0["site"]) == "YouTube" && ["Trailer", "Teaser"].contains(jstr($0["type"])) }
            return jstr((vids.first { jstr($0["type"]) == "Trailer" } ?? vids.first)?["key"])
        }
        info.trailer = trailerKey(o)
        if info.trailer.isEmpty && lang != "en" {
            info.trailer = trailerKey(try? await get("\(t)/\(id)/videos", ["language": "en-US"]))
        }
        infos[ik] = info
        return info
    }

    func season(tvID: Int, season: Int) async -> [Int: TMDBEpisode] {
        guard !key.isEmpty, tvID > 0 else { return [:] }
        let sk = "\(tvID)|\(season)|\(lang)"
        if let hit = seasons[sk] { return hit }
        guard let o = try? await get("tv/\(tvID)/season/\(season)", ["language": lang]) else { return [:] }
        var map: [Int: TMDBEpisode] = [:]
        for e in (o["episodes"] as? [[String: Any]]) ?? [] {
            let n = jstr(e["name"])
            let generic = n.range(of: #"^(Episode|الحلقة)\s*\d+$"#, options: [.regularExpression, .caseInsensitive]) != nil
            map[jint(e["episode_number"])] = TMDBEpisode(name: generic ? "" : n, overview: jstr(e["overview"]), still: img(jstr(e["still_path"]), "w300"))
        }
        seasons[sk] = map
        return map
    }
}
