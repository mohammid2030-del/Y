import Foundation

struct TMDBCard: Codable, Hashable { var poster: String; var title: String; var id: Int; var backdrop: String? = nil; var genres: [Int]? = nil }

struct TMDBPerson: Hashable { var name: String; var role: String; var photo: String; var id: Int = 0 }

struct KnownWork: Hashable, Identifiable { var id: Int; var poster: String; var title: String; var original: String; var isTV: Bool; var year: String }

struct TMDBPersonInfo { var bio = ""; var born = ""; var place = ""; var knownFor: [KnownWork] = [] }

struct TMDBInfo {
    var id: Int
    var title = "", original = "", overview = "", tagline = "", rating = "", year = ""
    var votes = 0, runtime = 0, seasons = 0
    var genres: [String] = []
    var backdrop = "", poster = "", logo = "", trailer = ""
    /// Usable trailers best first (never an episode preview); `trailer` is the first.
    var trailers: [String] = []
    /// The work's original language (tr, ar, en…): its trailer comes first.
    var originalLanguage = ""
    /// Logo in the app language only, and whether TMDB has the title + story in the app language.
    var logoLocal = "", localText = false
    /// The film series this movie belongs to (TMDB collection), 0 when none.
    var collectionID = 0, collectionName = ""
    var cast: [TMDBPerson] = []
    var creators: [String] = []
}

struct TMDBEpisode { var name: String; var overview: String; var still: String }

/// Thread-safe "title → TMDB id" registry shared with the library index, so copies of a title with different
/// names (Arabic / English / regional) merge into one card once TMDB has identified them.
final class TMDBIDs: @unchecked Sendable {
    static let shared = TMDBIDs()
    private let lock = NSLock()
    private var map: [String: Int] = [:]
    private var ver = 0

    init() {
        for (k, v) in Storage.loadFile("tmdbCards3", as: [String: TMDBCard].self) ?? [:] where v.id > 0 { map[TMDBIDs.strip(k)] = v.id }
    }

    /// "series|ar|Zmora|2026" → "series|Zmora|2026" (the id is the same in every language).
    static func strip(_ ck: String) -> String {
        let p = ck.split(separator: "|", omittingEmptySubsequences: false)
        guard p.count >= 4 else { return ck }
        return ([p[0]] + p[2...]).joined(separator: "|")
    }

    func set(_ ck: String, _ id: Int) {
        lock.lock(); map[TMDBIDs.strip(ck)] = id; ver += 1; lock.unlock()
    }
    func id(_ key: String) -> Int? {
        lock.lock(); defer { lock.unlock() }
        return map[key]
    }
    var version: Int { lock.lock(); defer { lock.unlock() }; return ver }
}

/// Optional TMDB enrichment using the user's own key (Keychain). Responses are cached in memory and by URLCache.
actor TMDB {
    static let shared = TMDB()

    private var key: String = Keychain.get("tmdbKey") ?? ""
    private var cards: [String: TMDBCard] = Storage.loadFile("tmdbCards3", as: [String: TMDBCard].self) ?? [:]
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
        Storage.saveFile("tmdbCards3", cards)
        return true
    }

    func removeKey() {
        key = ""; cards = [:]; infos = [:]
        Keychain.set(nil, for: "tmdbKey")
        Storage.saveFile("tmdbCards3", cards)
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

    /// True for titles in a script the viewer probably can't read (Cyrillic, CJK, Thai …): TMDB falls back to the
    /// original title when there is no Arabic translation, so we show the English title instead.
    nonisolated static func foreignScript(_ s: String) -> Bool {
        for u in s.unicodeScalars where u.properties.isAlphabetic {
            let v = u.value
            let latin = v < 0x0250 || (0x1E00...0x1EFF).contains(v)
            let arabic = (0x0600...0x06FF).contains(v) || (0x0750...0x077F).contains(v) || (0xFB50...0xFEFF).contains(v)
            if !latin && !arabic { return true }
        }
        return false
    }

    private func englishTitle(_ tv: Bool, _ id: Int) async -> String {
        guard id > 0, let o = try? await get("\(tv ? "tv" : "movie")/\(id)", ["language": "en-US"]) else { return "" }
        return jstr(o[tv ? "name" : "title"])
    }

    private func img(_ path: String, _ size: String) -> String { path.isEmpty ? "" : "https://image.tmdb.org/t/p/\(size)\(path)" }

    /// Localised poster + title for list cards.
    func card(kind: MediaKind, name: String, year: String) async -> TMDBCard? {
        guard !key.isEmpty, kind != .live else { return nil }
        let c = Clean.full(name)
        let y = year.prefix(4).allSatisfy(\.isNumber) && year.count >= 4 ? String(year.prefix(4)) : c.year
        let ck = "\(kind.rawValue)|\(lang)|\(c.title)|\(y)"
        if let hit = cards[ck] { return hit.id == 0 && hit.poster.isEmpty ? nil : hit }
        while running >= 6 {
            if Task.isCancelled { return nil }          // card scrolled away: don't spin
            try? await Task.sleep(nanoseconds: 60_000_000)
        }
        if Task.isCancelled { return nil }
        running += 1
        defer { running -= 1 }
        let tv = kind == .series
        var params = ["query": c.title, "language": lang, "include_adult": "false"]
        if !y.isEmpty { params[tv ? "first_air_date_year" : "year"] = y }
        let path = tv ? "search/tv" : "search/movie"
        // A failed request (offline, rate limit) is not cached as "no poster", so it is retried next time.
        guard var results = (try? await get(path, params))?["results"] as? [[String: Any]] else { return nil }
        if results.isEmpty && !y.isEmpty {
            params.removeValue(forKey: tv ? "first_air_date_year" : "year")
            guard let r2 = (try? await get(path, params))?["results"] as? [[String: Any]] else { return nil }
            results = r2
        }
        let f = results.first
        var title = jstr(f?[tv ? "name" : "title"])
        if TMDB.foreignScript(title) { let en = await englishTitle(tv, jint(f?["id"])); if !en.isEmpty { title = en } }
        let card = TMDBCard(poster: img(jstr(f?["poster_path"]), "w342"), title: title, id: jint(f?["id"]),
                            backdrop: img(jstr(f?["backdrop_path"]), "w780"),
                            genres: (f?["genre_ids"] as? [Any])?.map { jint($0) } ?? [])
        cards[ck] = card
        if card.id > 0 { TMDBIDs.shared.set(ck, card.id) }
        dirty += 1
        if dirty % 20 == 0 { Storage.saveFile("tmdbCards3", cards) }
        return card.id == 0 ? nil : card
    }

    /// Genre ids of a title (looked up once, then cached). Empty when TMDB doesn't know it.
    func genres(kind: MediaKind, name: String, year: String) async -> [Int] {
        await card(kind: kind, name: name, year: year)?.genres ?? []
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
                                                     "append_to_response": "credits,images,videos,translations",
                                                     "include_image_language": "\(lang),en,null",
                                                     "include_video_language": "\(lang),ar,en,tr,null"]) else { return nil }
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
        if TMDB.foreignScript(info.title) {
            let enTitle = en.map { jstr($0[tv ? "name" : "title"]) } ?? ""
            let t2 = enTitle.isEmpty ? await englishTitle(tv, id) : enTitle
            if !t2.isEmpty { info.title = t2 }
        }
        info.original = jstr(o[tv ? "original_name" : "original_title"])
        info.overview = jstr(o["overview"]).isEmpty ? jstr(en?["overview"]) : jstr(o["overview"])
        info.tagline = jstr(o["tagline"])
        // TMDB often has the Arabic text under another Arabic region (ar-SA, ar-AE…): use it before any translation
        if lang == "ar", let tr = TMDB.translation(o, "ar") {
            if !Translator.isArabic(info.overview), !jstr(tr["overview"]).isEmpty { info.overview = jstr(tr["overview"]) }
            if !jstr(tr["tagline"]).isEmpty, !Translator.isArabic(info.tagline) { info.tagline = jstr(tr["tagline"]) }
        }
        let va = Double(jstr(o["vote_average"])) ?? 0
        info.rating = va > 0 ? String(format: "%.1f", va) : ""
        info.votes = jint(o["vote_count"])
        info.year = String(jstr(o[tv ? "first_air_date" : "release_date"]).prefix(4))
        info.runtime = tv ? jint((o["episode_run_time"] as? [Any])?.first) : jint(o["runtime"])
        info.seasons = jint(o["number_of_seasons"])
        info.originalLanguage = jstr(o["original_language"]).lowercased()
        if let col = o["belongs_to_collection"] as? [String: Any] { info.collectionID = jint(col["id"]); info.collectionName = jstr(col["name"]) }
        info.genres = ((o["genres"] as? [[String: Any]]) ?? []).map { jstr($0["name"]) }.filter { !$0.isEmpty }
        let bd = pick("backdrops", [nil]); info.backdrop = img(bd.isEmpty ? jstr(o["backdrop_path"]) : bd, "w1280")
        let ps = pick("posters", [lang]); info.poster = img(ps.isEmpty ? jstr(o["poster_path"]) : ps, "w500")
        info.logo = img(pick("logos", [lang, "en", nil]), "w500")
        info.logoLocal = img(pick("logos", [lang]), "w500")
        info.localText = !jstr(o["overview"]).isEmpty && !TMDB.foreignScript(jstr(o[tv ? "name" : "title"]))
        let credits = o["credits"] as? [String: Any] ?? [:]
        info.cast = ((credits["cast"] as? [[String: Any]]) ?? []).prefix(15).map {
            TMDBPerson(name: jstr($0["name"]), role: jstr($0["character"]), photo: img(jstr($0["profile_path"]), "w185"), id: jint($0["id"]))
        }
        info.creators = tv ? ((o["created_by"] as? [[String: Any]]) ?? []).map { jstr($0["name"]) }
            : ((credits["crew"] as? [[String: Any]]) ?? []).filter { jstr($0["job"]) == "Director" }.map { jstr($0["name"]) }
        func trailerKeys(_ dict: [String: Any]?) -> [String] {
            var list: [[String: Any]] = []
            if let v = dict?["videos"] as? [String: Any], let r = v["results"] as? [[String: Any]] { list = r }
            else if let r = dict?["results"] as? [[String: Any]] { list = r }
            // trailer in the app language first, then English / any; trailer > teaser > clip > featurette
            let types = ["Trailer": 0, "Teaser": 1, "Clip": 2, "Featurette": 3]
            func rank(_ v: [String: Any]) -> Double {
                let l = jstr(v["iso_639_1"])
                return Double((types[jstr(v["type"])] ?? 9) * 10 + (l == lang ? 0 : l == "en" ? 1 : 2)) - ((v["official"] as? Bool) == true ? 0.5 : 0)
            }
            let vids = list.filter { jstr($0["site"]) == "YouTube" && types[jstr($0["type"])] != nil && TrailerLauncher.episodeOf(jstr($0["name"])) <= 1 }
                .sorted { rank($0) < rank($1) }
            return vids.prefix(6).map { jstr($0["key"]) }.filter { !$0.isEmpty }
        }
        info.trailers = trailerKeys(o)
        if info.trailers.isEmpty && lang != "en" {
            info.trailers = trailerKeys(try? await get("\(t)/\(id)/videos", ["language": "en-US"]))
        }
        info.trailer = info.trailers.first ?? ""
        infos[ik] = info
        return info
    }

    /// Biography and best-known titles of a cast member.
    private var collections: [String: (String, [KnownWork])] = [:]

    /// Every part of a film series, in release order.
    func collection(_ id: Int) async -> (name: String, parts: [KnownWork])? {
        guard !key.isEmpty, id > 0 else { return nil }
        let ck = "\(lang)|\(id)"
        if let hit = collections[ck] { return hit }
        guard let o = try? await get("collection/\(id)", ["language": lang]) else { return nil }
        let parts = ((o["parts"] as? [[String: Any]]) ?? [])
            .filter { !jstr($0["release_date"]).isEmpty || !jstr($0["poster_path"]).isEmpty }
            .sorted { (jstr($0["release_date"]).isEmpty ? "9999" : jstr($0["release_date"])) < (jstr($1["release_date"]).isEmpty ? "9999" : jstr($1["release_date"])) }
            .map { d in KnownWork(id: jint(d["id"]), poster: img(jstr(d["poster_path"]), "w342"), title: jstr(d["title"]),
                                  original: jstr(d["original_title"]), isTV: false, year: String(jstr(d["release_date"]).prefix(4))) }
        let out = (jstr(o["name"]), parts)
        collections[ck] = out
        return out
    }

    /// The text of a translation in `lang` (any region), from `append_to_response=translations`.
    static func translation(_ o: [String: Any], _ lang: String) -> [String: Any]? {
        let list = ((o["translations"] as? [String: Any])?["translations"] as? [[String: Any]]) ?? []
        let ar = list.filter { jstr($0["iso_639_1"]) == lang }.compactMap { $0["data"] as? [String: Any] }
        return ar.first { !jstr($0["overview"]).isEmpty || !jstr($0["biography"]).isEmpty } ?? ar.first
    }

    func person(_ id: Int) async -> TMDBPersonInfo? {
        guard !key.isEmpty, id > 0,
              let o = try? await get("person/\(id)", ["language": lang, "append_to_response": "combined_credits,translations"]) else { return nil }
        var p = TMDBPersonInfo()
        p.bio = jstr(o["biography"])
        if lang == "ar", !Translator.isArabic(p.bio), let tr = TMDB.translation(o, "ar"), !jstr(tr["biography"]).isEmpty { p.bio = jstr(tr["biography"]) }
        if p.bio.isEmpty && lang != "en", let en = try? await get("person/\(id)", ["language": "en-US"]) { p.bio = jstr(en["biography"]) }
        p.born = jstr(o["birthday"])
        p.place = jstr(o["place_of_birth"])
        let cast = ((o["combined_credits"] as? [String: Any])?["cast"] as? [[String: Any]]) ?? []
        var seen = Set<Int>()
        p.knownFor = cast.sorted { (Double(jstr($0["vote_count"])) ?? 0) > (Double(jstr($1["vote_count"])) ?? 0) }
            .filter { !jstr($0["poster_path"]).isEmpty && seen.insert(jint($0["id"])).inserted }
            .prefix(12)
            .map { d -> KnownWork in
                let tv = jstr(d["media_type"]) == "tv"
                return KnownWork(id: jint(d["id"]), poster: img(jstr(d["poster_path"]), "w342"),
                                 title: jstr(d[tv ? "name" : "title"]), original: jstr(d[tv ? "original_name" : "original_title"]), isTV: tv,
                                 year: String(jstr(d[tv ? "first_air_date" : "release_date"]).prefix(4)))
            }
        return p
    }

    private var videoLists: [String: [String]] = [:]
    /// Trailers / teasers of a title or of one season, original language first (a Turkish series' Turkish trailer,
    /// never a dubbed one first), then the app language, then English; never an episode preview.
    func trailers(tv: Bool, id: Int, original: String, season: Int) async -> [String] {
        guard !key.isEmpty, id > 0 else { return [] }
        let path = season > 0 ? "tv/\(id)/season/\(season)/videos" : "\(tv ? "tv" : "movie")/\(id)/videos"
        let langs = [original, lang, "en"].filter { !$0.isEmpty }.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
        let ck = "\(path)|\(langs.joined(separator: ","))"
        if let hit = videoLists[ck] { return hit }
        guard let o = try? await get(path, ["language": original.isEmpty ? lang : original, "include_video_language": langs.joined(separator: ",") + ",null"]) else { return [] }
        func rank(_ v: [String: Any]) -> Double {
            let li = langs.firstIndex(of: jstr(v["iso_639_1"])) ?? 3
            return Double(li * 10 + (jstr(v["type"]) == "Trailer" ? 0 : 1)) - ((v["official"] as? Bool) == true ? 0.5 : 0)
        }
        let list = ((o["results"] as? [[String: Any]]) ?? [])
            .filter { jstr($0["site"]) == "YouTube" && ["Trailer", "Teaser"].contains(jstr($0["type"])) && TrailerLauncher.episodeOf(jstr($0["name"])) <= 1 }
            .sorted { rank($0) < rank($1) }
        let out = list.prefix(8).map { jstr($0["key"]) }.filter { !$0.isEmpty }
        videoLists[ck] = out
        return out
    }

    private var seasonVideos: [String: [String]] = [:]
    /// Trailers / teasers of one season (TMDB season videos, best first), [] when TMDB has none.
    func seasonTrailer(tvID: Int, season: Int) async -> [String] {
        guard !key.isEmpty, tvID > 0 else { return [] }
        let ck = "\(tvID)|\(season)|\(lang)"
        if let hit = seasonVideos[ck] { return hit }
        let l = lang
        guard let o = try? await get("tv/\(tvID)/season/\(season)/videos", ["language": l, "include_video_language": "\(l),en,null"]) else { return [] }
        func rank(_ v: [String: Any]) -> Double {
            (jstr(v["type"]) == "Trailer" ? 0 : 10) + (jstr(v["iso_639_1"]) == l ? 0 : jstr(v["iso_639_1"]) == "en" ? 1 : 2) - ((v["official"] as? Bool) == true ? 0.5 : 0)
        }
        let list = ((o["results"] as? [[String: Any]]) ?? [])
            .filter { jstr($0["site"]) == "YouTube" && ["Trailer", "Teaser"].contains(jstr($0["type"])) && TrailerLauncher.episodeOf(jstr($0["name"])) <= 1 }
            .sorted { rank($0) < rank($1) }
        let k = list.prefix(6).map { jstr($0["key"]) }.filter { !$0.isEmpty }
        seasonVideos[ck] = k
        return k
    }

    private var searches: [String: [KnownWork]] = [:]
    /// TMDB search over movies and series in any language ("تمرد" finds the film whatever the subscription calls it).
    func searchAll(_ q: String) async -> [KnownWork] {
        guard !key.isEmpty, !q.isEmpty else { return [] }
        let ck = "\(lang)|\(q.lowercased())"
        if let hit = searches[ck] { return hit }
        guard let o = try? await get("search/multi", ["query": q, "language": lang, "include_adult": "false"]) else { return [] }
        let out = ((o["results"] as? [[String: Any]]) ?? []).filter { ["movie", "tv"].contains(jstr($0["media_type"])) }.prefix(12).map { d -> KnownWork in
            let tv = jstr(d["media_type"]) == "tv"
            return KnownWork(id: jint(d["id"]), poster: img(jstr(d["poster_path"]), "w342"), title: jstr(d[tv ? "name" : "title"]),
                             original: jstr(d[tv ? "original_name" : "original_title"]), isTV: tv,
                             year: String(jstr(d[tv ? "first_air_date" : "release_date"]).prefix(4)))
        }
        searches[ck] = Array(out)
        return Array(out)
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
