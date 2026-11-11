import Foundation

/// Everything shown on a match's own page: venue (with a photo), kick-off in the stadium's and the viewer's time,
/// both teams' form and line-up (or squad), last meetings, table positions and the official broadcaster.
struct MatchDetails {
    struct Player: Hashable { var name: String; var number: String; var position: String; var starter: Bool }
    struct Side { var form: [String] = []; var players: [Player] = []; var formation = ""; var rank = ""; var points = ""; var lineup = false }
    struct Meeting: Hashable { var date: String; var home: String; var away: String; var score: String }

    var venue = "", city = "", country = "", capacity = 0, venueImage = ""
    var venueZone: TimeZone?
    var home = Side(), away = Side()
    var meetings: [Meeting] = []
    var referee = ""
}

extension Matches {
    /// The official broadcaster of a competition in the region (Arabic name in Arabic).
    static func broadcaster(_ slug: String) -> String {
        let ar = L.isArabic
        switch slug {
        case "uefa.champions", "uefa.europa", "uefa.europa.conf", "eng.1", "esp.1", "fra.1", "fifa.world", "eng.fa", "uefa.nations", "caf.nations", "afc.asian.cup":
            return ar ? "بي إن سبورتس" : "beIN SPORTS"
        case "ger.1": return ar ? "إم بي سي أكشن · شاهد" : "MBC Action · Shahid"
        case "ksa.1": return ar ? "ثمانية" : "Thmanyah"
        case "ita.1": return ar ? "أبوظبي الرياضية · ستارزبلاي" : "Abu Dhabi Sports · STARZPLAY"
        case "afc.champions": return ar ? "ثمانية · الكأس" : "Thmanyah · Alkass"
        case "fifa.worldq.afc": return ar ? "السعودية الرياضية · الكأس · بي إن" : "KSA Sports · Alkass · beIN"
        case "tur.1": return ar ? "إس بورت" : "S Sport"
        default: return network(slug)
        }
    }

    private static let zones: [String: String] = [
        "england": "Europe/London", "scotland": "Europe/London", "wales": "Europe/London", "united kingdom": "Europe/London",
        "spain": "Europe/Madrid", "italy": "Europe/Rome", "germany": "Europe/Berlin", "france": "Europe/Paris",
        "netherlands": "Europe/Amsterdam", "portugal": "Europe/Lisbon", "turkey": "Europe/Istanbul", "türkiye": "Europe/Istanbul",
        "belgium": "Europe/Brussels", "switzerland": "Europe/Zurich", "austria": "Europe/Vienna", "greece": "Europe/Athens",
        "saudi arabia": "Asia/Riyadh", "qatar": "Asia/Qatar", "united arab emirates": "Asia/Dubai", "uae": "Asia/Dubai",
        "kuwait": "Asia/Kuwait", "bahrain": "Asia/Bahrain", "oman": "Asia/Muscat", "iraq": "Asia/Baghdad", "jordan": "Asia/Amman",
        "egypt": "Africa/Cairo", "morocco": "Africa/Casablanca", "algeria": "Africa/Algiers", "tunisia": "Africa/Tunis",
        "iran": "Asia/Tehran", "japan": "Asia/Tokyo", "south korea": "Asia/Seoul", "korea republic": "Asia/Seoul", "china": "Asia/Shanghai",
        "australia": "Australia/Sydney", "brazil": "America/Sao_Paulo", "argentina": "America/Argentina/Buenos_Aires",
        "mexico": "America/Mexico_City", "usa": "America/New_York", "united states": "America/New_York", "canada": "America/Toronto"
    ]
    private static let leagueZones: [String: String] = [
        "eng.1": "Europe/London", "eng.fa": "Europe/London", "esp.1": "Europe/Madrid", "esp.copa_del_rey": "Europe/Madrid",
        "ita.1": "Europe/Rome", "ger.1": "Europe/Berlin", "fra.1": "Europe/Paris", "ned.1": "Europe/Amsterdam", "por.1": "Europe/Lisbon",
        "tur.1": "Europe/Istanbul", "ksa.1": "Asia/Riyadh", "usa.1": "America/New_York"
    ]

    /// The match page's data (ESPN's public match summary; the squads when no line-up is out yet).
    static func details(_ m: Match) async -> MatchDetails {
        var d = MatchDetails()
        guard let url = URL(string: "https://site.api.espn.com/apis/site/v2/sports/soccer/\(m.slug)/summary?event=\(m.id)"),
              let j = try? await Net.json(url) as? [String: Any] else { return d }
        let gi = j["gameInfo"] as? [String: Any] ?? [:]
        if let v = gi["venue"] as? [String: Any] {
            d.venue = jstr(v["fullName"])
            let a = v["address"] as? [String: Any] ?? [:]
            d.city = jstr(a["city"]); d.country = jstr(a["country"])
            d.capacity = jint(v["capacity"])
            d.venueImage = jstr(((v["images"] as? [[String: Any]])?.first)?["href"])
        }
        d.referee = jstr(((gi["officials"] as? [[String: Any]])?.first)?["displayName"])
        let zoneID = zones[d.country.lowercased()] ?? leagueZones[m.slug]
        d.venueZone = zoneID.flatMap(TimeZone.init(identifier:))

        // line-ups (about an hour before kick-off)
        for r in (j["rosters"] as? [[String: Any]]) ?? [] {
            let tid = jstr((r["team"] as? [String: Any])?["id"])
            let players = ((r["roster"] as? [[String: Any]]) ?? []).map { p -> MatchDetails.Player in
                let a = p["athlete"] as? [String: Any] ?? [:]
                return MatchDetails.Player(name: jstr(a["displayName"]), number: jstr(p["jersey"]).isEmpty ? jstr(a["jersey"]) : jstr(p["jersey"]),
                                           position: jstr((p["position"] as? [String: Any])?["abbreviation"]), starter: (p["starter"] as? Bool) ?? false)
            }.filter { !$0.name.isEmpty }
            guard !players.isEmpty else { continue }
            var side = MatchDetails.Side(players: players, formation: jstr(r["formation"]), lineup: true)
            if tid == m.away.id { side.form = d.away.form; d.away = side } else if tid == m.home.id { side.form = d.home.form; d.home = side }
        }
        // last five results
        for f in ((j["boxscore"] as? [String: Any])?["form"] as? [[String: Any]]) ?? [] {
            let tid = jstr((f["team"] as? [String: Any])?["id"])
            let form = ((f["events"] as? [[String: Any]]) ?? []).prefix(5).map { jstr($0["gameResult"]) }.filter { !$0.isEmpty }
            if tid == m.home.id { d.home.form = form } else if tid == m.away.id { d.away.form = form }
        }
        // last meetings
        for h in ((j["headToHeadGames"] as? [[String: Any]]) ?? []).prefix(1) {
            for e in ((h["events"] as? [[String: Any]]) ?? []).prefix(5) {
                let date = String(jstr(e["gameDate"]).prefix(10))
                let home = jstr((e["homeTeam"] as? [String: Any])?["displayName"]).isEmpty ? m.home.short : jstr((e["homeTeam"] as? [String: Any])?["displayName"])
                let away = jstr((e["awayTeam"] as? [String: Any])?["displayName"]).isEmpty ? m.away.short : jstr((e["awayTeam"] as? [String: Any])?["displayName"])
                let score = jstr(e["score"]).isEmpty ? "\(jstr(e["homeTeamScore"])) - \(jstr(e["awayTeamScore"]))" : jstr(e["score"])
                d.meetings.append(MatchDetails.Meeting(date: date, home: home, away: away, score: score))
            }
        }
        // table positions
        for g in (((j["standings"] as? [String: Any])?["groups"] as? [[String: Any]]) ?? []) {
            let entries = ((g["standings"] as? [String: Any])?["entries"] as? [[String: Any]]) ?? []
            for (i, e) in entries.enumerated() {
                let tid = jstr((e["team"] as? [String: Any])?["id"]).isEmpty ? jstr(e["id"]) : jstr((e["team"] as? [String: Any])?["id"])
                let stats = (e["stats"] as? [[String: Any]]) ?? []
                let pts = jstr(stats.first { jstr($0["name"]) == "points" }?["displayValue"])
                let rank = jstr(stats.first { jstr($0["name"]) == "rank" }?["displayValue"])
                if tid == m.home.id { d.home.rank = rank.isEmpty ? "\(i + 1)" : rank; d.home.points = pts }
                if tid == m.away.id { d.away.rank = rank.isEmpty ? "\(i + 1)" : rank; d.away.points = pts }
            }
        }
        // no line-up yet: the squads
        let needHome = d.home.players.isEmpty, needAway = d.away.players.isEmpty
        let slug = m.slug, homeID = m.home.id, awayID = m.away.id
        async let hs = squad(slug, homeID, needed: needHome)
        async let as_ = squad(slug, awayID, needed: needAway)
        let (h, a) = await (hs, as_)
        if !h.isEmpty { d.home.players = h }
        if !a.isEmpty { d.away.players = a }
        if d.venueImage.isEmpty, !d.venue.isEmpty { d.venueImage = await wikiImage(d.venue) }
        return d
    }

    private static func squad(_ slug: String, _ teamID: String, needed: Bool) async -> [MatchDetails.Player] {
        guard needed, !teamID.isEmpty, let url = URL(string: "https://site.api.espn.com/apis/site/v2/sports/soccer/\(slug)/teams/\(teamID)/roster"),
              let j = try? await Net.json(url) as? [String: Any] else { return [] }
        let order = ["G": 0, "GK": 0, "D": 1, "M": 2, "F": 3]
        return ((j["athletes"] as? [[String: Any]]) ?? []).map { a in
            MatchDetails.Player(name: jstr(a["displayName"]), number: jstr(a["jersey"]),
                                position: jstr((a["position"] as? [String: Any])?["abbreviation"]), starter: false)
        }
        .filter { !$0.name.isEmpty }
        .sorted { (order[$0.position] ?? 4, Int($0.number) ?? 99) < (order[$1.position] ?? 4, Int($1.number) ?? 99) }
    }

    /// A photo of the stadium from Wikipedia (its page's main image).
    private static func wikiImage(_ venue: String) async -> String {
        let title = venue.replacingOccurrences(of: " ", with: "_")
        guard let enc = title.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
              let url = URL(string: "https://en.wikipedia.org/api/rest_v1/page/summary/\(enc)"),
              let j = try? await Net.json(url) as? [String: Any] else { return "" }
        return jstr((j["originalimage"] as? [String: Any])?["source"]).isEmpty ? jstr((j["thumbnail"] as? [String: Any])?["source"])
            : jstr((j["originalimage"] as? [String: Any])?["source"])
    }

    // MARK: the exact channel and its versions

    /// One TV channel (e.g. "beIN SPORTS 2") and every copy of it in the subscription (Arabic / English, qualities).
    struct ChannelGroup: Identifiable { var id: String; var name: String; var versions: [MediaItem]; var showing: Bool }

    /// "AR: beIN SPORTS 2 FHD" and "beIN Sports 2 EN HD" are the same channel: "bein sports 2".
    static func channelBase(_ name: String) -> String {
        var t = name.lowercased()
        t = t.replacingOccurrences(of: #"\[[^\]]*\]|\([^)]*\)"#, with: " ", options: .regularExpression)
        t = t.replacingOccurrences(of: #"^[a-z]{2,3}\s*[:|\-]\s*"#, with: "", options: .regularExpression)
        t = t.replacingOccurrences(of: #"(^|[^a-z0-9])(fhd|uhd|4k|hd|sd|hevc|h265|h\.265|raw|backup|vip|plus|\+|ar|arab|arabic|en|eng|english|uk|mena|max)(?=$|[^a-z0-9])"#,
                                    with: " ", options: .regularExpression)
        t = t.replacingOccurrences(of: #"عربي|انجليزي|إنجليزي"#, with: " ", options: .regularExpression)
        t = t.replacingOccurrences(of: #"bein\s*sports?"#, with: "bein sports", options: .regularExpression)
        t = t.replacingOccurrences(of: #"[^a-z0-9؀-ۿ]+"#, with: " ", options: .regularExpression)
        return t.split(separator: " ").joined(separator: " ")
    }

    static func isArabicChannel(_ name: String) -> Bool {
        name.range(of: #"(^|[^a-z])(ar|arab|arabic)([^a-z]|$)|[؀-ۿ]"#, options: [.regularExpression, .caseInsensitive]) != nil
    }
    static func isEnglishChannel(_ name: String) -> Bool {
        name.range(of: #"(^|[^a-z])(en|eng|english|uk)([^a-z]|$)"#, options: [.regularExpression, .caseInsensitive]) != nil
    }

    /// The channels of the match: those whose programme guide shows it first (the exact one, e.g. beIN 2 — not beIN 1),
    /// else the rights holder's channels; each with all its versions in the subscription.
    static func channelGroups(_ m: Match, all: [MediaItem], ranked: [(MediaItem, Int, Bool)]) -> [ChannelGroup] {
        let showing = ranked.filter { $0.2 }.map { $0.0 }
        let base = showing.isEmpty ? ranked.prefix(24).map { $0.0 } : showing
        var order: [String] = []
        var flags: [String: Bool] = [:]
        for ch in base {
            let b = channelBase(ch.name)
            if !order.contains(b) { order.append(b) }
            if showing.contains(where: { $0.id == ch.id }) { flags[b] = true }
        }
        // every copy of those channels in the subscription, Arabic first (in Arabic), best quality first
        func quality(_ n: String) -> Int {
            let t = n.lowercased()
            return t.contains("4k") || t.contains("uhd") ? 0 : t.contains("fhd") ? 1 : t.contains("hd") ? 2 : t.contains("sd") ? 4 : 3
        }
        var groups: [ChannelGroup] = []
        for b in order.prefix(showing.isEmpty ? 6 : 4) {
            let vs = all.filter { channelBase($0.name) == b }.sorted { a, c in
                let la = isArabicChannel(a.name) == L.isArabic ? 0 : 1, lc = isArabicChannel(c.name) == L.isArabic ? 0 : 1
                return (la, quality(a.name)) < (lc, quality(c.name))
            }
            guard !vs.isEmpty else { continue }
            let pretty = vs.first(where: { isEnglishChannel($0.name) })?.name ?? vs[0].name
            groups.append(ChannelGroup(id: b, name: pretty.replacingOccurrences(of: #"^[A-Za-z]{2,3}\s*[:|\-]\s*"#, with: "", options: .regularExpression),
                                       versions: Array(vs.prefix(8)), showing: flags[b] == true))
        }
        return groups
    }
}
