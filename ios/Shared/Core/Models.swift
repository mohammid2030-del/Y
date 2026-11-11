import Foundation

enum SourceKind: String, Codable { case xtream, m3u }

enum MediaKind: String, Codable, Hashable { case live, movie, series }

struct Subscription: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var kind: SourceKind
    var server: String = ""
    var username: String = ""
    var lastSync: Date?
    var expires: Date?
    /// Last change (for Google-account sync of subscriptions).
    var updated: Double? = nil
}

struct Category: Codable, Identifiable, Hashable {
    var id: String
    var name: String
}

/// A channel, movie or series as listed by the provider.
struct MediaItem: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var logo: String = ""
    var category: String = ""
    var ext: String = ""
    var num: Int = 0
    var rating: String = ""
    var year: String = ""
    var added: Int = 0
    var plot: String = ""
    /// Direct URL (M3U sources only).
    var url: String = ""
    /// TMDB id when the provider lists one (lets copies with different names merge into one card).
    var tmdb: String? = nil
}

struct Catalog: Codable {
    var live: [MediaItem] = []
    var movies: [MediaItem] = []
    var series: [MediaItem] = []
    var liveCats: [Category] = []
    var movieCats: [Category] = []
    var seriesCats: [Category] = []

    func items(_ kind: MediaKind) -> [MediaItem] {
        switch kind { case .live: return live; case .movie: return movies; case .series: return series }
    }
    func categories(_ kind: MediaKind) -> [Category] {
        switch kind { case .live: return liveCats; case .movie: return movieCats; case .series: return seriesCats }
    }
}

struct Episode: Codable, Identifiable, Hashable {
    var id: String
    var season: Int
    var num: Int
    var title: String
    var ext: String
    var plot: String
    var image: String
    var duration: Int
}

struct SeriesInfo {
    var plot = "", cover = "", backdrop = "", genre = "", rating = "", year = "", tmdb = ""
    var seasons: [Int: [Episode]] = [:]
}

struct VodInfo {
    var plot = "", genre = "", duration = "", year = "", rating = "", cast = "", director = "", image = "", backdrop = "", tmdb = ""
}

struct FavItem: Codable, Hashable, Identifiable {
    var subID: String
    var kind: MediaKind
    var stream: MediaItem
    var id: String { "\(subID)|\(kind.rawValue)|\(stream.id)" }
}

struct HistoryItem: Codable, Hashable, Identifiable {
    var subID: String
    var kind: MediaKind
    var stream: MediaItem
    var episodeID: String = ""
    var episodeExt: String = ""
    var subtitle: String = ""
    var position: Int = 0   // ms
    var duration: Int = 0   // ms
    var updated: Date = Date()
    var id: String { HistoryItem.key(subID, kind, stream.id, episodeID) }

    static func key(_ sub: String, _ kind: MediaKind, _ id: String, _ ep: String = "") -> String { "\(sub)|\(kind.rawValue)|\(id)|\(ep)" }

    var progress: Double { duration > 0 ? min(1, max(0, Double(position) / Double(duration))) : 0 }
    var resumable: Bool { position > 30_000 && (duration == 0 || Double(position) < Double(duration) * 0.98) }
    /// "Next episode, from the start" in Continue watching (position 1 ms — the same mark on every platform).
    var upNext: Bool { position == 1 }
}

/// What the player should open.
struct PlayRequest: Identifiable {
    let id = UUID()
    var kind: MediaKind
    var stream: MediaItem
    var title: String
    var subtitle: String = ""
    var url: URL
    var startMs: Int = 0
    var episode: Episode? = nil
    var seriesEpisodes: [Episode] = []
    var channels: [MediaItem] = []
}

enum Route: Hashable {
    case movie(MediaItem)
    case series(MediaItem)
    case category(MediaKind, Category)
    case matchPrefs
    case match(Match)
    case teams(String)
}
