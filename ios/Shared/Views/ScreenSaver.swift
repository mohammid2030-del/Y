import SwiftUI
import UIKit

// MARK: - Remote / touch activity

/// Notes every press on the Siri Remote and every touch, without taking them from the app.
/// Used for the screensaver (starts after a minute without any input).
final class ActivityMonitor: NSObject, UIGestureRecognizerDelegate {
    static let shared = ActivityMonitor()
    private(set) var lastActivity = Date()
    /// Called on any input while the screensaver is up (it closes and that input does nothing else).
    var onActivity: (() -> Void)?
    private var installed = Set<ObjectIdentifier>()

    func touch() { lastActivity = Date() }
    var idle: TimeInterval { Date().timeIntervalSince(lastActivity) }

    /// Attaches the watcher to every window of the app (called periodically: windows come and go).
    func install() {
        #if os(iOS)
        // iPad: touches are noted as the window hands them out — no gesture recognizer on the window, which could
        // keep taps from reaching the player (a tap on the video must always bring back its controls)
        _ = UIWindow.novaWatchTouches
        return
        #else
        for scene in UIApplication.shared.connectedScenes {
            guard let ws = scene as? UIWindowScene else { continue }
            for w in ws.windows where !installed.contains(ObjectIdentifier(w)) {
                let g = Watcher(target: nil, action: nil)
                g.monitor = self
                g.cancelsTouchesInView = false
                g.delaysTouchesBegan = false
                g.delaysTouchesEnded = false
                g.delegate = self
                w.addGestureRecognizer(g)
                installed.insert(ObjectIdentifier(w))
            }
        }
        #endif
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

    func noted() {
        touch()
        onActivity?()
    }

    /// Never recognises anything: it only watches presses and touches go by.
    private final class Watcher: UIGestureRecognizer {
        weak var monitor: ActivityMonitor?
        override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent) {
            monitor?.noted(); state = .failed
        }
        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
            monitor?.noted(); state = .failed
        }
    }
}

#if os(iOS)
extension UIWindow {
    /// Swaps in `nova_sendEvent` once: every touch still goes exactly where it went before.
    static let novaWatchTouches: Void = {
        guard let a = class_getInstanceMethod(UIWindow.self, #selector(UIWindow.sendEvent(_:))),
              let b = class_getInstanceMethod(UIWindow.self, #selector(UIWindow.nova_sendEvent(_:))) else { return }
        method_exchangeImplementations(a, b)
    }()

    @objc func nova_sendEvent(_ event: UIEvent) {
        if event.type == .touches, event.allTouches?.contains(where: { $0.phase == .began }) == true { ActivityMonitor.shared.noted() }
        nova_sendEvent(event)   // the original sendEvent (implementations are swapped)
    }
}
#endif

// MARK: - Trailers

/// Opens a trailer in the YouTube app (Apple TV and iPad); when TMDB has no trailer — common for
/// Arabic and Turkish titles — opens a YouTube search for "<title> <year> إعلان / trailer".
enum TrailerLauncher {
    struct Query { let q: String; let names: [String]; var season = 0; var lang = ""; var series = false }

    /// What to search on YouTube, in the work's original language (Turkish series → "<Turkish name> fragman"),
    /// and the names the video's title must contain (never an unrelated clip).
    static func query(_ tm: TMDBInfo?, _ item: MediaItem, series: Bool, season: Int = 0) -> Query {
        let name = (tm.map { $0.original.isEmpty ? $0.title : $0.original }) ?? Clean.title(item.name)
        let year = tm?.year ?? String(item.year.prefix(4))
        let arabic = name.range(of: "[\\u0600-\\u06FF]", options: .regularExpression) != nil
        let lang = (tm?.originalLanguage ?? "").isEmpty ? (arabic ? "ar" : "") : tm!.originalLanguage
        let q: String
        if lang == "tr" {
            q = season > 0 ? "\(name) \(season). sezon fragman" : "\(name)\(series ? " 1. bölüm fragmanı" : " fragman")"
        } else if lang == "ar" || arabic {
            // "اعلان" alone also means a TV commercial — say what it is
            q = season > 0 ? "مسلسل \(name) الموسم \(season) برومو" : series ? "مسلسل \(name) برومو" : "فيلم \(name) \(year) اعلان رسمي"
        } else {
            q = season > 0 ? "\(name) season \(season) official trailer" : "\(name) \(year)\(series ? " series" : "") official trailer"
        }
        let names = [name, tm?.title ?? "", Clean.title(item.name)].filter { !$0.isEmpty }
        return Query(q: q.split(separator: " ").joined(separator: " "), names: names, season: season, lang: lang, series: series)
    }

    /// Comparison text: lower case, no accents (ö → o, ş → s, ı → i), no Arabic diacritics / letter variants, no punctuation.
    static func norm(_ s: String) -> String {
        var t = s.lowercased().replacingOccurrences(of: "ı", with: "i")
        t = t.decomposedStringWithCanonicalMapping
        t = t.replacingOccurrences(of: "[\\u0300-\\u036f\\u064B-\\u065F\\u0670\\u0640]", with: "", options: .regularExpression)
        t = t.replacingOccurrences(of: "[أإآٱ]", with: "ا", options: .regularExpression)
            .replacingOccurrences(of: "ة", with: "ه").replacingOccurrences(of: "ى", with: "ي")
        t = t.precomposedStringWithCanonicalMapping
        t = t.replacingOccurrences(of: "[^\\p{L}\\p{N}]+", with: " ", options: .regularExpression)
        return t.split(separator: " ").joined(separator: " ")
    }

    private static func has(_ t: String, _ pattern: String) -> Bool { t.range(of: pattern, options: .regularExpression) != nil }

    /// Episode a video previews ("8. Bölüm 2. Fragman", "Episode 8", "الحلقة 8" → 8), 0 when it is not about one episode.
    static func episodeOf(_ title: String) -> Int {
        let t = norm(title)
        if has(t, "الحلقه الاخيره|final episode|final bolum|finali|son bolum") { return 99 }
        let pattern = "s\\d+ ?e(\\d+)|(\\d+) bolum|bolum (\\d+)|episode (\\d+)|(?:^| )ep ?(\\d+)|(?:^| )e(\\d+)(?: |$)|(?:الحلقه|حلقه) ([0-9\\u0660-\\u0669]+)|(\\d+) (?:الحلقه|حلقه)"
        if let re = try? NSRegularExpression(pattern: pattern), let m = re.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) {
            for i in 1..<m.numberOfRanges {
                guard let r = Range(m.range(at: i), in: t) else { continue }
                let digits = String(t[r].map { c -> Character in
                    if let v = c.unicodeScalars.first?.value, (0x660...0x669).contains(v) { return Character(String(v - 0x660)) }
                    return c
                })
                return Int(digits) ?? 0
            }
        }
        let ord = ["الاول": 1, "الاولي": 1, "الثاني": 2, "الثانيه": 2, "الثالث": 3, "الثالثه": 3, "الرابع": 4, "الرابعه": 4, "الخامس": 5, "الخامسه": 5,
                   "السادس": 6, "السادسه": 6, "السابع": 7, "السابعه": 7, "الثامن": 8, "الثامنه": 8, "التاسع": 9, "التاسعه": 9, "العاشر": 10, "العاشره": 10]
        let words = t.split(separator: " ").map(String.init)
        for (i, w) in words.enumerated() where (w == "الحلقه" || w == "حلقه") && i + 1 < words.count {
            if let n = ord[words[i + 1]] { return n }
        }
        return 0
    }

    /// Does a video title name season n ("Season 2", "S02", "الموسم الثاني", "الجزء 2", "2. Sezon")?
    /// Season 1 is often just "the trailer": fine as long as no other season is named.
    static func namesSeason(_ title: String, _ n: Int) -> Bool {
        let t = norm(title)
        let ar = ["", "الاول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر"]
        func says(_ k: Int) -> Bool {
            let east = Array("٠١٢٣٤٥٦٧٨٩")
            let digits = String(String(k).compactMap { $0.wholeNumberValue.map { east[$0] } })
            var num = "(?:0?\(k)|\(digits)"
            if k < ar.count { num += "|" + ar[k] }
            num += ")"
            return has(t, "(?:season|sezon|الموسم|موسم|الجزء|جزء)\\s*\(num)(?![0-9])|(?:^|\\s)s0?\(k)(?![0-9])|(?:^|\\s)\(k)\\s*sezon")
        }
        if says(n) { return true }
        return n == 1 && !(2...30).contains { says($0) }
    }

    // MARK: official-source checks

    private static let trailerWords = "trailer|teaser|promo|official|اعلان|برومو|تريلر|fragman|tanitim|bande annonce|trailer oficial"
    private static let musicWords = "اغنيه|كليب|music video|lyrics|official video|official audio|soundtrack|jenerik|dizi muzig|muzigi|(?:^| )ost(?: |$)|(?:^| )mv(?: |$)|sarki"
    private static let unofficialWords = "fan ?made|(?:^| )fan(?: |$)|concept|reaction|tepki|parod|(?:^| )edit(?: |$)|review|recap|explained|breakdown|ozet|inceleme|مراجعه|تحليل|ملخص|ردة فعل|رده فعل|حالات|ستوري|shorts|(?:^| )short(?: |$)|status|tiktok|ai trailer|fake|اعلان مزيف|(?:^| )kamera arkasi|behind the scenes|kolaj"
    private static let dubbedWords = "dublaj|dubbed|(?:^| )dub(?: |$)|doblad|doblaje|مدبلج|بالعربي|باللهجه|النسخه العربيه|arabic version|hindi|urdu|turkce dublaj"
    private static let subbedWords = "مترجم|altyazili|subtitled|translated|traduit|legendado"
    private static let strongWords = "official trailer|resmi fragman|tanitim|الاعلان الرسمي|اعلان رسمي|الاعلان الاول|(?:^| )1 bolum fragman|ilk fragman|teaser trailer|ilk tanitim"
    private static let officialChannels = "(?:^| )(?:atv|show tv|showtv|kanal d|star tv|fox|now|now tv|trt ?1|trt|tv ?8|beyaz tv|kanal 7|netflix|disney|prime video|amazon|hbo|max|apple tv|paramount|warner|universal|sony pictures|lionsgate|marvel|a24|20th century|focus features|neon|mubi|ay yapim|ox yapim|tims|mf yapim|gold film|blu ?tv|exxen|gain|tabii|mbc|shahid|osn|rotana|watch it|yango|tod|شاهد|روتانا|ام بي سي)(?: |$)"

    /// What YouTube says about a video: title, channel, length, audio language and whether it plays here.
    struct Fact: Codable { var t = "", c = "", d = 0, a = "", ok = false }

    /// Video facts and search answers, kept in a cache file (Apple TV settings must stay small).
    private actor Memory {
        var facts: [String: Fact] = Storage.loadFile("ytFacts1", as: [String: Fact].self) ?? [:]
        var answers: [String: String] = Storage.loadFile("ytTrailers4", as: [String: String].self) ?? [:]
        func fact(_ id: String) -> Fact? { facts[id] }
        func setFacts(_ f: [String: Fact]) { facts.merge(f) { _, b in b }; Storage.saveFile("ytFacts1", facts) }
        func answer(_ k: String) -> String? { answers[k] }
        func setAnswer(_ k: String, _ v: String) { answers[k] = v; Storage.saveFile("ytTrailers4", answers) }
    }
    private static let memory = Memory()

    private static func seconds(_ iso: String) -> Int {
        guard let re = try? NSRegularExpression(pattern: "P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?"),
              let m = re.firstMatch(in: iso, range: NSRange(iso.startIndex..., in: iso)) else { return 0 }
        func n(_ i: Int) -> Int { Range(m.range(at: i), in: iso).flatMap { Int(iso[$0]) } ?? 0 }
        return n(1) * 86400 + n(2) * 3600 + n(3) * 60 + n(4)
    }

    /// Facts for videos (one API unit for up to 50 ids, remembered).
    static func facts(_ ids: [String]) async -> [String: Fact] {
        var out: [String: Fact] = [:], ask: [String] = []
        for id in ids { if let f = await memory.fact(id) { out[id] = f } else { ask.append(id) } }
        let apiKey = CloudConfig.youtubeKey
        guard !ask.isEmpty, !apiKey.isEmpty else { return out }
        var c = URLComponents(string: "https://www.googleapis.com/youtube/v3/videos")!
        c.queryItems = [.init(name: "part", value: "snippet,status,contentDetails"), .init(name: "id", value: ask.prefix(50).joined(separator: ",")),
                        .init(name: "key", value: apiKey)]
        guard let url = c.url, let (data, resp) = try? await URLSession.shared.data(from: url),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return out }   // can't tell now
        let cc = (Locale.current.regionCode ?? "SA").uppercased()
        var got: [String: Fact] = [:]
        for it in (o["items"] as? [[String: Any]]) ?? [] {
            let id = jstr(it["id"])
            let st = it["status"] as? [String: Any] ?? [:], sn = it["snippet"] as? [String: Any] ?? [:]
            let cd = it["contentDetails"] as? [String: Any] ?? [:]
            let rr = cd["regionRestriction"] as? [String: Any] ?? [:]
            let blocked = ((rr["blocked"] as? [String])?.contains(cc) ?? false) || ((rr["allowed"] as? [String]).map { !$0.contains(cc) } ?? false)
            got[id] = Fact(t: jstr(sn["title"]), c: jstr(sn["channelTitle"]), d: seconds(jstr(cd["duration"])),
                           a: String(jstr(sn["defaultAudioLanguage"]).prefix(2)).lowercased(),
                           ok: jstr(st["privacyStatus"]) != "private" && (st["embeddable"] as? Bool ?? true) && !blocked && jstr(st["uploadStatus"]) != "rejected")
        }
        for id in ask.prefix(50) where got[id] == nil { got[id] = Fact() }   // deleted / private
        await memory.setFacts(got)
        return out.merging(got) { a, _ in a }
    }

    /// An official-looking trailer of the work, in its original language, that plays here?
    static func acceptable(_ f: Fact?, _ q: Query, needName: Bool) -> Bool {
        guard let f, f.ok else { return false }
        if f.d > 0 && (f.d < 45 || f.d > 600) { return false }   // Shorts / full episodes
        let t = norm(f.t), ch = norm(f.c)
        if has(t, musicWords) || has(t, unofficialWords) || has(ch, unofficialWords) || has(t, dubbedWords) { return false }
        if episodeOf(f.t) > 1 { return false }
        if q.season > 0 && !namesSeason(f.t, q.season) { return false }
        if !q.lang.isEmpty && !f.a.isEmpty && f.a != q.lang { return false }   // audio in another language than the original
        if needName {
            let wanted = q.names.map(norm).filter { $0.count >= 2 }
            if !wanted.contains(where: { t.contains($0) }) || !has(t, trailerWords) { return false }
        }
        return true
    }

    /// Higher = more official: the series' own channel, the broadcaster / studio, the original language.
    static func score(_ f: Fact, _ q: Query) -> Int {
        let t = norm(f.t), ch = norm(f.c)
        var s = 0
        if q.names.map(norm).filter({ $0.count >= 3 }).contains(where: { ch.contains($0) }) { s += 4 }
        if has(ch, officialChannels) { s += 3 }
        if has(ch, "official|resmi|رسمي") { s += 1 }
        if has(t, strongWords) { s += 2 }
        if q.lang == "tr" && has(t, "fragman|tanitim") { s += 1 }
        if q.lang == "ar" && has(t, "[\\u0600-\\u06FF]") { s += 1 }
        if !q.lang.isEmpty && q.lang != "ar" && q.lang != "tr" && has(t, "trailer|teaser") { s += 1 }
        if has(t, subbedWords) && !has(ch, officialChannels) { s -= 3 }   // re-uploads with subtitles
        return s
    }

    /// The best official trailer: one of TMDB's [keys] that passes the checks, else a YouTube search. "" when none.
    static func pick(keys: [String], query q: Query) async -> String {
        let keys = keys.map { $0.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" } }.filter { !$0.isEmpty }
        let apiKey = CloudConfig.youtubeKey
        guard !apiKey.isEmpty else { return keys.first ?? "" }
        let fk = await facts(keys)
        let good = keys.filter { acceptable(fk[$0], q, needName: false) }
        if let best = good.enumerated().max(by: { (score(fk[$0.1]!, q), -$0.0) < (score(fk[$1.1]!, q), -$1.0) })?.1 { return best }
        guard !q.q.isEmpty else { return "" }
        let cacheKey = q.q.lowercased() + "|" + q.lang
        if let hit = await memory.answer(cacheKey) { return hit }
        var c = URLComponents(string: "https://www.googleapis.com/youtube/v3/search")!
        var items: [URLQueryItem] = [.init(name: "part", value: "snippet"), .init(name: "type", value: "video"), .init(name: "maxResults", value: "15"),
                                     .init(name: "videoEmbeddable", value: "true"), .init(name: "safeSearch", value: "moderate"),
                                     .init(name: "q", value: q.q), .init(name: "key", value: apiKey)]
        if !q.lang.isEmpty { items.append(.init(name: "relevanceLanguage", value: q.lang)) }
        c.queryItems = items
        guard let url = c.url, let (data, resp) = try? await URLSession.shared.data(from: url),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }   // quota / offline: try another time
        let ids = ((o["items"] as? [[String: Any]]) ?? []).compactMap { ($0["id"] as? [String: Any])?["videoId"] as? String }
        let f = await facts(ids)
        let ok = ids.filter { acceptable(f[$0], q, needName: true) }
        // most official first; YouTube's order among equals
        let id = ok.enumerated().max(by: { (score(f[$0.1]!, q), -$0.0) < (score(f[$1.1]!, q), -$1.0) })?.1 ?? ""
        await memory.setAnswer(cacheKey, id)
        return id
    }

    /// The title's (or season's) official trailer. iPad plays it inside the app (`play`); Apple TV has no web view,
    /// so it opens the video in the YouTube app. Without a real trailer: says so.
    @MainActor static func open(tm: TMDBInfo?, item: MediaItem, series: Bool, season: Int = 0, play: @escaping (String?) -> Void) {
        Task { @MainActor in
            let q = query(tm, item, series: series, season: season)
            var keys: [String] = []
            if let tm, tm.id > 0 { keys = await TMDB.shared.trailers(tv: series, id: tm.id, original: tm.originalLanguage, season: season) }
            if season == 0, let more = tm?.trailers { keys += more.filter { !keys.contains($0) } }
            let k = await pick(keys: keys, query: q)
            #if os(iOS)
            if !k.isEmpty { play(k); return }
            #endif
            guard !k.isEmpty else { play(nil); return }
            #if os(tvOS)
            // Apple TV's YouTube app plays a video only with youtube://watch/<id> (other forms just open its home page).
            let urls: [URL] = ["youtube://watch/\(k)", "youtube://www.youtube.com/watch?v=\(k)"].compactMap(URL.init)
            #else
            let urls: [URL] = ["youtube://watch?v=\(k)", "youtube://www.youtube.com/watch?v=\(k)", "https://www.youtube.com/watch?v=\(k)"].compactMap(URL.init)
            #endif
            func attempt(_ i: Int) {
                guard i < urls.count else { play(nil); return }
                UIApplication.shared.open(urls[i], options: [:]) { ok in if !ok { attempt(i + 1) } }
            }
            attempt(0)
        }
    }
}

// MARK: - Screensaver

/// After a minute without the remote (nothing playing) the app shows the library's movies and series
/// full-screen from TMDB — backdrop, logo / title, type, year, genres, rating and story — a new one every 10 s.
/// In Arabic only titles TMDB has in Arabic are shown (title + story, Arabic logo or the Arabic title).
@MainActor
final class ScreenSaver: ObservableObject {
    struct Slide: Identifiable, Equatable {
        let id = UUID()
        let kind: MediaKind
        let info: TMDBInfo
        let backdrop: UIImage
        let logo: UIImage?
        static func == (a: Slide, b: Slide) -> Bool { a.id == b.id }
    }

    @Published var active = false
    @Published var slide: Slide?
    private weak var state: AppState?
    private var loop: Task<Void, Never>?
    private var recent: [String] = []
    static let idleLimit: TimeInterval = 60, slideTime: UInt64 = 10_000_000_000

    func attach(_ state: AppState) {
        self.state = state
        ActivityMonitor.shared.onActivity = { [weak self] in
            Task { @MainActor in if self?.active == true { self?.stop() } }
        }
        guard loop == nil else { return }
        loop = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                ActivityMonitor.shared.install()
                await self?.check()
            }
        }
    }

    private func canStart() -> Bool {
        guard let s = state, !active, s.tmdbOn, s.playing == nil, case .main = s.phase else { return false }
        return ActivityMonitor.shared.idle >= Self.idleLimit
    }

    private func check() async {
        guard canStart(), let first = await prepare(), canStart() else { return }
        slide = first
        withAnimation(.easeInOut(duration: 1.0)) { active = true }
        cycle()
    }

    private var cycleTask: Task<Void, Never>?
    private func cycle() {
        cycleTask?.cancel()
        cycleTask = Task { [weak self] in
            var next: Slide? = await self?.prepare()
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.slideTime)
                guard let self, self.active, !Task.isCancelled else { return }
                if let n = next { withAnimation(.easeInOut(duration: 1.6)) { self.slide = n } }
                next = await self.prepare()
            }
        }
    }

    func stop() {
        cycleTask?.cancel()
        ActivityMonitor.shared.touch()
        withAnimation(.easeOut(duration: 0.35)) { active = false }
    }

    /// Finds a title with full TMDB artwork + story (tries a few).
    private func prepare() async -> Slide? {
        guard let s = state else { return nil }
        let arabic = L.isArabic
        for _ in 0..<16 {
            let kind: MediaKind = Bool.random() ? .movie : .series
            let all = s.catalog.items(kind)
            guard !all.isEmpty else { continue }
            // half the time from the newest additions, otherwise anything — varied
            let pool = Bool.random() ? Array(all.sorted { $0.added > $1.added }.prefix(300)) : all
            guard let item = pool.randomElement(), !recent.contains(kind.rawValue + item.id) else { continue }
            guard let tm = await TMDB.shared.details(kind: kind, tmdbID: item.tmdb ?? "", name: item.name, year: item.year),
                  !tm.backdrop.isEmpty, !tm.overview.isEmpty else { continue }
            if arabic {
                let ar = { (t: String) in t.range(of: "[\\u0600-\\u06FF]", options: .regularExpression) != nil }
                guard tm.localText, ar(tm.overview), ar(tm.title) else { continue }
            }
            guard let bd = await ImagePipeline.shared.load(tm.backdrop, maxPixel: 1920) else { continue }
            let logoURL = arabic ? tm.logoLocal : tm.logo
            let logo = logoURL.isEmpty ? nil : await ImagePipeline.shared.load(logoURL, maxPixel: 900)
            recent.append(kind.rawValue + item.id); if recent.count > 40 { recent.removeFirst() }
            return Slide(kind: kind, info: tm, backdrop: bd, logo: logo)
        }
        return nil
    }
}

struct ScreenSaverView: View {
    @ObservedObject var saver: ScreenSaver
    @State private var zoom = false
    @State private var now = Date()
    private let clock = Timer.publish(every: 15, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Color.black.ignoresSafeArea()
            if let s = saver.slide {
                Image(uiImage: s.backdrop).resizable().aspectRatio(contentMode: .fill)
                    .scaleEffect(zoom ? 1.08 : 1.0)
                    .frame(maxWidth: .infinity, maxHeight: .infinity).clipped().ignoresSafeArea()
                    .id(s.id).transition(.opacity)
                    .onAppear { zoom = false; withAnimation(.easeOut(duration: 12)) { zoom = true } }
                LinearGradient(colors: [.black.opacity(0.92), .black.opacity(0.55), .clear], startPoint: .bottom, endPoint: .center).ignoresSafeArea()
                LinearGradient(colors: [.black.opacity(0.35), .clear], startPoint: .leading, endPoint: .trailing).ignoresSafeArea()
                info(s).id("i" + s.id.uuidString).transition(.opacity.combined(with: .move(edge: .bottom)))
            }
            VStack {
                HStack {
                    Spacer()
                    Text(now, format: .dateTime.hour().minute()).font(.system(size: Theme.isTV ? 52 : 34, weight: .light)).monospacedDigit()
                        .foregroundStyle(.white.opacity(0.9)).shadow(radius: 10)
                }
                Spacer()
            }.padding(Theme.isTV ? 70 : 28)
        }
        .onReceive(clock) { now = $0 }
        .onTapGesture { saver.stop() }   // iPad: a tap closes it
        #if os(tvOS)
        .onExitCommand { saver.stop() }
        .onPlayPauseCommand { saver.stop() }
        #endif
        #if os(iOS)
        .statusBarHidden()
        #endif
    }

    private func info(_ s: ScreenSaver.Slide) -> some View {
        let tm = s.info
        let meta = [tm.year, tm.genres.prefix(3).joined(separator: " · "), s.kind == .series && tm.seasons > 0 ? String(format: L.t("seasonsCount"), tm.seasons) : ""]
            .filter { !$0.isEmpty }.joined(separator: "   •   ")
        return VStack(alignment: .leading, spacing: 14) {
            Text(L.t(s.kind == .movie ? "movie" : "seriesOne"))
                .font(.system(size: Theme.isTV ? 24 : 15, weight: .bold)).padding(.horizontal, 16).padding(.vertical, 6)
                .background(.white.opacity(0.16), in: Capsule())
            if let logo = s.logo {
                Image(uiImage: logo).resizable().aspectRatio(contentMode: .fit)
                    .frame(maxWidth: Theme.isTV ? 620 : 320, maxHeight: Theme.isTV ? 180 : 100, alignment: .leading).shadow(radius: 16)
            } else {
                Text(tm.title).font(.system(size: Theme.isTV ? 72 : 40, weight: .heavy)).lineLimit(2).shadow(radius: 14)
            }
            HStack(spacing: 14) {
                if !tm.rating.isEmpty && tm.votes >= 20 {
                    Text("★ " + tm.rating).foregroundStyle(Color(red: 1, green: 0.83, blue: 0.3)).fontWeight(.heavy)
                }
                Text(meta)
            }.font(.system(size: Theme.isTV ? 28 : 17, weight: .semibold)).foregroundStyle(.white.opacity(0.88))
            Text(tm.overview).font(.system(size: Theme.isTV ? 28 : 17)).foregroundStyle(.white.opacity(0.88))
                .lineLimit(3).lineSpacing(6).frame(maxWidth: Theme.isTV ? 1000 : 600, alignment: .leading)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, Theme.isTV ? 110 : 28).padding(.bottom, Theme.isTV ? 90 : 40)
    }
}
