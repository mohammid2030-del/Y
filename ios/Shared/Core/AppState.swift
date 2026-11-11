import Foundation
import SwiftUI

enum Phase: Equatable {
    case signIn
    case profiles
    case viewers
    case addSubscription(String?)
    case syncing(String)
    case main
}

enum Tab: Hashable { case home, live, movies, series, search, favorites, settings }

@MainActor
final class AppState: ObservableObject {
    @Published var phase: Phase = .profiles
    @Published var tab: Tab = .home
    @Published var subs: [Subscription] = []
    @Published var activeID: String = ""
    /// People watching on the active subscription, and who is watching now.
    @Published var viewers: [Viewer] = []
    @Published var viewerID = "main"
    @Published var cloudStatus = ""
    /// Google account the subscriptions are saved to (nil = not signed in).
    @Published var account: String? = Cloud.shared.accountUID == nil ? nil : Cloud.shared.accountEmail
    @Published var catalog = Catalog() { didSet { rebuildIndex() } }
    /// Shelves, duplicate groups and quality, rebuilt in the background whenever the catalog changes.
    @Published var index: CatalogIndex?
    private var indexJob: Task<Void, Never>?
    private var browseCache: [String: [MediaItem]] = [:]
    @Published var favorites: [FavItem] = []
    @Published var history: [String: HistoryItem] = [:]
    @Published var playing: PlayRequest?
    @Published var syncMessage = ""
    @Published var syncError: String?
    @Published var tmdbOn = TMDB.enabledNow
    @Published var showMatches = Storage.string("showMatches") != "0"
    /// A search started from elsewhere (e.g. a cast member's known titles).
    @Published var pendingSearch: String?
    @Published var matchPrefs: MatchPrefs = Storage.load("matchPrefs", as: MatchPrefs.self) ?? MatchPrefs()

    private var byCategoryCache: [String: [MediaItem]] = [:]

    init() {
        URLCache.shared = URLCache(memoryCapacity: 64 << 20, diskCapacity: 400 << 20)
        subs = Storage.load("subs", as: [Subscription].self) ?? []
        favorites = Storage.load("favorites", as: [FavItem].self) ?? []
        history = Storage.load("history", as: [String: HistoryItem].self) ?? [:]
        activeID = Storage.string("activeSub") ?? subs.first?.id ?? ""
        if subs.isEmpty {
            phase = Cloud.shared.googleAvailable && account == nil ? .signIn : .addSubscription(nil)
        } else {
            phase = .profiles
        }
        if account != nil { Task { await pullAccount() } }
    }

    var active: Subscription? { subs.first { $0.id == activeID } }
    /// History / favorites owner: the subscription for the first viewer (keeps existing data), "sub#viewer" for the others.
    var ownerID: String { viewerID == "main" ? activeID : "\(activeID)#\(viewerID)" }
    var currentViewer: Viewer? { viewers.first { $0.id == viewerID } }

    // MARK: subscriptions

    func activate(_ id: String) {
        activeID = id
        Storage.setString("activeSub", id)
        byCategoryCache = [:]
        if let c = Storage.loadCatalog(for: id) {
            catalog = c
            enterAfterSubscription()
        } else {
            phase = .syncing(id)
        }
    }

    func save(id: String?, name: String, kind: SourceKind, server: String, user: String, pass: String, m3u: String) {
        let sid = id ?? UUID().uuidString
        let srv = kind == .xtream ? Xtream.normalize(server) : ""
        let host = URL(string: kind == .xtream ? srv : m3u)?.host ?? "NOVA"
        var sub = Subscription(id: sid, name: name.isEmpty ? host : name, kind: kind, server: srv, username: user)
        sub.updated = Date().timeIntervalSince1970
        if let i = subs.firstIndex(where: { $0.id == sid }) { sub.expires = subs[i].expires; subs[i] = sub } else { subs.append(sub) }
        Keychain.set(pass, for: "pass|\(sid)")
        Keychain.set(m3u, for: "m3u|\(sid)")
        Storage.save("subs", subs)
        pushAccountSub(sub)
        activeID = sid
        Storage.setString("activeSub", sid)
        phase = .syncing(sid)
    }

    func delete(_ id: String) {
        if let uid = Cloud.shared.accountUID {
            let tomb: [String: Any] = ["x": true, "up": Date().timeIntervalSince1970]
            Task { await Cloud.shared.put("users/\(uid)/subs/\(Cloud.safe(id))", tomb) }
        }
        subs.removeAll { $0.id == id }
        favorites.removeAll { $0.subID == id || $0.subID.hasPrefix(id + "#") }
        history = history.filter { $0.value.subID != id && !$0.value.subID.hasPrefix(id + "#") }
        Keychain.set(nil, for: "pass|\(id)"); Keychain.set(nil, for: "m3u|\(id)")
        Storage.deleteCatalog(for: id)
        Storage.save("subs", subs); Storage.save("favorites", favorites); Storage.save("history", history)
        if subs.isEmpty { phase = .addSubscription(nil) } else if id == activeID { activate(subs[0].id) }
    }

    func xtream(for sub: Subscription) -> Xtream {
        Xtream(server: sub.server, user: sub.username, pass: Keychain.get("pass|\(sub.id)") ?? "")
    }

    func sync(_ id: String) async {
        guard let i = subs.firstIndex(where: { $0.id == id }) else { phase = .profiles; return }
        syncError = nil
        syncMessage = L.t("connecting")
        let sub = subs[i]
        do {
            let report: (String) -> Void = { msg in Task { @MainActor in self.syncMessage = msg } }
            var cat: Catalog
            var exp: Date? = nil
            if sub.kind == .xtream {
                (cat, exp) = try await xtream(for: sub).load(progress: report)
            } else {
                cat = try await M3U.load(Keychain.get("m3u|\(id)") ?? "", progress: report)
            }
            syncMessage = L.t("saving")
            Storage.saveCatalog(cat, for: id)
            subs[i].lastSync = Date()
            if let exp { subs[i].expires = exp }
            Storage.save("subs", subs)
            catalog = cat
            byCategoryCache = [:]
            activeID = id
            Storage.setString("activeSub", id)
            enterAfterSubscription()
        } catch {
            syncError = error.localizedDescription
        }
    }

    // MARK: catalog helpers

    func items(_ kind: MediaKind, category: String?) -> [MediaItem] {
        let all = catalog.items(kind)
        guard let category else { return all }
        let k = "\(kind.rawValue)|\(category)"
        if let hit = byCategoryCache[k] { return hit }
        let list = all.filter { $0.category == category }
        byCategoryCache[k] = list
        return list
    }

    func latest(_ kind: MediaKind, _ n: Int) -> [MediaItem] {
        let k = "latest|\(kind.rawValue)|\(TMDBIDs.shared.version)"
        if let hit = byCategoryCache[k] { return Array(hit.prefix(n)) }
        let recent = Array(catalog.items(kind).sorted { $0.added > $1.added }.prefix(240))
        let list = Array(collapse(kind, recent).prefix(60))
        if index != nil { byCategoryCache[k] = list }   // cache once duplicates can be collapsed
        return Array(list.prefix(n))
    }

    /// The hero: a different selection every day, from the newest additions (the same all day long).
    var featured: [(MediaKind, MediaItem)] {
        let day = Int(Date().timeIntervalSince1970 / 86400)
        var rng = SeededRandom(seed: UInt64(day) &* 2654435761 &+ activeID.utf8.reduce(UInt64(0)) { $0 &* 31 &+ UInt64($1) })
        let m = latest(.movie, 60).filter { !$0.logo.isEmpty }.shuffled(using: &rng)
        let s = latest(.series, 60).filter { !$0.logo.isEmpty }.shuffled(using: &rng)
        var out: [(MediaKind, MediaItem)] = []
        for i in 0..<6 {
            if i < m.count { out.append((.movie, m[i])) }
            if i < s.count { out.append((.series, s[i])) }
        }
        return Array(out.prefix(8))
    }

    // MARK: keeping the catalog fresh

    private var refreshing = false
    /// New films / series / channels from the provider without a sync screen: when the app opens or comes back,
    /// and every three hours while it stays open ("latest" rows and the hero update by themselves).
    func refreshCatalogIfStale(maxAge: TimeInterval = 3 * 3600) {
        guard phase == .main, !refreshing, let i = subs.firstIndex(where: { $0.id == activeID }) else { return }
        if let last = subs[i].lastSync, Date().timeIntervalSince(last) < maxAge { return }
        refreshing = true
        let sub = subs[i], id = sub.id
        Task { @MainActor in
            defer { refreshing = false }
            let fresh: Catalog?
            if sub.kind == .xtream {
                fresh = try? await xtream(for: sub).load(progress: { _ in }).0
            } else {
                fresh = try? await M3U.load(Keychain.get("m3u|\(id)") ?? "", progress: { _ in })
            }
            guard let cat = fresh, id == activeID, !(cat.movies.isEmpty && cat.series.isEmpty && cat.live.isEmpty) else { return }
            Storage.saveCatalog(cat, for: id)
            if let j = subs.firstIndex(where: { $0.id == id }) { subs[j].lastSync = Date(); Storage.save("subs", subs) }
            byCategoryCache = [:]
            catalog = cat
        }
    }

    /// One card per movie / series — its latest entry. A finished movie disappears; a series shows the episode in
    /// progress, or the next one (up-next mark) after finishing one; after the last episode it disappears.
    var continueWatching: [HistoryItem] {
        var seen = Set<String>()
        return history.values.filter { $0.subID == ownerID && $0.kind != .live }
            .sorted { $0.updated > $1.updated }
            .filter { seen.insert("\($0.kind.rawValue)|\($0.stream.id)").inserted }
            .filter { $0.resumable || $0.upNext }
            .prefix(15).map { $0 }
    }

    var recentChannels: [MediaItem] {
        history.values.filter { $0.subID == ownerID && $0.kind == .live }.sorted { $0.updated > $1.updated }.prefix(15).map(\.stream)
    }

    // MARK: favorites

    func isFavorite(_ kind: MediaKind, _ item: MediaItem) -> Bool {
        favorites.contains { $0.subID == ownerID && $0.kind == kind && $0.stream.id == item.id }
    }

    func toggleFavorite(_ kind: MediaKind, _ item: MediaItem) {
        let owner = ownerID
        if let i = favorites.firstIndex(where: { $0.subID == owner && $0.kind == kind && $0.stream.id == item.id }) {
            let f = favorites.remove(at: i)
            cloudPush(fav: f, deleted: true)
        } else {
            let f = FavItem(subID: owner, kind: kind, stream: item)
            favorites.append(f)
            cloudPush(fav: f, deleted: false)
        }
        Storage.save("favorites", favorites)
    }

    // MARK: playback

    func url(_ kind: MediaKind, _ item: MediaItem, episode: Episode? = nil) -> URL? {
        guard let sub = active else { return nil }
        if sub.kind == .m3u { return URL(string: item.url) }
        return xtream(for: sub).url(kind: kind, item: item, episode: episode)
    }

    func playLive(_ item: MediaItem, list: [MediaItem]) {
        guard let u = url(.live, item) else { return }
        playing = PlayRequest(kind: .live, stream: item, title: item.name, url: u, channels: list.isEmpty ? [item] : list)
        record(kind: .live, item: item, position: 0, duration: 0)
    }

    func playMovie(_ item: MediaItem, fromStart: Bool = false) {
        guard let u = url(.movie, item) else { return }
        let h = history[HistoryItem.key(ownerID, .movie, item.id)]
        playing = PlayRequest(kind: .movie, stream: item, title: Clean.title(item.name), url: u,
                              startMs: !fromStart && (h?.resumable ?? false) ? h!.position : 0)
    }

    func playEpisode(_ e: Episode, of series: MediaItem, all: [Episode], fromStart: Bool = false) {
        guard let u = url(.series, series, episode: e) else { return }
        let h = history[HistoryItem.key(ownerID, .series, series.id, e.id)]
        playing = PlayRequest(kind: .series, stream: series, title: Clean.title(series.name),
                              subtitle: "S\(e.season) E\(e.num)" + (e.title.isEmpty ? "" : " — \(e.title)"), url: u,
                              startMs: !fromStart && (h?.resumable ?? false) ? h!.position : 0, episode: e, seriesEpisodes: all)
    }

    func resume(_ h: HistoryItem) {
        if h.kind == .movie { playMovie(h.stream); return }
        let e = Episode(id: h.episodeID, season: Clean.seasonEpisode(h.subtitle)?.0 ?? 1, num: Clean.seasonEpisode(h.subtitle)?.1 ?? 1,
                        title: "", ext: h.episodeExt, plot: "", image: "", duration: 0)
        guard let u = url(.series, h.stream, episode: e) else { return }
        playing = PlayRequest(kind: .series, stream: h.stream, title: Clean.title(h.stream.name), subtitle: h.subtitle, url: u,
                              startMs: h.upNext ? 0 : h.position, episode: e)
    }

    /// After finishing an episode: Continue watching shows the next one, from the start.
    func recordUpNext(series: MediaItem, next e: Episode) {
        let owner = ownerID
        let h = HistoryItem(subID: owner, kind: .series, stream: series, episodeID: e.id, episodeExt: e.ext,
                            subtitle: "S\(e.season) E\(e.num)" + (e.title.isEmpty ? "" : " — \(e.title)"),
                            position: 1, duration: 0, updated: Date().addingTimeInterval(0.01))
        history[h.id] = h
        saveHistorySoon()
        cloudPush(history: h)
    }

    // MARK: intro / end credits

    struct Segs { var introStart = 0, introEnd = 0, credits = 0 }

    /// Every episode of a series in order (all seasons) and its TMDB id, for "next episode" across seasons.
    func allEpisodes(_ series: MediaItem) async -> (episodes: [Episode], tmdb: String) {
        guard let sub = active, sub.kind != .m3u, let info = try? await xtream(for: sub).seriesInfo(series.id) else { return ([], "") }
        return (info.seasons.keys.sorted().flatMap { info.seasons[$0] ?? [] }, info.tmdb)
    }

    // MARK: AI Arabic subtitles (made on an Android device of this subscription, shared in Firebase)

    struct AICue { let start: Double; let end: Double; let text: String }

    /// Needs the shared cloud (Firebase) and an Xtream subscription; the work itself runs on an Android device.
    var aiSubsAvailable: Bool { Cloud.shared.enabled && subKey != nil && active?.kind == .xtream }

    /// Asks an Android device with the app to translate this series from `episodeID` onward.
    func requestAISubs(seriesID: String, episodeID: String) {
        guard let p = cloudPath("aisubs/req/\(Cloud.safe(seriesID))") else { return }
        Task { await Cloud.shared.put(p, ["ep": episodeID, "t": Int(Date().timeIntervalSince1970)]) }
    }

    /// ("" / queued / working / done / error, progress 0–100) for one episode.
    func aiSubsState(seriesID: String, episodeID: String) async -> (String, Int) {
        guard let b = cloudPath("aisubs/eps/\(Cloud.safe(seriesID))/\(Cloud.safe(episodeID))") else { return ("", 0) }
        let st = jstr(await Cloud.shared.get(b + "/s"))
        let pr = jint(await Cloud.shared.get(b + "/p"))
        if st.isEmpty, let r = cloudPath("aisubs/req/\(Cloud.safe(seriesID))"), await Cloud.shared.get(r) is [String: Any] {
            return ("queued", 0)
        }
        return (st, pr)
    }

    /// The finished subtitles of one episode, parsed (empty when there are none yet).
    func aiSubs(seriesID: String, episodeID: String) async -> [AICue] {
        guard let b = cloudPath("aisubs/eps/\(Cloud.safe(seriesID))/\(Cloud.safe(episodeID))"),
              jstr(await Cloud.shared.get(b + "/s")) == "done",
              let vtt = await Cloud.shared.get(b + "/vtt") as? String else { return [] }
        return Self.parseVTT(vtt)
    }

    nonisolated static func parseVTT(_ vtt: String) -> [AICue] {
        func secs(_ t: String) -> Double? {
            let p = t.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".").split(separator: ":")
            guard p.count >= 2 else { return nil }
            var v = 0.0
            for x in p { guard let d = Double(x) else { return nil }; v = v * 60 + d }
            return v
        }
        var out: [AICue] = []
        let blocks = vtt.replacingOccurrences(of: "\r", with: "").components(separatedBy: "\n\n")
        for b in blocks {
            let lines = b.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
            guard let i = lines.firstIndex(where: { $0.contains("-->") }) else { continue }
            let parts = lines[i].components(separatedBy: "-->")
            guard parts.count == 2, let a = secs(parts[0]), let e = secs(parts[1].split(separator: " ").first.map(String.init) ?? "") else { continue }
            let text = lines[(i + 1)...].joined(separator: "\n")
            if !text.isEmpty { out.append(AICue(start: a, end: e, text: text)) }
        }
        return out.sorted { $0.start < $1.start }
    }

    /// Intro / credits marked by hand in the player: saved for this episode and as the season's default, in Firebase,
    /// so Android, Samsung and every Apple device skip it too. Empty times clear the marks.
    func saveManualSegments(seriesID: String, season: Int, episode: Int, _ s: Segs) {
        var v: [String: Any] = ["v": 1, "m": 1, "t": Int(Date().timeIntervalSince1970)]
        if s.introEnd > s.introStart { v["is"] = s.introStart; v["ie"] = s.introEnd }
        if s.credits > 0 { v["cs"] = s.credits }
        let clear = s.introEnd <= s.introStart && s.credits <= 0
        for key in ["\(season)_\(episode)", "\(season)_0"] {
            guard let p = cloudPath("segs/\(Cloud.safe(seriesID))/\(key)") else { continue }
            Task { await Cloud.shared.put(p, clear ? NSNull() : v) }
        }
    }

    /// Intro / credits times: found by sound on an Android device of this subscription (shared in Firebase,
    /// exact for these very files), else the TheIntroDB community database (by TMDB id).
    func segments(seriesID: String, season: Int, episode: Int, tmdb: String) async -> Segs? {
        // this episode (sound fingerprint or marked by hand), then the season's times marked by hand ("<season>_0")
        for key in ["\(season)_\(episode)", "\(season)_0"] {
            if let p = cloudPath("segs/\(Cloud.safe(seriesID))/\(key)"), let d = await Cloud.shared.get(p) as? [String: Any] {
                let s = Segs(introStart: jint(d["is"]), introEnd: jint(d["ie"]), credits: jint(d["cs"]))
                if s.introEnd > s.introStart || s.credits > 0 { return s }
            }
        }
        guard !tmdb.isEmpty, season > 0, episode > 0,
              let u = URL(string: "https://api.theintrodb.org/v4/media?tmdb_id=\(tmdb)&season=\(season)&episode=\(episode)") else { return nil }
        var r = URLRequest(url: u, timeoutInterval: 10)
        r.setValue("NOVA-IPTV/1.0 (Apple)", forHTTPHeaderField: "User-Agent")
        guard let (data, resp) = try? await URLSession.shared.data(for: r), (resp as? HTTPURLResponse)?.statusCode == 200,
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        func first(_ k: String) -> [String: Any]? { (o[k] as? [[String: Any]])?.first ?? o[k] as? [String: Any] }
        var s = Segs()
        if let i = first("intro"), jint(i["end_ms"]) > jint(i["start_ms"]) { s.introStart = jint(i["start_ms"]); s.introEnd = jint(i["end_ms"]) }
        if let c = first("credits") { s.credits = jint(c["start_ms"]) }
        return s.introEnd > s.introStart || s.credits > 0 ? s : nil
    }

    func record(kind: MediaKind, item: MediaItem, episode: Episode? = nil, subtitle: String = "", position: Int, duration: Int) {
        let owner = ownerID
        let key = HistoryItem.key(owner, kind, item.id, episode?.id ?? "")
        let h = HistoryItem(subID: owner, kind: kind, stream: item, episodeID: episode?.id ?? "", episodeExt: episode?.ext ?? "",
                            subtitle: subtitle, position: position, duration: duration, updated: Date())
        history[key] = h
        saveHistorySoon()
        cloudPush(history: h)
    }

    private var historySaveTask: Task<Void, Never>?
    /// History is written at most once a second (the player records every few seconds), and kept to the newest
    /// entries so the saved settings stay small — a big write on the main thread made the app stutter.
    func saveHistorySoon() {
        historySaveTask?.cancel()
        historySaveTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            if Task.isCancelled { return }
            if history.count > 500 {
                let keep = history.values.sorted { $0.updated > $1.updated }.prefix(400)
                history = Dictionary(uniqueKeysWithValues: keep.map { ($0.id, $0) })
            }
            Storage.save("history", history)
        }
    }

    func clearHistory() {
        let owner = ownerID
        history = history.filter { $0.value.subID != owner }
        Storage.save("history", history)
        if let p = cloudPath("data/\(viewerID)/h") { Task { await Cloud.shared.put(p, [String: Any]()) } }
    }

    private var titleIndexCache: [MediaKind: [String: [MediaItem]]] = [:]
    private var titleIndexOwner = ""

    /// Cleaned title → items, built once per catalog off the main thread.
    private func titleIndex(_ kind: MediaKind) async -> [String: [MediaItem]] {
        let owner = "\(activeID)|\(catalog.items(kind).count)"
        if owner != titleIndexOwner { titleIndexCache = [:]; titleIndexOwner = owner }
        if let i = titleIndexCache[kind] { return i }
        let list = catalog.items(kind)
        let idx = await Task.detached(priority: .userInitiated) { () -> [String: [MediaItem]] in
            var d: [String: [MediaItem]] = [:]
            for m in list { d[Clean.title(m.name).lowercased(), default: []].append(m) }
            return d
        }.value
        titleIndexCache[kind] = idx
        return idx
    }

    /// Finds a TMDB title in the user's own catalog (Arabic or original title, preferring the same year).
    func findInCatalog(_ w: KnownWork) async -> (MediaKind, MediaItem)? {
        let kind: MediaKind = w.isTV ? .series : .movie
        let idx = await titleIndex(kind)
        let names = [w.title, w.original].map { Clean.title($0).lowercased() }.filter { !$0.isEmpty }
        let hits = names.flatMap { idx[$0] ?? [] }
        guard !hits.isEmpty else { return nil }
        let sameYear = hits.first { m in
            let y = m.year.isEmpty ? Clean.full(m.name).year : String(m.year.prefix(4))
            return w.year.isEmpty || y.isEmpty || y == w.year
        }
        return (kind, sameYear ?? hits[0])
    }

    private func rebuildIndex() {
        indexJob?.cancel()
        index = nil
        browseCache = [:]
        groupsCache = [:]
        let cat = catalog
        indexJob = Task { [weak self] in
            let idx = await Task.detached(priority: .userInitiated) { CatalogIndex.build(cat) }.value
            if Task.isCancelled { return }
            self?.index = idx
        }
    }

    func shelves(_ kind: MediaKind) -> [Shelf] { index?.shelves[kind] ?? [] }

    /// Identity of a title: the TMDB id when known (provider field, another copy's id, or a TMDB lookup), else the cleaned title.
    func groupKey(_ kind: MediaKind, _ m: MediaItem) -> String {
        guard let idx = index else { return m.id }
        let ik = "\(kind.rawValue)|\(m.id)"
        let tk = idx.key[ik] ?? m.id
        if let t = m.tmdb { return "tmdb:\(t)" }
        if let t = idx.titleTmdb["\(kind.rawValue)|\(tk)"] { return "tmdb:\(t)" }
        if let ck = idx.cardKey[ik], let id = TMDBIDs.shared.id(ck), id > 0 { return "tmdb:\(id)" }
        return tk
    }

    /// Titles of a shelf (or one of its categories), one card per title — the best copy (4K > FHD > HD), newest first.
    func browse(_ kind: MediaKind, shelf: String, category: String?) -> [MediaItem] {
        guard let idx = index else { return [] }
        let ck = "\(kind.rawValue)|\(shelf)|\(category ?? "")|\(TMDBIDs.shared.version)"
        if let hit = browseCache[ck] { return hit }
        let source: [MediaItem]
        if let category { source = items(kind, category: category) }
        else { source = catalog.items(kind).filter { idx.shelfOfCategory["\(kind.rawValue)|\($0.category)"] == shelf } }
        let out = collapse(kind, source)
        if browseCache.count > 200 { browseCache = [:] }
        browseCache[ck] = out
        return out
    }

    /// One card per title, best copy first, newest titles first.
    func collapse(_ kind: MediaKind, _ source: [MediaItem]) -> [MediaItem] {
        guard let idx = index else { return source }
        var best: [String: MediaItem] = [:]
        var order: [String] = []
        for m in source {
            let k = groupKey(kind, m)
            if let cur = best[k] {
                let a = idx.quality["\(kind.rawValue)|\(m.id)"] ?? 0, b = idx.quality["\(kind.rawValue)|\(cur.id)"] ?? 0
                if a > b || (a == b && m.added > cur.added) { best[k] = m }
            } else {
                best[k] = m
                order.append(k)
            }
        }
        return order.compactMap { best[$0] }.sorted { $0.added > $1.added }
    }

    private var variantsCache: [String: [MediaItem]] = [:]
    private var groupsCache: [String: (version: Int, map: [String: [MediaItem]])] = [:]

    /// Every TMDB-identified title → its copies, built once per catalog / TMDB update
    /// (scanning the whole catalog on each detail page made opening a title slow).
    private func tmdbGroups(_ kind: MediaKind) -> [String: [MediaItem]] {
        let v = TMDBIDs.shared.version
        if let c = groupsCache[kind.rawValue], c.version == v { return c.map }
        var map: [String: [MediaItem]] = [:]
        for m in catalog.items(kind) {
            let g = groupKey(kind, m)
            if g.hasPrefix("tmdb:") { map[g, default: []].append(m) }
        }
        groupsCache[kind.rawValue] = (v, map)
        return map
    }

    /// Every copy of the same title in the library (4K / HD / dubbed / subtitled, other names), best first.
    func variants(_ kind: MediaKind, _ item: MediaItem) -> [MediaItem] {
        guard let idx = index else { return [item] }
        let g = groupKey(kind, item)
        let ck = "\(kind.rawValue)|\(g)|\(TMDBIDs.shared.version)"
        if let hit = variantsCache[ck] { return hit }
        var list: [MediaItem]
        if g.hasPrefix("tmdb:") {
            list = tmdbGroups(kind)[g] ?? [item]
        } else {
            list = idx.variants["\(kind.rawValue)|\(g)"] ?? [item]
        }
        if list.isEmpty { list = [item] }
        list.sort {
            let a = idx.quality["\(kind.rawValue)|\($0.id)"] ?? 0, b = idx.quality["\(kind.rawValue)|\($1.id)"] ?? 0
            return a != b ? a > b : $0.added > $1.added
        }
        if variantsCache.count > 300 { variantsCache = [:] }
        variantsCache[ck] = list
        return list
    }

    /// Asks TMDB about titles of a list that it hasn't identified yet (cached for good), so copies merge.
    func identify(_ kind: MediaKind, _ list: [MediaItem]) async {
        guard tmdbOn, let idx = index else { return }
        let todo = list.filter { m in
            guard m.tmdb == nil, let ck = idx.cardKey["\(kind.rawValue)|\(m.id)"] else { return false }
            return TMDBIDs.shared.id(ck) == nil
        }
        var i = 0
        while i < todo.count {
            if Task.isCancelled { return }
            let chunk = Array(todo[i..<min(todo.count, i + 18)])
            await withTaskGroup(of: Void.self) { g in
                for m in chunk { g.addTask { _ = await TMDB.shared.card(kind: kind, name: m.name, year: m.year) } }
            }
            i += chunk.count
        }
    }

    func categoryName(_ kind: MediaKind, _ id: String) -> String {
        catalog.categories(kind).first { $0.id == id }?.name ?? ""
    }

    func searchFor(_ text: String) {
        pendingSearch = Clean.title(text)
        tab = .search
    }

    func updateMatchPrefs(_ change: (inout MatchPrefs) -> Void) {
        var p = matchPrefs
        change(&p)
        matchPrefs = p
        Storage.save("matchPrefs", p)
    }

    func toggleTeam(_ t: FavTeam) {
        updateMatchPrefs { p in
            if let i = p.teams.firstIndex(where: { $0.id == t.id }) { p.teams.remove(at: i) } else { p.teams.append(t) }
        }
    }

    func toggleComp(_ slug: String) {
        updateMatchPrefs { p in
            if let i = p.comps.firstIndex(of: slug) { p.comps.remove(at: i) } else { p.comps.append(slug) }
        }
    }

    // MARK: Google account: subscriptions (and the TMDB key) follow the account to every device

    private func pushAccountSub(_ sub: Subscription) {
        guard let uid = Cloud.shared.accountUID else { return }
        let d: [String: Any] = ["n": sub.name, "t": sub.kind == .xtream ? "xtream" : "m3u", "s": sub.server, "u": sub.username,
                                "p": Keychain.get("pass|\(sub.id)") ?? "", "m": Keychain.get("m3u|\(sub.id)") ?? "",
                                "up": sub.updated ?? Date().timeIntervalSince1970]
        Task { await Cloud.shared.put("users/\(uid)/subs/\(Cloud.safe(sub.id))", d) }
    }

    func pushAccountSettings(tmdbKey: String?) {
        guard let uid = Cloud.shared.accountUID else { return }
        Task { await Cloud.shared.put("users/\(uid)/settings/tmdb", tmdbKey ?? "") }
    }

    /// Merges the account's subscriptions with this device's (newest wins, deletions travel), then the TMDB key.
    func pullAccount() async {
        guard let uid = Cloud.shared.accountUID else { return }
        let remote = (await Cloud.shared.get("users/\(uid)") as? [String: Any]) ?? [:]
        let rs = remote["subs"] as? [String: Any] ?? [:]
        var changed = false
        for (id, raw) in rs {
            guard let d = raw as? [String: Any] else { continue }
            let up = Double(jstr(d["up"])) ?? 0
            if (d["x"] as? Bool) == true {
                if let local = subs.first(where: { Cloud.safe($0.id) == id }), (local.updated ?? 0) <= up {
                    subs.removeAll { $0.id == local.id }; changed = true
                }
                continue
            }
            let kind: SourceKind = jstr(d["t"]) == "m3u" ? .m3u : .xtream
            var sub = Subscription(id: id, name: jstr(d["n"]), kind: kind, server: jstr(d["s"]), username: jstr(d["u"]))
            sub.updated = up
            if let i = subs.firstIndex(where: { Cloud.safe($0.id) == id }) {
                if (subs[i].updated ?? 0) < up {
                    sub.id = subs[i].id; sub.lastSync = subs[i].lastSync; sub.expires = subs[i].expires
                    subs[i] = sub; changed = true
                    Keychain.set(jstr(d["p"]), for: "pass|\(sub.id)"); Keychain.set(jstr(d["m"]), for: "m3u|\(sub.id)")
                } else if (subs[i].updated ?? 0) > up { pushAccountSub(subs[i]) }
            } else {
                subs.append(sub); changed = true
                Keychain.set(jstr(d["p"]), for: "pass|\(id)"); Keychain.set(jstr(d["m"]), for: "m3u|\(id)")
            }
        }
        for s in subs where rs[Cloud.safe(s.id)] == nil { pushAccountSub(s) }
        if changed { Storage.save("subs", subs) }
        if let key = (remote["settings"] as? [String: Any])?["tmdb"] as? String, !key.isEmpty, !tmdbOn {
            if await TMDB.shared.setKey(key) { tmdbOn = true }
        } else if tmdbOn, ((remote["settings"] as? [String: Any])?["tmdb"] as? String ?? "").isEmpty,
                  let mine = Keychain.get("tmdbKey") {
            pushAccountSettings(tmdbKey: mine)
        }
    }

    /// After Google sign-in: bring the account's subscriptions here and continue.
    func didSignIn(_ email: String) async {
        account = email
        await pullAccount()
        if subs.isEmpty { phase = .addSubscription(nil) }
        else if phase == .signIn || phase == .addSubscription(nil) { phase = .profiles }
    }

    func signOut() {
        Task { await Cloud.shared.signOutGoogle() }
        account = nil
    }

    // MARK: viewers

    private func defaultViewers() -> [Viewer] {
        [Viewer(id: "main", name: L.isArabic ? "أنا" : "Me", color: 0, updated: 0)]
    }

    private func loadViewers() {
        let list = (Storage.load("viewers|\(activeID)", as: [Viewer].self) ?? defaultViewers()).filter { $0.deleted != true }
        viewers = list.isEmpty ? defaultViewers() : list
        let saved = Storage.string("viewer|\(activeID)") ?? "main"
        viewerID = viewers.contains { $0.id == saved } ? saved : viewers[0].id
    }

    private func saveViewers(_ all: [Viewer]) {
        Storage.save("viewers|\(activeID)", all)
        viewers = all.filter { $0.deleted != true }
    }

    /// After the subscription is chosen: pick a viewer, or go straight in when there is one viewer without a PIN.
    func enterAfterSubscription() {
        loadViewers()
        if viewers.count == 1 && !(viewers[0].hasPIN) {
            chooseViewer(viewers[0])
        } else {
            phase = .viewers
        }
        Task { await cloudPullViewers() }
    }

    func chooseViewer(_ v: Viewer) {
        viewerID = v.id
        Storage.setString("viewer|\(activeID)", v.id)
        byCategoryCache = [:]
        phase = .main
        tab = .home
        Task { await cloudPullData() }
        refreshCatalogIfStale()
    }

    func pinHash(_ pin: String) -> String { Hash.sha256((subKey ?? activeID) + ":" + pin) }

    func checkPIN(_ v: Viewer, _ pin: String) -> Bool { !v.hasPIN || v.pinHash == pinHash(pin) }

    func saveViewer(id: String?, name: String, color: Int, pin: String?, clearPIN: Bool) {
        var all = Storage.load("viewers|\(activeID)", as: [Viewer].self) ?? viewers
        if all.isEmpty { all = defaultViewers() }
        let now = Date().timeIntervalSince1970
        if let id, let i = all.firstIndex(where: { $0.id == id }) {
            all[i].name = name; all[i].color = color; all[i].updated = now
            if clearPIN { all[i].pinHash = nil } else if let pin, pin.count == 4 { all[i].pinHash = pinHash(pin) }
            cloudPush(viewer: all[i])
        } else {
            guard all.filter({ $0.deleted != true }).count < 5 else { return }
            var v = Viewer(id: UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(12).lowercased(), name: name, color: color, updated: now)
            if let pin, pin.count == 4 { v.pinHash = pinHash(pin) }
            all.append(v)
            cloudPush(viewer: v)
        }
        saveViewers(all)
    }

    func deleteViewer(_ v: Viewer) {
        var all = Storage.load("viewers|\(activeID)", as: [Viewer].self) ?? viewers
        guard all.filter({ $0.deleted != true }).count > 1, let i = all.firstIndex(where: { $0.id == v.id }) else { return }
        all[i].deleted = true
        all[i].updated = Date().timeIntervalSince1970
        cloudPush(viewer: all[i])
        let owner = v.id == "main" ? activeID : "\(activeID)#\(v.id)"
        history = history.filter { $0.value.subID != owner }
        favorites.removeAll { $0.subID == owner }
        Storage.save("history", history); Storage.save("favorites", favorites)
        saveViewers(all)
        if viewerID == v.id, let first = viewers.first { viewerID = first.id }
    }

    // MARK: cloud sync (same subscription on any device = same viewers and progress)

    /// Hash of the subscription's own credentials: the private folder all devices with this subscription share.
    var subKey: String? {
        guard let sub = active else { return nil }
        let raw = sub.kind == .xtream ? "\(Xtream.normalize(sub.server).lowercased())|\(sub.username)|\(Keychain.get("pass|\(sub.id)") ?? "")"
                                      : (Keychain.get("m3u|\(sub.id)") ?? "")
        guard raw.count > 3 else { return nil }
        return String(Hash.sha256("nova:" + raw).prefix(40))
    }

    private func cloudPath(_ sub: String) -> String? {
        guard Cloud.shared.enabled, let k = subKey else { return nil }
        return "nova/v1/\(k)/\(sub)"
    }

    private func cloudPush(viewer v: Viewer) {
        guard let p = cloudPath("profiles/\(Cloud.safe(v.id))") else { return }
        let d = CloudCodec.viewer(v)
        Task { await Cloud.shared.put(p, d) }
    }

    private func cloudPush(history h: HistoryItem) {
        guard let p = cloudPath("data/\(Cloud.safe(viewerID))/h/\(CloudCodec.historyKey(h))") else { return }
        let d = CloudCodec.history(h)
        Task { await Cloud.shared.put(p, d) }
    }

    private func cloudPush(fav f: FavItem, deleted: Bool) {
        guard let p = cloudPath("data/\(Cloud.safe(viewerID))/f/\(CloudCodec.favKey(f.kind, f.stream.id))") else { return }
        let d = CloudCodec.fav(f, deleted: deleted)
        Task { await Cloud.shared.put(p, d) }
    }

    /// Viewers: newest change wins on each side; deletions travel as tombstones.
    func cloudPullViewers() async {
        guard let p = cloudPath("profiles") else { return }
        let sid = activeID
        let remote = (await Cloud.shared.get(p) as? [String: Any]) ?? [:]
        guard sid == activeID else { return }
        var all = Storage.load("viewers|\(sid)", as: [Viewer].self) ?? viewers
        var changed = false
        for (id, raw) in remote {
            guard let d = raw as? [String: Any] else { continue }
            let rv = CloudCodec.viewer(id, d)
            if let i = all.firstIndex(where: { $0.id == id }) {
                if rv.updated > all[i].updated { all[i] = rv; changed = true }
                else if rv.updated < all[i].updated { cloudPush(viewer: all[i]) }
            } else {
                all.append(rv); changed = true
            }
        }
        for v in all where remote[v.id] == nil { cloudPush(viewer: v) }
        if changed {
            // "main" created on two devices: keep the newest name/PIN, which the merge above already did.
            saveViewers(all)
            if !viewers.contains(where: { $0.id == viewerID }), let first = viewers.first { viewerID = first.id }
            if phase == .main, viewers.count > 1, currentViewer?.hasPIN == true, Storage.string("viewer|\(sid)") == nil { phase = .viewers }
        }
        cloudStatus = L.t("cloudSynced")
    }

    /// Progress and favorites of the current viewer: newest wins per title, both ways.
    func cloudPullData() async {
        guard let base = cloudPath("data/\(Cloud.safe(viewerID))") else { return }
        let owner = ownerID
        let remote = (await Cloud.shared.get(base) as? [String: Any]) ?? [:]
        guard owner == ownerID else { return }
        let rh = remote["h"] as? [String: Any] ?? [:]
        var seen = Set<String>()
        for (_, raw) in rh {
            guard let d = raw as? [String: Any], let h = CloudCodec.history(d, owner: owner) else { continue }
            let key = HistoryItem.key(owner, h.kind, h.stream.id, h.episodeID)
            seen.insert(CloudCodec.historyKey(h))
            if let local = history[key] {
                if h.updated > local.updated { history[key] = h }
                else if h.updated < local.updated { cloudPush(history: local) }
            } else {
                history[key] = h
            }
        }
        for h in history.values where h.subID == owner && !seen.contains(CloudCodec.historyKey(h)) { cloudPush(history: h) }
        let rf = remote["f"] as? [String: Any] ?? [:]
        var remoteFav = Set<String>()
        for (k, raw) in rf {
            guard let d = raw as? [String: Any], let kind = MediaKind(rawValue: jstr(d["k"])) else { continue }
            remoteFav.insert(k)
            let id = jstr(d["i"])
            let has = favorites.contains { $0.subID == owner && $0.kind == kind && $0.stream.id == id }
            if (d["del"] as? Bool) == true {
                if has { favorites.removeAll { $0.subID == owner && $0.kind == kind && $0.stream.id == id } }
            } else if !has {
                favorites.append(FavItem(subID: owner, kind: kind,
                                         stream: MediaItem(id: id, name: jstr(d["n"]), logo: jstr(d["l"]), category: jstr(d["c"]), ext: jstr(d["x"]), year: jstr(d["y"]))))
            }
        }
        for f in favorites where f.subID == owner && !remoteFav.contains(CloudCodec.favKey(f.kind, f.stream.id)) { cloudPush(fav: f, deleted: false) }
        Storage.save("history", history)
        Storage.save("favorites", favorites)
        byCategoryCache = [:]
        cloudStatus = L.t("cloudSynced")
    }

    func setShowMatches(_ v: Bool) { showMatches = v; Storage.setString("showMatches", v ? "1" : "0") }
}


/// A small deterministic generator: the same seed gives the same "random" order (the hero changes once a day).
struct SeededRandom: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed == 0 ? 0x9E3779B97F4A7C15 : seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
}
