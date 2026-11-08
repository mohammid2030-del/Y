import Foundation
import SwiftUI

struct MatchTeam: Hashable { var id: String; var name: String; var short: String; var logo: String; var score: String; var color: Color? }

struct Match: Identifiable, Hashable {
    enum State: Int { case live = 0, halftime = 1, pre = 2, post = 3, postponed = 4 }
    var id: String
    var slug: String
    var league: String
    var leagueLogo: String
    var priority: Int
    var home: MatchTeam
    var away: MatchTeam
    var start: Date
    var state: State
    var clock: String

    var isLive: Bool { state == .live || state == .halftime }
    /// The channel opens from 30 minutes before kick-off until the final whistle.
    var canOpen: Bool { isLive || (state == .pre && Date() >= start.addingTimeInterval(-30 * 60)) }
}

/// Today's / tomorrow's major matches from ESPN's public scoreboard JSON (no key, unofficial).
enum Matches {
    static let competitions: [(slug: String, en: String, ar: String, p: Int)] = [
        ("uefa.champions", "UEFA Champions League", "دوري أبطال أوروبا", 0), ("ksa.1", "Saudi Pro League", "دوري روشن السعودي", 1),
        ("eng.1", "Premier League", "الدوري الإنجليزي", 2), ("esp.1", "LaLiga", "الدوري الإسباني", 3),
        ("ita.1", "Serie A", "الدوري الإيطالي", 4), ("ger.1", "Bundesliga", "الدوري الألماني", 5),
        ("fra.1", "Ligue 1", "الدوري الفرنسي", 6), ("uefa.europa", "UEFA Europa League", "الدوري الأوروبي", 7),
        ("afc.champions", "AFC Champions League Elite", "دوري أبطال آسيا للنخبة", 8), ("fifa.worldq.afc", "World Cup Qualifiers — Asia", "تصفيات كأس العالم — آسيا", 9)
    ]

    /// Regional (MENA) rights holders, used to rank the user's own channels.
    static let hints: [String: [String]] = [
        "uefa.champions": ["bein", "بي ان"], "uefa.europa": ["bein", "بي ان"], "eng.1": ["bein", "بي ان"],
        "esp.1": ["bein", "بي ان"], "fra.1": ["bein", "بي ان"], "ger.1": ["mbc action", "ام بي سي اكشن", "shahid", "mbc"],
        "ita.1": ["starz", "ad sport", "abu dhabi", "ابوظبي", "shasha"], "ksa.1": ["thmanyah", "ثمانية", "ssc"],
        "afc.champions": ["thmanyah", "ثمانية", "ssc", "alkass"], "fifa.worldq.afc": ["ssc", "ksa sport", "alkass", "bein"]
    ]

    static func network(_ slug: String) -> String {
        switch slug {
        case "uefa.champions", "uefa.europa", "eng.1", "esp.1", "fra.1": return "beIN SPORTS"
        case "ger.1": return L.isArabic ? "MBC أكشن · شاهد" : "MBC Action · Shahid"
        case "ksa.1": return L.isArabic ? "ثمانية" : "Thmanyah"
        default: return ""
        }
    }

    private static func color(_ hex: String) -> Color? {
        guard hex.count == 6, let v = UInt32(hex, radix: 16) else { return nil }
        return Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }

    static func forDay(_ offset: Int) async -> [Match] {
        let cal = Calendar.current
        let start = cal.date(byAdding: .day, value: offset, to: cal.startOfDay(for: Date()))!
        let end = cal.date(byAdding: .day, value: 1, to: start)!
        let f = DateFormatter(); f.dateFormat = "yyyyMMdd"; f.locale = Locale(identifier: "en_US_POSIX")
        let dates = [-1, 0, 1].map { f.string(from: cal.date(byAdding: .day, value: $0, to: start.addingTimeInterval(12 * 3600))!) }
        var all: [Match] = []
        await withTaskGroup(of: [Match].self) { group in
            for c in competitions { for d in dates { group.addTask { await fetch(c.slug, c.en, c.p, d) } } }
            for await list in group { all += list }
        }
        var seen = Set<String>()
        return all.filter { seen.insert($0.id).inserted && $0.start >= start && $0.start < end }
            .sorted { ($0.state.rawValue <= 1 ? 0 : $0.state.rawValue, $0.priority, $0.start) < ($1.state.rawValue <= 1 ? 0 : $1.state.rawValue, $1.priority, $1.start) }
    }

    private static func fetch(_ slug: String, _ name: String, _ p: Int, _ date: String) async -> [Match] {
        guard let url = URL(string: "https://site.api.espn.com/apis/site/v2/sports/soccer/\(slug)/scoreboard?dates=\(date)"),
              let j = try? await Net.json(url) as? [String: Any] else { return [] }
        let lg = (j["leagues"] as? [[String: Any]])?.first
        let lgName = jstr(lg?["name"]).isEmpty ? name : jstr(lg?["name"])
        let lgLogo = jstr(((lg?["logos"] as? [[String: Any]])?.first)?["href"])
        let iso = ISO8601DateFormatter()
        let isoNoSec = DateFormatter(); isoNoSec.dateFormat = "yyyy-MM-dd'T'HH:mm'Z'"; isoNoSec.timeZone = TimeZone(identifier: "UTC")
        isoNoSec.locale = Locale(identifier: "en_US_POSIX")
        return ((j["events"] as? [[String: Any]]) ?? []).compactMap { e -> Match? in
            guard let comp = (e["competitions"] as? [[String: Any]])?.first else { return nil }
            let teams = (comp["competitors"] as? [[String: Any]]) ?? []
            guard let h = teams.first(where: { jstr($0["homeAway"]) == "home" }) ?? teams.first,
                  let a = teams.first(where: { jstr($0["homeAway"]) == "away" }) ?? teams.dropFirst().first else { return nil }
            let st = (comp["status"] ?? e["status"]) as? [String: Any] ?? [:]
            let ty = st["type"] as? [String: Any] ?? [:]
            let nm = jstr(ty["name"])
            let state: Match.State = nm.contains("POSTPONED") || nm.contains("CANCELED") ? .postponed
                : nm.contains("HALFTIME") ? .halftime : jstr(ty["state"]) == "in" ? .live : jstr(ty["state"]) == "post" ? .post : .pre
            func team(_ o: [String: Any]) -> MatchTeam {
                let t = o["team"] as? [String: Any] ?? [:]
                let logo = jstr(t["logo"]).isEmpty ? jstr(((t["logos"] as? [[String: Any]])?.first)?["href"]) : jstr(t["logo"])
                let short = jstr(t["shortDisplayName"]).isEmpty ? jstr(t["displayName"]) : jstr(t["shortDisplayName"])
                return MatchTeam(id: jstr(t["id"]), name: jstr(t["displayName"]), short: short, logo: logo,
                                 score: state == .pre || state == .postponed ? "" : jstr(o["score"]), color: color(jstr(t["color"])))
            }
            let ds = jstr(e["date"])
            guard let date = iso.date(from: ds) ?? isoNoSec.date(from: ds) else { return nil }
            let clock = jstr(st["displayClock"]).isEmpty ? jstr(ty["shortDetail"]) : jstr(st["displayClock"])
            return Match(id: jstr(e["id"]), slug: slug, league: lgName, leagueLogo: lgLogo, priority: p,
                         home: team(h), away: team(a), start: date, state: state, clock: clock)
        }
    }

    /// Ranks the user's channels: regional rights holder, app language, remembered choice.
    static func rank(_ m: Match, channels: [MediaItem], subID: String) -> [MediaItem] {
        let hs = hints[m.slug] ?? []
        let learnedMatch = Storage.string("pick|\(subID)|m|\(m.id)")
        let learnedFamily = Storage.string("pick|\(subID)|\(m.slug)")
        func family(_ n: String) -> String { n.lowercased().replacingOccurrences(of: #"\d+"#, with: "#", options: .regularExpression) }
        func has(_ text: String, _ pattern: String) -> Bool { text.range(of: pattern, options: .regularExpression) != nil }
        var scored: [(MediaItem, Int)] = []
        for ch in channels {
            let text = ch.name.lowercased()
            var score = 0
            var hinted = false
            for (i, h) in hs.enumerated() where text.contains(h) { score = max(score, 60 - i * 8); hinted = true }
            if !hinted && !text.contains("sport") && !text.contains("رياض") { continue }
            let isAr = has(text, #"(^|[^a-z])(ar|arab|arabic)([^a-z]|$)"#) || has(text, #"[؀-ۿ]"#)
            let isEn = has(text, #"(^|[^a-z])(en|eng|english|uk)([^a-z]|$)"#)
            let other = has(text, #"(^|[^a-z])(es|esp|spanish|fr|french|tr|turk|de|ger|it|ita|pt|por|in|hindi|ru|pl|nl|us|usa)([^a-z]|$)"#)
            if L.isArabic { score += isAr ? 40 : isEn ? -20 : other ? -80 : 0 } else { score += isEn ? 40 : isAr ? -10 : other ? -80 : 0 }
            if has(text, #"\b(fhd|uhd|4k)\b"#) { score += 6 } else if has(text, #"\bhd\b"#) { score += 4 }
            if family(ch.name) == learnedFamily { score += 80 }
            if ch.id == learnedMatch { score += 500 }
            if score > 0 { scored.append((ch, score)) }
        }
        return scored.sorted { $0.1 > $1.1 }.prefix(8).map { $0.0 }
    }

    static func isSure(_ m: Match, ranked: [MediaItem], channels: [MediaItem], subID: String) -> Bool {
        if ranked.count == 1 { return true }
        return Storage.string("pick|\(subID)|m|\(m.id)") == ranked.first?.id
    }

    static func remember(_ m: Match, subID: String, channel: MediaItem) {
        Storage.setString("pick|\(subID)|m|\(m.id)", channel.id)
        Storage.setString("pick|\(subID)|\(m.slug)", channel.name.lowercased().replacingOccurrences(of: #"\d+"#, with: "#", options: .regularExpression))
    }
}
