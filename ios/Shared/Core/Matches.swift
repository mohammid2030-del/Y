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
    var fav = false

    var isLive: Bool { state == .live || state == .halftime }
    /// The channel opens from 30 minutes before kick-off until the final whistle.
    var canOpen: Bool { isLive || (state == .pre && Date() >= start.addingTimeInterval(-30 * 60)) }
}

struct Competition: Hashable { let slug: String; let en: String; let ar: String; let p: Int; var nat = false
    var label: String { L.isArabic ? ar : en }
}

struct FavTeam: Codable, Hashable, Identifiable { var id: String; var name: String; var logo: String; var nat: Bool; var slug: String }

/// What the user follows: competitions, favourite clubs / national teams, and "only my teams".
struct MatchPrefs: Codable, Hashable {
    var comps: [String] = Matches.defaultComps
    var teams: [FavTeam] = []
    var only = false
}

/// Today's / tomorrow's major matches from ESPN's public scoreboard JSON (no key, unofficial).
enum Matches {
    static let competitions: [Competition] = [
        Competition(slug: "uefa.champions", en: "UEFA Champions League", ar: "دوري أبطال أوروبا", p: 0),
        Competition(slug: "ksa.1", en: "Saudi Pro League", ar: "دوري روشن السعودي", p: 1),
        Competition(slug: "eng.1", en: "Premier League", ar: "الدوري الإنجليزي", p: 2),
        Competition(slug: "esp.1", en: "LaLiga", ar: "الدوري الإسباني", p: 3),
        Competition(slug: "ita.1", en: "Serie A", ar: "الدوري الإيطالي", p: 4),
        Competition(slug: "ger.1", en: "Bundesliga", ar: "الدوري الألماني", p: 5),
        Competition(slug: "fra.1", en: "Ligue 1", ar: "الدوري الفرنسي", p: 6),
        Competition(slug: "uefa.europa", en: "UEFA Europa League", ar: "الدوري الأوروبي", p: 7),
        Competition(slug: "afc.champions", en: "AFC Champions League Elite", ar: "دوري أبطال آسيا للنخبة", p: 8),
        Competition(slug: "uefa.europa.conf", en: "Conference League", ar: "دوري المؤتمر الأوروبي", p: 10),
        Competition(slug: "eng.fa", en: "FA Cup", ar: "كأس الاتحاد الإنجليزي", p: 11),
        Competition(slug: "esp.copa_del_rey", en: "Copa del Rey", ar: "كأس ملك إسبانيا", p: 12),
        Competition(slug: "ned.1", en: "Eredivisie", ar: "الدوري الهولندي", p: 13),
        Competition(slug: "por.1", en: "Liga Portugal", ar: "الدوري البرتغالي", p: 14),
        Competition(slug: "tur.1", en: "Süper Lig", ar: "الدوري التركي", p: 15),
        Competition(slug: "usa.1", en: "MLS", ar: "الدوري الأمريكي", p: 16),
        Competition(slug: "fifa.world", en: "FIFA World Cup", ar: "كأس العالم", p: 0, nat: true),
        Competition(slug: "fifa.worldq.afc", en: "World Cup Qualifiers — Asia", ar: "تصفيات كأس العالم — آسيا", p: 9, nat: true),
        Competition(slug: "afc.asian.cup", en: "AFC Asian Cup", ar: "كأس آسيا", p: 9, nat: true),
        Competition(slug: "uefa.nations", en: "UEFA Nations League", ar: "دوري الأمم الأوروبية", p: 12, nat: true),
        Competition(slug: "fifa.worldq.uefa", en: "World Cup Qualifiers — Europe", ar: "تصفيات كأس العالم — أوروبا", p: 13, nat: true),
        Competition(slug: "caf.nations", en: "Africa Cup of Nations", ar: "كأس أمم أفريقيا", p: 13, nat: true),
        Competition(slug: "fifa.friendly", en: "International Friendlies", ar: "مباريات ودية دولية", p: 20, nat: true)
    ]
    static let defaultComps = ["uefa.champions", "ksa.1", "eng.1", "esp.1", "ita.1", "ger.1", "fra.1", "uefa.europa", "afc.champions", "fifa.worldq.afc"]
    static let continental = ["uefa.champions", "uefa.europa", "uefa.europa.conf", "afc.champions"]

    static func competition(_ slug: String) -> Competition? { competitions.first { $0.slug == slug } }

    /// Regional (MENA) rights holders, used to rank the user's own channels.
    static let hints: [String: [String]] = [
        "uefa.champions": ["bein", "بي ان"], "uefa.europa": ["bein", "بي ان"], "uefa.europa.conf": ["bein", "بي ان"],
        "eng.1": ["bein", "بي ان"], "esp.1": ["bein", "بي ان"], "fra.1": ["bein", "بي ان"],
        "ger.1": ["mbc action", "ام بي سي اكشن", "shahid", "mbc"],
        "ita.1": ["starz", "ad sport", "abu dhabi", "ابوظبي", "shasha"], "ksa.1": ["thmanyah", "ثمانية", "ssc"],
        "afc.champions": ["thmanyah", "ثمانية", "ssc", "alkass", "الكاس"],
        "fifa.worldq.afc": ["ssc", "ksa sport", "السعودية الرياضية", "alkass", "bein"], "fifa.world": ["bein", "بي ان"]
    ]

    static func network(_ slug: String) -> String {
        switch slug {
        case "uefa.champions", "uefa.europa", "uefa.europa.conf", "eng.1", "esp.1", "fra.1": return "beIN SPORTS"
        case "ger.1": return L.isArabic ? "MBC أكشن · شاهد" : "MBC Action · Shahid"
        case "ksa.1": return L.isArabic ? "ثمانية" : "Thmanyah"
        default: return ""
        }
    }

    private static func color(_ hex: String) -> Color? {
        guard hex.count == 6, let v = UInt32(hex, radix: 16) else { return nil }
        return Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }

    static func forDay(_ offset: Int, prefs: MatchPrefs) async -> [Match] {
        let cal = Calendar.current
        let start = cal.date(byAdding: .day, value: offset, to: cal.startOfDay(for: Date()))!
        let end = cal.date(byAdding: .day, value: 1, to: start)!
        let f = DateFormatter(); f.dateFormat = "yyyyMMdd"; f.locale = Locale(identifier: "en_US_POSIX")
        let dates = [-1, 0, 1].map { f.string(from: cal.date(byAdding: .day, value: $0, to: start.addingTimeInterval(12 * 3600))!) }
        var slugs: [String] = []
        func add(_ s: String) { if !slugs.contains(s) { slugs.append(s) } }
        if !prefs.only || prefs.teams.isEmpty { prefs.comps.forEach(add) }
        prefs.teams.forEach { add($0.slug) }
        if prefs.teams.contains(where: { !$0.nat }) { continental.forEach(add) }
        if prefs.teams.contains(where: { $0.nat }) { competitions.filter(\.nat).forEach { add($0.slug) } }
        let favIDs = Set(prefs.teams.map(\.id))
        var all: [Match] = []
        await withTaskGroup(of: [Match].self) { group in
            for slug in slugs {
                let c = competition(slug) ?? Competition(slug: slug, en: slug, ar: slug, p: 30)
                for d in dates { group.addTask { await fetch(c.slug, c.en, c.p, d) } }
            }
            for await list in group { all += list }
        }
        var seen = Set<String>()
        let list = all.filter { seen.insert($0.id).inserted && $0.start >= start && $0.start < end }
            .map { m -> Match in var m = m; m.fav = favIDs.contains(m.home.id) || favIDs.contains(m.away.id); return m }
            .filter { m in prefs.only && !prefs.teams.isEmpty ? m.fav : (m.fav || prefs.comps.contains(m.slug)) }
        func rank(_ m: Match) -> Int { m.isLive ? 0 : m.state == .pre ? 1 : m.state == .post ? 2 : 3 }
        return list.sorted { a, b in
            (rank(a), a.fav ? 0 : 1, a.priority, a.start.timeIntervalSince1970) < (rank(b), b.fav ? 0 : 1, b.priority, b.start.timeIntervalSince1970)
        }
    }

    /// Clubs or national teams of a competition, for the favourites picker.
    static func teams(_ slug: String) async -> [FavTeam] {
        guard let url = URL(string: "https://site.api.espn.com/apis/site/v2/sports/soccer/\(slug)/teams"),
              let j = try? await Net.json(url) as? [String: Any] else { return [] }
        let nat = competition(slug)?.nat ?? false
        let lg = ((j["sports"] as? [[String: Any]])?.first?["leagues"] as? [[String: Any]])?.first
        return ((lg?["teams"] as? [[String: Any]]) ?? []).compactMap { x -> FavTeam? in
            guard let t = x["team"] as? [String: Any], !jstr(t["id"]).isEmpty else { return nil }
            return FavTeam(id: jstr(t["id"]), name: jstr(t["displayName"]),
                           logo: jstr(((t["logos"] as? [[String: Any]])?.first)?["href"]), nat: nat, slug: slug)
        }.sorted { $0.name < $1.name }
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

    // MARK: channel choice

    /// A channel is a candidate only if it is a sports channel: "beIN BOX OFFICE" or "MBC 2" are never a match channel.
    private static let sportsWords = #"sport|سبورت|سبورتس|رياض|ssc|thmanyah|ثمانية|alkass|al kass|الكاس|action|اكشن|أكشن"#
    private static let notSports = #"box ?office|movie|cinema|سينما|افلام|أفلام|film|drama|دراما|kids|اطفال|أطفال|junior|jeem|news|اخبار|أخبار|series|مسلسل|documentar|وثائقي|gourmet|nat ?geo|discovery|music|موسيقى|quran|قرآن|comedy|كوميدي"#

    private static func has(_ text: String, _ pattern: String) -> Bool { text.range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil }
    private static func family(_ n: String) -> String { n.lowercased().replacingOccurrences(of: #"\d+"#, with: "#", options: .regularExpression) }

    static func isSportsChannel(_ name: String) -> Bool { has(name, sportsWords) && !has(name, notSports) }

    /// Ranks the user's channels: sports only, regional rights holder, app language, quality, remembered choice.
    static func rank(_ m: Match, channels: [MediaItem], subID: String) -> [(MediaItem, Int)] {
        let hs = hints[m.slug] ?? []
        let learnedMatch = Storage.string("pick|\(subID)|m|\(m.id)")
        let learnedFamily = Storage.string("pick|\(subID)|\(m.slug)")
        var scored: [(MediaItem, Int)] = []
        for ch in channels {
            let text = ch.name.lowercased()
            guard isSportsChannel(text) else { continue }
            var score = 10
            for (i, h) in hs.enumerated() where text.contains(h) { score = max(score, 70 - i * 8) }
            let isAr = has(text, #"(^|[^a-z])(ar|arab|arabic)([^a-z]|$)"#) || has(text, #"[؀-ۿ]"#)
            let isEn = has(text, #"(^|[^a-z])(en|eng|english|uk)([^a-z]|$)"#)
            let other = has(text, #"(^|[^a-z])(es|esp|spain|spanish|fr|french|tr|turk|de|ger|it|ita|pt|por|in|hindi|ru|pl|nl|us|usa)([^a-z]|$)"#)
            if L.isArabic { score += isAr ? 40 : isEn ? -20 : other ? -80 : 0 } else { score += isEn ? 40 : isAr ? -10 : other ? -80 : 0 }
            if has(text, #"\b(fhd|uhd|4k)\b"#) { score += 6 } else if has(text, #"\bhd\b"#) { score += 4 } else if has(text, #"\bsd\b"#) { score -= 6 }
            if family(ch.name) == learnedFamily { score += 80 }
            if ch.id == learnedMatch { score += 500 }
            if score > 0 { scored.append((ch, score)) }
        }
        return scored.sorted { $0.1 > $1.1 }.prefix(12).map { $0 }
    }

    /// Arabic spellings for clubs that EPGs in Arabic use.
    private static let arabicNames: [String: [String]] = [
        "real madrid": ["ريال مدريد"], "barcelona": ["برشلونة"], "atletico": ["أتلتيكو", "اتلتيكو"], "sevilla": ["إشبيلية", "اشبيلية"],
        "real sociedad": ["ريال سوسيداد"], "villarreal": ["فياريال"], "betis": ["بيتيس"], "rayo": ["رايو"], "valencia": ["فالنسيا"],
        "manchester united": ["مانشستر يونايتد"], "man utd": ["مانشستر يونايتد"], "manchester city": ["مانشستر سيتي"], "man city": ["مانشستر سيتي"],
        "liverpool": ["ليفربول"], "arsenal": ["أرسنال", "آرسنال", "ارسنال"], "chelsea": ["تشيلسي"], "tottenham": ["توتنهام"],
        "aston villa": ["أستون فيلا", "استون فيلا"], "newcastle": ["نيوكاسل"], "west ham": ["وست هام"], "everton": ["إيفرتون", "ايفرتون"],
        "bayern": ["بايرن"], "dortmund": ["دورتموند"], "leverkusen": ["ليفركوزن"], "leipzig": ["لايبزيغ"], "freiburg": ["فرايبورغ"],
        "juventus": ["يوفنتوس"], "inter": ["إنتر", "انتر"], "milan": ["ميلان"], "napoli": ["نابولي"], "roma": ["روما"], "lazio": ["لاتسيو"],
        "paris saint-germain": ["باريس"], "psg": ["باريس"], "marseille": ["مارسيليا"], "monaco": ["موناكو"], "lyon": ["ليون"],
        "al hilal": ["الهلال"], "al-hilal": ["الهلال"], "al nassr": ["النصر"], "al-nassr": ["النصر"], "al ittihad": ["الاتحاد"], "al-ittihad": ["الاتحاد"],
        "al ahli": ["الأهلي", "الاهلي"], "al-ahli": ["الأهلي", "الاهلي"], "al shabab": ["الشباب"], "al-shabab": ["الشباب"],
        "al qadsiah": ["القادسية"], "al-qadsiah": ["القادسية"], "al ettifaq": ["الاتفاق"], "al-ettifaq": ["الاتفاق"], "al taawoun": ["التعاون"],
        "saudi arabia": ["السعودية"], "qatar": ["قطر"], "uae": ["الإمارات", "الامارات"], "egypt": ["مصر"], "morocco": ["المغرب"]
    ]

    private static func tokens(_ t: MatchTeam) -> [String] {
        var out: [String] = []
        let names = [t.name.lowercased(), t.short.lowercased()]
        for n in names where !n.isEmpty {
            out.append(n)
            for (k, v) in arabicNames where n.contains(k) { out += v }
        }
        // distinctive single words ("Sociedad", "Villa", "Hilal"), not "FC" / "United"
        let generic: Set<String> = ["fc", "cf", "club", "united", "city", "real", "al", "de", "sc", "ac", "afc", "sporting"]
        for n in names { for w in n.split(whereSeparator: { !$0.isLetter }) where w.count >= 5 && !generic.contains(String(w)) { out.append(String(w)) } }
        return Array(Set(out))
    }

    /// Looks at the channels' own programme guide: the channel actually showing this match wins.
    static func epgBoost(_ m: Match, candidates: [(MediaItem, Int)], xtream: Xtream) async -> [(MediaItem, Int, Bool)] {
        let home = tokens(m.home), away = tokens(m.away)
        let t0 = Int(m.start.timeIntervalSince1970)
        var hits: [String: Bool] = [:]
        await withTaskGroup(of: (String, Bool).self) { group in
            for (ch, _) in candidates {
                group.addTask {
                    let progs = await withTaskGroup(of: [(start: Int, stop: Int, text: String)]?.self) { g -> [(start: Int, stop: Int, text: String)] in
                        g.addTask { await xtream.shortEPG(ch.id) }
                        g.addTask { try? await Task.sleep(nanoseconds: 5_000_000_000); return nil }
                        let first = await g.next() ?? nil
                        g.cancelAll()
                        return first ?? []
                    }
                    let hit = progs.contains { p in
                        let near = p.start == 0 || (p.start <= t0 + 2 * 3600 && (p.stop == 0 || p.stop >= t0 - 30 * 60))
                        let txt = p.text.lowercased()
                        return near && (home.contains { txt.contains($0) } || away.contains { txt.contains($0) })
                    }
                    return (ch.id, hit)
                }
            }
            for await (id, hit) in group { hits[id] = hit }
        }
        return candidates.map { ($0.0, $0.1 + (hits[$0.0.id] == true ? 400 : 0), hits[$0.0.id] == true) }.sorted { $0.1 > $1.1 }
    }

    static func remember(_ m: Match, subID: String, channel: MediaItem) {
        Storage.setString("pick|\(subID)|m|\(m.id)", channel.id)
        Storage.setString("pick|\(subID)|\(m.slug)", family(channel.name))
    }
}
