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
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

    fileprivate func noted() {
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

// MARK: - Trailers

/// Opens a trailer in the YouTube app (Apple TV and iPad); when TMDB has no trailer — common for
/// Arabic and Turkish titles — opens a YouTube search for "<title> <year> إعلان / trailer".
enum TrailerLauncher {
    struct Query { let q: String; let names: [String]; var season = 0 }

    /// What to search on YouTube, and the names the video's title must contain (never an unrelated clip).
    static func query(_ tm: TMDBInfo?, _ item: MediaItem, series: Bool, season: Int = 0) -> Query {
        let name = (tm.map { $0.original.isEmpty ? $0.title : $0.original }) ?? Clean.title(item.name)
        let year = tm?.year ?? String(item.year.prefix(4))
        let arabic = name.range(of: "[\\u0600-\\u06FF]", options: .regularExpression) != nil
        // "اعلان" alone also means a TV commercial — say what it is
        let q = season > 0 ? (arabic ? "مسلسل \(name) الموسم \(season) برومو" : "\(name) season \(season) official trailer")
              : arabic ? (series ? "مسلسل \(name) برومو" : "فيلم \(name) \(year) اعلان رسمي")
                       : "\(name) \(year)\(series ? " series" : "") official trailer"
        let names = [name, tm?.title ?? "", Clean.title(item.name)].filter { !$0.isEmpty }
        return Query(q: q.split(separator: " ").joined(separator: " "), names: names, season: season)
    }

    static func norm(_ s: String) -> String {
        var t = s.lowercased().folding(options: [.diacriticInsensitive], locale: nil)
        t = t.replacingOccurrences(of: "[\\u064B-\\u0652\\u0640]", with: "", options: .regularExpression)
        t = t.replacingOccurrences(of: "[أإآ]", with: "ا", options: .regularExpression)
            .replacingOccurrences(of: "ة", with: "ه").replacingOccurrences(of: "ى", with: "ي")
        t = t.replacingOccurrences(of: "[^\\p{L}\\p{N}]+", with: " ", options: .regularExpression)
        return t.split(separator: " ").joined(separator: " ")
    }

    /// Finds a trailer on YouTube when TMDB has none (YouTube Data API, 100 searches a day for the project:
    /// every answer is remembered, also "nothing found").
    /// Does a video title name season n ("Season 2", "S02", "الموسم الثاني", "الجزء 2", "2. Sezon")?
    /// Season 1 is often just "the trailer": fine as long as no other season is named.
    /// Episode a video previews ("8. Bölüm 2. Fragman", "Episode 8", "الحلقة 8" → 8), 0 when it is not about one episode.
    static func episodeOf(_ title: String) -> Int {
        let t = norm(title)
        if t.range(of: "الحلقه الاخيره|final episode|final bölüm|finali|son bölüm", options: .regularExpression) != nil { return 99 }
        let pattern = "s\\d+ ?e(\\d+)|(\\d+) bölüm|bölüm (\\d+)|episode (\\d+)|(?:^| )ep ?(\\d+)|(?:^| )e(\\d+)(?: |$)|(?:الحلقه|حلقه) ([0-9\\u0660-\\u0669]+)"
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

    /// The videos that really play here: public, embeddable, not blocked in the viewer's country
    /// ("The uploader has not made this video available in your country"). One API unit for up to 50 ids.
    static func usable(_ ids: [String]) async -> [String] {
        let ids = ids.filter { !$0.isEmpty }
        let apiKey = CloudConfig.youtubeKey
        guard !apiKey.isEmpty, !ids.isEmpty else { return ids }
        let d = UserDefaults.standard
        let ask = ids.filter { d.object(forKey: "ytok|" + $0) == nil }
        if !ask.isEmpty {
            var c = URLComponents(string: "https://www.googleapis.com/youtube/v3/videos")!
            c.queryItems = [.init(name: "part", value: "status,contentDetails"), .init(name: "id", value: ask.joined(separator: ",")), .init(name: "key", value: apiKey)]
            guard let url = c.url, let (data, resp) = try? await URLSession.shared.data(from: url),
                  (resp as? HTTPURLResponse)?.statusCode == 200,
                  let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return ids }   // can't tell: try them
            let cc = (Locale.current.regionCode ?? "SA").uppercased()
            var seen = Set<String>()
            for it in (o["items"] as? [[String: Any]]) ?? [] {
                let id = jstr(it["id"]); seen.insert(id)
                let st = it["status"] as? [String: Any] ?? [:]
                let rr = (it["contentDetails"] as? [String: Any])?["regionRestriction"] as? [String: Any] ?? [:]
                let blocked = ((rr["blocked"] as? [String])?.contains(cc) ?? false) || ((rr["allowed"] as? [String]).map { !$0.contains(cc) } ?? false)
                let ok = jstr(st["privacyStatus"]) != "private" && (st["embeddable"] as? Bool ?? true) && !blocked && jstr(st["uploadStatus"]) != "rejected"
                d.set(ok, forKey: "ytok|" + id)
            }
            for id in ask where !seen.contains(id) { d.set(false, forKey: "ytok|" + id) }   // deleted / private
        }
        return ids.filter { d.bool(forKey: "ytok|" + $0) }
    }

    static func namesSeason(_ title: String, _ n: Int) -> Bool {
        let t = norm(title)
        let ar = ["", "الاول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر"]
        func says(_ k: Int) -> Bool {
            let east = Array("٠١٢٣٤٥٦٧٨٩")
            let digits = String(String(k).compactMap { $0.wholeNumberValue.map { east[$0] } })
            var num = "(?:0?\(k)|\(digits)"
            if k < ar.count { num += "|" + ar[k] }
            num += ")"
            let p = "(?:season|sezon|الموسم|موسم|الجزء|جزء)\\s*\(num)(?![0-9])|(?:^|\\s)s0?\(k)(?![0-9])|(?:^|\\s)\(k)\\s*sezon"
            return t.range(of: p, options: [.regularExpression, .caseInsensitive]) != nil
        }
        if says(n) { return true }
        return n == 1 && !(2...30).contains { says($0) }
    }

    static func search(_ query: String, names: [String], season: Int = 0) async -> String {
        let apiKey = CloudConfig.youtubeKey
        guard !apiKey.isEmpty, !query.isEmpty else { return "" }
        let cacheKey = "yt3|" + query.lowercased()
        if let hit = UserDefaults.standard.string(forKey: cacheKey) { return hit }
        var c = URLComponents(string: "https://www.googleapis.com/youtube/v3/search")!
        c.queryItems = [.init(name: "part", value: "snippet"), .init(name: "type", value: "video"), .init(name: "maxResults", value: "10"),
                        .init(name: "videoEmbeddable", value: "true"), .init(name: "safeSearch", value: "moderate"),
                        .init(name: "q", value: query), .init(name: "key", value: apiKey)]
        guard let url = c.url, let res = try? await URLSession.shared.data(from: url) else { return "" }
        let (data, resp) = res
        guard (resp as? HTTPURLResponse)?.statusCode == 200,
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }   // quota / offline: try another time
        let items = (o["items"] as? [[String: Any]]) ?? []
        func title(_ i: [String: Any]) -> String { ((i["snippet"] as? [String: Any])?["title"] as? String) ?? "" }
        // the video title must name this title and look like a trailer / promo (not a song or a commercial)
        let wanted = names.map(norm).filter { $0.count >= 2 }
        func matches(_ t: String, _ pattern: String) -> Bool { t.range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil }
        var good = items.filter { i in
            let t = title(i), n = norm(t)
            return wanted.contains { n.contains($0) }
                && matches(t, "trailer|teaser|promo|official|اعلان|إعلان|برومو|تريلر|fragman|tanıtım")
                && !matches(t, "اغنية|أغنية|كليب|music video|lyrics|official video|official audio")
                && episodeOf(t) <= 1
                && (season == 0 || namesSeason(t, season))
        }
        // the official / first-episode trailer before other promos
        let strong = "official trailer|resmi fragman|tanıtım|الاعلان الرسمي|اعلان رسمي|الاعلان الاول|1 bölüm fragman|ilk fragman|teaser trailer"
        good = good.filter { norm(title($0)).range(of: strong, options: .regularExpression) != nil }
            + good.filter { norm(title($0)).range(of: strong, options: .regularExpression) == nil }
        let ids = good.compactMap { ($0["id"] as? [String: Any])?["videoId"] as? String }
        let id = await usable(ids).first ?? ""
        UserDefaults.standard.set(id, forKey: cacheKey)
        return id
    }

    /// TMDB trailer, else a YouTube API search. iPad plays it inside the app (`play`); Apple TV has no web view,
    /// so it opens the video in the YouTube app. Without any video: a YouTube search.
    @MainActor static func open(keys: [String], query: Query, play: @escaping (String?) -> Void) {
        Task { @MainActor in
            // TMDB's trailers first — only one that really plays here (not blocked in this country)
            let clean = keys.map { $0.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" } }.filter { !$0.isEmpty }
            var k = await usable(clean).first ?? ""
            if k.isEmpty { k = await search(query.q, names: query.names, season: query.season) }
            #if os(iOS)
            if !k.isEmpty { play(k); return }
            #endif
            #if os(tvOS)
            // Apple TV's YouTube app plays a video only with youtube://watch/<id> (other forms just open its home page).
            // It has no search link, so without a video we say so instead of dropping the viewer on YouTube's home.
            guard !k.isEmpty else { play(nil); return }
            let urls: [URL] = ["youtube://watch/\(k)", "youtube://www.youtube.com/watch?v=\(k)"].compactMap(URL.init)
            #else
            // no real trailer found: say so (a YouTube search used to land on unrelated videos)
            guard !k.isEmpty else { play(nil); return }
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
