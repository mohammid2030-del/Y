import Foundation

/// A clear, Netflix-style group of provider categories: a platform (Netflix, Shahid…), a region (Turkish, Korean…) or a type (Kids…).
struct Shelf: Identifiable, Hashable {
    let id: String
    let title: String
    let icon: String
    var categories: [Category]
    var count: Int
}

/// Genre filters, backed by TMDB genre ids (movie and TV lists differ).
enum GenreFilter: String, CaseIterable, Identifiable {
    case action, drama, comedy, romance, thriller, horror, crime, scifi, adventure, animation, family, mystery, war, documentary
    var id: String { rawValue }
    var label: String { L.t("g_" + rawValue) }

    func ids(_ kind: MediaKind) -> Set<Int> {
        let tv = kind == .series
        switch self {
        case .action: return tv ? [10759] : [28]
        case .drama: return [18]
        case .comedy: return [35]
        case .romance: return [10749]
        case .thriller: return tv ? [9648, 80] : [53]
        case .horror: return [27]
        case .crime: return [80]
        case .scifi: return tv ? [10765] : [878, 14]
        case .adventure: return tv ? [10759] : [12]
        case .animation: return [16]
        case .family: return tv ? [10751, 10762] : [10751]
        case .mystery: return [9648]
        case .war: return tv ? [10768] : [10752]
        case .documentary: return [99]
        }
    }

    /// Provider categories that already say the genre ("Action 4K", "أفلام رعب") count without asking TMDB.
    func matchesCategory(_ name: String) -> Bool {
        let p: String
        switch self {
        case .action: p = "action|اكشن|أكشن"
        case .drama: p = "drama|دراما"
        case .comedy: p = "comedy|كوميدي|كوميديا"
        case .romance: p = "romance|romantic|رومانس|رومنس|رومانسي"
        case .thriller: p = "thriller|اثارة|إثارة|تشويق"
        case .horror: p = "horror|رعب"
        case .crime: p = "crime|جريمة|جرائم"
        case .scifi: p = "sci-?fi|science|خيال علمي"
        case .adventure: p = "adventure|مغامرة|مغامرات"
        case .animation: p = "animation|cartoon|anime|انمي|أنمي|كرتون|رسوم"
        case .family: p = "family|عائلي|عائلية"
        case .mystery: p = "mystery|غموض"
        case .war: p = "war|حرب|حروب"
        case .documentary: p = "docu|وثائقي"
        }
        return name.range(of: p, options: [.regularExpression, .caseInsensitive]) != nil
    }

    static func available(_ kind: MediaKind) -> [GenreFilter] {
        kind == .series ? [.drama, .comedy, .action, .crime, .thriller, .scifi, .animation, .family, .mystery, .war, .documentary]
                        : [.action, .drama, .comedy, .romance, .thriller, .horror, .crime, .scifi, .adventure, .animation, .family, .war, .documentary]
    }
}

/// Library index built once per catalog, off the main thread.
struct CatalogIndex {
    var shelves: [MediaKind: [Shelf]] = [:]
    var shelfOfCategory: [String: String] = [:]       // "kind|categoryID" → shelf id
    var key: [String: String] = [:]                    // "kind|itemID" → title key
    var variants: [String: [MediaItem]] = [:]          // "kind|titleKey" → every copy of that title, best first
    var quality: [String: Int] = [:]                   // "kind|itemID" → quality score
    var cardKey: [String: String] = [:]                // "kind|itemID" → TMDB lookup key "kind|title|year"
    var titleTmdb: [String: String] = [:]              // "kind|titleKey" → TMDB id a copy of that title carries

    private struct Rule { let id: String; let ar: String; let en: String; let icon: String; let pattern: String }

    /// Order matters: platforms first ("Netflix Arabic" is Netflix), then regions, then types.
    private static let rules: [Rule] = [
        Rule(id: "netflix", ar: "نتفليكس", en: "Netflix", icon: "n.square.fill", pattern: #"netflix|نتفليكس|نتفلكس|(^|[^a-z])nf([^a-z]|$)"#),
        Rule(id: "shahid", ar: "شاهد", en: "Shahid", icon: "play.tv.fill", pattern: #"shahid|شاهد"#),
        Rule(id: "prime", ar: "أمازون برايم", en: "Prime Video", icon: "a.square.fill", pattern: #"amazon|prime|برايم|امازون|أمازون|(^|[^a-z])amz([^a-z]|$)"#),
        Rule(id: "disney", ar: "ديزني+", en: "Disney+", icon: "sparkles", pattern: #"disney|ديزني"#),
        Rule(id: "hbo", ar: "HBO و Max", en: "HBO & Max", icon: "h.square.fill", pattern: #"hbo|اتش بي او|(^|[^a-z])max([^a-z]|$)"#),
        Rule(id: "apple", ar: "Apple TV+", en: "Apple TV+", icon: "applelogo", pattern: #"apple|ابل تي|آبل|(^|[^a-z])atv([^a-z]|$)"#),
        Rule(id: "starz", ar: "ستارز بلاي", en: "STARZPLAY", icon: "star.square.fill", pattern: #"starz|ستارز"#),
        Rule(id: "osn", ar: "OSN+", en: "OSN+", icon: "o.square.fill", pattern: #"(^|[^a-z])osn"#),
        Rule(id: "platforms", ar: "منصات أخرى", en: "More platforms", icon: "square.stack.3d.up.fill",
             pattern: #"paramount|hulu|peacock|crunchyroll|(^|[^a-z])tod([^a-z]|$)|yango|watch ?it|wavo|viu|tvplus|tv\+|jawwy|weyyak|وياك|منصة"#),
        Rule(id: "ramadan", ar: "رمضان", en: "Ramadan", icon: "moon.stars.fill", pattern: #"رمضان|ramadan"#),
        Rule(id: "kids", ar: "أطفال وأنمي", en: "Kids & anime", icon: "figure.and.child.holdinghands",
             pattern: #"kid|child|اطفال|أطفال|cartoon|كرتون|انمي|أنمي|anime|animation|spacetoon|سبيستون|رسوم"#),
        Rule(id: "docs", ar: "وثائقي", en: "Documentaries", icon: "globe.europe.africa.fill", pattern: #"docu|وثائقي"#),
        Rule(id: "turkish", ar: "تركي", en: "Turkish", icon: "moon.fill", pattern: #"turk|ترك"#),
        Rule(id: "asian", ar: "كوري وآسيوي", en: "Korean & Asian", icon: "sun.max.fill",
             pattern: #"korea|كوري|asia|اسيو|آسيو|china|chinese|صين|japan|يابان|thai|تايلند"#),
        Rule(id: "indian", ar: "هندي", en: "Indian", icon: "music.note", pattern: #"india|هند|bollywood|بوليوود"#),
        Rule(id: "arabic", ar: "عربي", en: "Arabic", icon: "character.book.closed.fill",
             pattern: #"arab|عرب|مصر|خليج|سوري|لبنان|سعودي|مغرب|عراق|كويت|اردن|تونس|جزائر|egypt|khaleej|syri"#),
        Rule(id: "foreign", ar: "أجنبي وعالمي", en: "International", icon: "globe",
             pattern: #"english|اجنبي|أجنبي|foreign|hollywood|هوليوود|(^|[^a-z])(en|us|uk)([^a-z]|$)|spanish|اسباني|french|فرنس|europe|اوروب|latino"#)
    ]

    static func shelfID(for name: String) -> String {
        let n = name.lowercased()
        for r in rules where n.range(of: r.pattern, options: .regularExpression) != nil { return r.id }
        // Unlabelled Latin-script categories ("ACTION 4K", "VOD 2025") are international; anything else is "more".
        return n.range(of: #"[\x{0600}-\x{06FF}]"#, options: .regularExpression) == nil ? "foreign" : "other"
    }

    private static func title(_ id: String) -> (String, String) {
        if id == "other" { return (L.isArabic ? "أخرى" : "More", "square.grid.2x2.fill") }
        let r = rules.first { $0.id == id }!
        return (L.isArabic ? r.ar : r.en, r.icon)
    }

    /// "Zmora: PL" / "ZMORA" / "Zmóra" → "zmora".
    static func normalize(_ title: String) -> String {
        var words = title.split(separator: " ").map(String.init)
        // trailing country / language codes some providers append ("Zmora PL", "Dark DE")
        while words.count > 1, let last = words.last, last.count <= 3, last == last.uppercased(), last.allSatisfy({ $0.isLetter && $0.isASCII }) {
            words.removeLast()
        }
        let folded = words.joined(separator: " ").folding(options: [.diacriticInsensitive, .caseInsensitive, .widthInsensitive], locale: nil)
        let out = String(folded.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }.map(Character.init))
        return out.isEmpty ? title.lowercased() : out
    }

    /// Language of a copy, from its name and category: what the version picker shows and sorts by (Arabic first).
    static func language(name: String, category: String) -> (label: String, rank: Int) {
        let n = (name + " " + category).lowercased()
        func has(_ p: String) -> Bool { n.range(of: p, options: .regularExpression) != nil }
        let ar = L.isArabic
        if has("مدبلج|dubbed ?ar|ar ?dub|arabic dub") { return (ar ? "مدبلج عربي" : "Arabic dubbed", 0) }
        if has("مترجم|arsub|ar-sub|ar sub|sub ?ar|arabic sub|subbed") { return (ar ? "مترجم عربي" : "Arabic subtitles", 1) }
        if has("عرب|arab|(^|[^a-z])ar([^a-z]|$)") { return (ar ? "عربي" : "Arabic", 2) }
        if has("english|(^|[^a-z])(en|eng|us|uk)([^a-z]|$)") { return (ar ? "إنجليزي" : "English", 3) }
        if has("españa|espana|spain|spanish|latino|(^|[^a-z])(es|esp)([^a-z]|$)") { return (ar ? "إسباني" : "Spanish", 5) }
        if has("nordic|sweden|swedish|norway|danish|denmark|finland|(^|[^a-z])(se|no|dk|fi)([^a-z]|$)") { return (ar ? "إسكندنافي" : "Nordic", 5) }
        if has("turk|ترك|(^|[^a-z])tr([^a-z]|$)") { return (ar ? "تركي" : "Turkish", 4) }
        if has("french|france|(^|[^a-z])fr([^a-z]|$)") { return (ar ? "فرنسي" : "French", 5) }
        if has("german|deutsch|(^|[^a-z])de([^a-z]|$)") { return (ar ? "ألماني" : "German", 5) }
        if has("poland|polish|(^|[^a-z])pl([^a-z]|$)") { return (ar ? "بولندي" : "Polish", 5) }
        if has("india|hindi|هند") { return (ar ? "هندي" : "Hindi", 5) }
        return ("", 4)
    }

    static func qualityScore(_ name: String) -> Int {
        let n = name.uppercased()
        if n.range(of: #"(^|[^A-Z0-9])(4K|UHD|2160P?)([^A-Z0-9]|$)"#, options: .regularExpression) != nil { return 3 }
        if n.range(of: #"(^|[^A-Z0-9])(FHD|1080P?)([^A-Z0-9]|$)"#, options: .regularExpression) != nil { return 2 }
        if n.range(of: #"(^|[^A-Z0-9])(HD|720P?)([^A-Z0-9]|$)"#, options: .regularExpression) != nil { return 1 }
        return 0
    }

    static func build(_ catalog: Catalog) -> CatalogIndex {
        var idx = CatalogIndex()
        for kind in [MediaKind.movie, .series] {
            let items = catalog.items(kind)
            var counts: [String: Int] = [:]
            for m in items { counts[m.category, default: 0] += 1 }
            var groups: [String: [Category]] = [:]
            for c in catalog.categories(kind) where (counts[c.id] ?? 0) > 0 {
                let sid = shelfID(for: c.name)
                groups[sid, default: []].append(c)
                idx.shelfOfCategory["\(kind.rawValue)|\(c.id)"] = sid
            }
            let order = rules.map(\.id) + ["other"]
            idx.shelves[kind] = order.compactMap { sid -> Shelf? in
                guard let cats = groups[sid], !cats.isEmpty else { return nil }
                let (t, icon) = title(sid)
                let sorted = cats.sorted { (counts[$0.id] ?? 0) > (counts[$1.id] ?? 0) }
                return Shelf(id: sid, title: t, icon: icon, categories: sorted, count: sorted.reduce(0) { $0 + (counts[$1.id] ?? 0) })
            }
            // Same title in several categories / qualities / languages → one card. Titles are compared without
            // punctuation, accents or trailing country codes; different years stay apart (remakes), unknown years join.
            var byTitle: [String: [(MediaItem, String)]] = [:]
            for m in items {
                let c = Clean.full(m.name)
                let year = m.year.count >= 4 && m.year.prefix(4).allSatisfy(\.isNumber) ? String(m.year.prefix(4)) : c.year
                byTitle[normalize(c.title), default: []].append((m, year))
                idx.quality["\(kind.rawValue)|\(m.id)"] = qualityScore(m.name)
                idx.cardKey["\(kind.rawValue)|\(m.id)"] = "\(kind.rawValue)|\(c.title)|\(year)"
            }
            for (t, list) in byTitle {
                var years: [String: Int] = [:]
                for (_, y) in list where !y.isEmpty { years[y, default: 0] += 1 }
                let main = years.max { $0.value < $1.value }?.key ?? ""
                var groups: [String: [MediaItem]] = [:]
                for (m, y) in list {
                    // a year within one of a known year is the same release (providers disagree by a year)
                    let near = years.keys.first { k in Int(k).flatMap { a in Int(y).map { abs(a - $0) <= 1 } } ?? false }
                    let g = y.isEmpty ? main : (near ?? y)
                    groups[g, default: []].append(m)
                }
                for (y, ms) in groups {
                    let k = t + "|" + y
                    for m in ms { idx.key["\(kind.rawValue)|\(m.id)"] = k }
                    if let t = ms.compactMap({ $0.tmdb }).first { idx.titleTmdb["\(kind.rawValue)|\(k)"] = t }
                    idx.variants["\(kind.rawValue)|\(k)"] = ms.sorted {
                        let a = idx.quality["\(kind.rawValue)|\($0.id)"] ?? 0, b = idx.quality["\(kind.rawValue)|\($1.id)"] ?? 0
                        return a != b ? a > b : $0.added > $1.added
                    }
                }
            }
        }
        return idx
    }
}
