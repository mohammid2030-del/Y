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
    static func query(_ tm: TMDBInfo?, _ item: MediaItem) -> String {
        let name = (tm.map { $0.original.isEmpty ? $0.title : $0.original }) ?? Clean.title(item.name)
        let year = tm?.year ?? String(item.year.prefix(4))
        let arabic = name.range(of: "[\\u0600-\\u06FF]", options: .regularExpression) != nil
        return "\(name) \(year) \(arabic ? "اعلان" : "trailer")".replacingOccurrences(of: "  ", with: " ")
    }

    /// Finds a trailer on YouTube when TMDB has none (YouTube Data API, 100 searches a day for the project:
    /// every answer is remembered, also "nothing found").
    static func search(_ query: String) async -> String {
        let apiKey = CloudConfig.youtubeKey
        guard !apiKey.isEmpty, !query.isEmpty else { return "" }
        let cacheKey = "yt|" + query.lowercased()
        if let hit = UserDefaults.standard.string(forKey: cacheKey) { return hit }
        var c = URLComponents(string: "https://www.googleapis.com/youtube/v3/search")!
        c.queryItems = [.init(name: "part", value: "snippet"), .init(name: "type", value: "video"), .init(name: "maxResults", value: "6"),
                        .init(name: "videoEmbeddable", value: "true"), .init(name: "safeSearch", value: "moderate"),
                        .init(name: "q", value: query), .init(name: "key", value: apiKey)]
        guard let url = c.url, let res = try? await URLSession.shared.data(from: url) else { return "" }
        let (data, resp) = res
        guard (resp as? HTTPURLResponse)?.statusCode == 200,
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }   // quota / offline: try another time
        let items = (o["items"] as? [[String: Any]]) ?? []
        func title(_ i: [String: Any]) -> String { ((i["snippet"] as? [String: Any])?["title"] as? String) ?? "" }
        let words = try? NSRegularExpression(pattern: "trailer|teaser|official|promo|اعلان|إعلان|برومو|تريلر|fragman|tanıtım", options: .caseInsensitive)
        let best = items.first { i in let t = title(i); return words?.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) != nil } ?? items.first
        let id = ((best?["id"] as? [String: Any])?["videoId"] as? String) ?? ""
        UserDefaults.standard.set(id, forKey: cacheKey)
        return id
    }

    /// TMDB trailer, else a YouTube API search. iPad plays it inside the app (`play`); Apple TV has no web view,
    /// so it opens the video in the YouTube app. Without any video: a YouTube search.
    @MainActor static func open(key: String?, query: String, play: @escaping (String?) -> Void) {
        Task { @MainActor in
            var k = (key ?? "").filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
            if k.isEmpty { k = await search(query) }
            #if os(iOS)
            if !k.isEmpty { play(k); return }
            #endif
            let q = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
            #if os(tvOS)
            // Apple TV's YouTube app plays a video only with youtube://watch/<id> (other forms just open its home page).
            // It has no search link, so without a video we say so instead of dropping the viewer on YouTube's home.
            guard !k.isEmpty else { play(nil); return }
            let urls: [URL] = ["youtube://watch/\(k)", "youtube://www.youtube.com/watch?v=\(k)"].compactMap(URL.init)
            #else
            let urls: [URL] = !k.isEmpty
                ? ["youtube://watch?v=\(k)", "youtube://www.youtube.com/watch?v=\(k)", "https://www.youtube.com/watch?v=\(k)"].compactMap(URL.init)
                : ["youtube://results?search_query=\(q)", "youtube://www.youtube.com/results?search_query=\(q)", "https://www.youtube.com/results?search_query=\(q)"].compactMap(URL.init)
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
