import Foundation
import SwiftUI

enum Phase: Equatable {
    case profiles
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
        phase = subs.isEmpty ? .addSubscription(nil) : .profiles
    }

    var active: Subscription? { subs.first { $0.id == activeID } }

    // MARK: subscriptions

    func activate(_ id: String) {
        activeID = id
        Storage.setString("activeSub", id)
        byCategoryCache = [:]
        if let c = Storage.loadCatalog(for: id) {
            catalog = c
            phase = .main
        } else {
            phase = .syncing(id)
        }
    }

    func save(id: String?, name: String, kind: SourceKind, server: String, user: String, pass: String, m3u: String) {
        let sid = id ?? UUID().uuidString
        let srv = kind == .xtream ? Xtream.normalize(server) : ""
        let host = URL(string: kind == .xtream ? srv : m3u)?.host ?? "NOVA"
        let sub = Subscription(id: sid, name: name.isEmpty ? host : name, kind: kind, server: srv, username: user)
        if let i = subs.firstIndex(where: { $0.id == sid }) { subs[i] = sub } else { subs.append(sub) }
        Keychain.set(pass, for: "pass|\(sid)")
        Keychain.set(m3u, for: "m3u|\(sid)")
        Storage.save("subs", subs)
        activeID = sid
        Storage.setString("activeSub", sid)
        phase = .syncing(sid)
    }

    func delete(_ id: String) {
        subs.removeAll { $0.id == id }
        favorites.removeAll { $0.subID == id }
        history = history.filter { $0.value.subID != id }
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
            phase = .main
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

    var featured: [(MediaKind, MediaItem)] {
        let m = latest(.movie, 20).filter { !$0.logo.isEmpty }
        let s = latest(.series, 20).filter { !$0.logo.isEmpty }
        var out: [(MediaKind, MediaItem)] = []
        for i in 0..<6 {
            if i < m.count { out.append((.movie, m[i])) }
            if i < s.count { out.append((.series, s[i])) }
        }
        return Array(out.prefix(8))
    }

    var continueWatching: [HistoryItem] {
        var seen = Set<String>()
        return history.values.filter { $0.subID == activeID && $0.kind != .live && $0.resumable }
            .sorted { $0.updated > $1.updated }
            .filter { seen.insert("\($0.kind.rawValue)|\($0.stream.id)").inserted }   // one card per series
            .prefix(15).map { $0 }
    }

    var recentChannels: [MediaItem] {
        history.values.filter { $0.subID == activeID && $0.kind == .live }.sorted { $0.updated > $1.updated }.prefix(15).map(\.stream)
    }

    // MARK: favorites

    func isFavorite(_ kind: MediaKind, _ item: MediaItem) -> Bool {
        favorites.contains { $0.subID == activeID && $0.kind == kind && $0.stream.id == item.id }
    }

    func toggleFavorite(_ kind: MediaKind, _ item: MediaItem) {
        if let i = favorites.firstIndex(where: { $0.subID == activeID && $0.kind == kind && $0.stream.id == item.id }) {
            favorites.remove(at: i)
        } else {
            favorites.append(FavItem(subID: activeID, kind: kind, stream: item))
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
        let h = history[HistoryItem.key(activeID, .movie, item.id)]
        playing = PlayRequest(kind: .movie, stream: item, title: Clean.title(item.name), url: u,
                              startMs: !fromStart && (h?.resumable ?? false) ? h!.position : 0)
    }

    func playEpisode(_ e: Episode, of series: MediaItem, all: [Episode], fromStart: Bool = false) {
        guard let u = url(.series, series, episode: e) else { return }
        let h = history[HistoryItem.key(activeID, .series, series.id, e.id)]
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
                              startMs: h.position, episode: e)
    }

    func record(kind: MediaKind, item: MediaItem, episode: Episode? = nil, subtitle: String = "", position: Int, duration: Int) {
        let key = HistoryItem.key(activeID, kind, item.id, episode?.id ?? "")
        history[key] = HistoryItem(subID: activeID, kind: kind, stream: item, episodeID: episode?.id ?? "", episodeExt: episode?.ext ?? "",
                                   subtitle: subtitle, position: position, duration: duration, updated: Date())
        Storage.save("history", history)
    }

    func clearHistory() {
        history = history.filter { $0.value.subID != activeID }
        Storage.save("history", history)
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

    /// Every copy of the same title in the library (4K / HD / dubbed / subtitled, other names), best first.
    func variants(_ kind: MediaKind, _ item: MediaItem) -> [MediaItem] {
        guard let idx = index else { return [item] }
        let g = groupKey(kind, item)
        let ck = "\(kind.rawValue)|\(g)|\(TMDBIDs.shared.version)"
        if let hit = variantsCache[ck] { return hit }
        var list: [MediaItem]
        if g.hasPrefix("tmdb:") {
            list = catalog.items(kind).filter { groupKey(kind, $0) == g }
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

    func setShowMatches(_ v: Bool) { showMatches = v; Storage.setString("showMatches", v ? "1" : "0") }
}
