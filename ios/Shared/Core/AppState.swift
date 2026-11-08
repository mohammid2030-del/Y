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
    @Published var catalog = Catalog()
    @Published var favorites: [FavItem] = []
    @Published var history: [String: HistoryItem] = [:]
    @Published var playing: PlayRequest?
    @Published var syncMessage = ""
    @Published var syncError: String?
    @Published var tmdbOn = TMDB.enabledNow
    @Published var showMatches = Storage.string("showMatches") != "0"
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
        let k = "latest|\(kind.rawValue)"
        if let hit = byCategoryCache[k] { return Array(hit.prefix(n)) }
        let list = Array(catalog.items(kind).sorted { $0.added > $1.added }.prefix(60))
        byCategoryCache[k] = list
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
