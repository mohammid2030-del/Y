import SwiftUI
#if os(iOS)
import WebKit
#endif

/// Shared header: backdrop, poster, title/logo, rating, genres, actions.
private struct DetailHeader<Actions: View>: View {
    let item: MediaItem
    let backdrop: String
    let poster: String
    let tmdb: TMDBInfo?
    let meta: [String]
    @ViewBuilder let actions: () -> Actions

    var body: some View {
        let title = tmdb?.title.isEmpty == false ? tmdb!.title : Clean.title(item.name)
        let rating = tmdb?.rating.isEmpty == false ? tmdb!.rating : item.rating
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .bottom) {
                RemoteImage(url: backdrop.isEmpty ? poster : backdrop, maxPixel: 1920)
                    .frame(maxWidth: .infinity).frame(height: Theme.isTV ? 620 : 300).clipped()
                    .blur(radius: backdrop.isEmpty ? 24 : 0)
                LinearGradient(colors: [.black.opacity(0.35), .clear, Theme.bg], startPoint: .top, endPoint: .bottom)
            }
            HStack(alignment: .top, spacing: Theme.isTV ? 50 : 18) {
                RemoteImage(url: poster, maxPixel: 900).frame(width: Theme.isTV ? 300 : 120, height: Theme.isTV ? 450 : 180)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).shadow(radius: 16)
                    .offset(y: Theme.isTV ? -160 : -70)
                    .padding(.bottom, Theme.isTV ? -160 : -70)
                VStack(alignment: .leading, spacing: 10) {
                    if let logo = tmdb?.logo, !logo.isEmpty {
                        RemoteImage(url: logo, contentMode: .fit).frame(maxWidth: Theme.isTV ? 600 : 300, maxHeight: Theme.isTV ? 140 : 70, alignment: .leading)
                        Text(title).font(.subheadline).foregroundStyle(.secondary)
                    } else {
                        Text(title).font(Theme.isTV ? .largeTitle.bold() : .title2.bold())
                        if let o = tmdb?.original, !o.isEmpty, o != title { Text(o).font(.subheadline).foregroundStyle(.secondary) }
                    }
                    HStack(spacing: 10) {
                        if !rating.isEmpty && rating != "0" {
                            Text("★ " + String(rating.prefix(3))).font(.caption.weight(.bold)).padding(.horizontal, 8).padding(.vertical, 3)
                                .background(Theme.gradient, in: RoundedRectangle(cornerRadius: 6))
                        }
                        if let v = tmdb?.votes, v > 0 { Text("TMDB · " + L.t("votes", v)).font(.caption).foregroundStyle(.secondary) }
                        Text(meta.filter { !$0.isEmpty }.joined(separator: " • ")).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    if let g = tmdb?.genres, !g.isEmpty {
                        HStack(spacing: 6) {
                            ForEach(g.prefix(4), id: \.self) { Text($0).font(.caption).padding(.horizontal, 10).padding(.vertical, 4).background(Theme.surface2, in: Capsule()) }
                        }
                    }
                    actions().padding(.top, 6)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, Theme.pad)
            if let tag = tmdb?.tagline, !tag.isEmpty {
                Text("“\(tag)”").italic().foregroundStyle(Theme.blue).padding(.horizontal, Theme.pad).padding(.top, 14)
            }
        }
    }
}

struct CastRow: View {
    let cast: [TMDBPerson]
    var body: some View {
        if !cast.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: L.t("cast"))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 16) {
                        ForEach(cast, id: \.self) { p in
                            VStack(spacing: 6) {
                                RemoteImage(url: p.photo, placeholderIcon: "person.fill").frame(width: Theme.isTV ? 130 : 76, height: Theme.isTV ? 130 : 76).clipShape(Circle())
                                Text(p.name).font(.caption.weight(.semibold)).lineLimit(2).multilineTextAlignment(.center)
                                Text(p.role).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                            }.frame(width: Theme.isTV ? 160 : 90)
                            .tvFocusable(radius: 18)
                        }
                    }.padding(.horizontal, Theme.pad)
                }
            }
        }
    }
}

struct MovieDetailView: View {
    let item: MediaItem
    @EnvironmentObject var state: AppState
    @State private var info: VodInfo?
    @State private var tmdb: TMDBInfo?
    @State private var trailer: String?

    var body: some View {
        let h = state.history[HistoryItem.key(state.activeID, .movie, item.id)]
        let canResume = h?.resumable ?? false
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                DetailHeader(item: item, backdrop: tmdb?.backdrop.isEmpty == false ? tmdb!.backdrop : (info?.backdrop ?? ""),
                             poster: tmdb?.poster.isEmpty == false ? tmdb!.poster : (info?.image.isEmpty == false ? info!.image : item.logo),
                             tmdb: tmdb,
                             meta: [tmdb?.year ?? item.year, (tmdb?.runtime ?? 0) > 0 ? L.t("minutes", tmdb!.runtime) : (info?.duration ?? ""),
                                    tmdb == nil ? (info?.genre ?? "") : ""]) {
                    actionButtons(canResume: canResume)
                }
                if canResume, let h {
                    ProgressView(value: h.progress).tint(Theme.blue).padding(.horizontal, Theme.pad)
                }
                let plot = tmdb?.overview.isEmpty == false ? tmdb!.overview : (info?.plot.isEmpty == false ? info!.plot : item.plot)
                if !plot.isEmpty {
                    Text(plot).font(.body).foregroundStyle(.white.opacity(0.85)).frame(maxWidth: .infinity, alignment: .leading)
                        .tvFocusable(radius: 14).padding(.horizontal, Theme.pad - (Theme.isTV ? 8 : 0))
                }
                if let cast = tmdb?.cast, !cast.isEmpty { CastRow(cast: cast) }
                else if let c = info?.cast, !c.isEmpty { Text(L.t("cast") + ": " + c).font(.footnote).foregroundStyle(.secondary).padding(.horizontal, Theme.pad) }
                let crew = tmdb?.creators.joined(separator: "، ") ?? info?.director ?? ""
                if !crew.isEmpty {
                    Text(L.t("director") + ": " + crew).font(.footnote).foregroundStyle(.secondary)
                        .tvFocusable(radius: 12).padding(.horizontal, Theme.pad - (Theme.isTV ? 8 : 0))
                }
            }
            .padding(.bottom, 40)
        }
        .ignoresSafeArea(edges: Theme.isTV ? [] : .top)
        .task {
            if let sub = state.active, sub.kind == .xtream { info = await state.xtream(for: sub).vodInfo(item.id) }
            if state.tmdbOn { tmdb = await TMDB.shared.details(kind: .movie, tmdbID: info?.tmdb ?? "", name: item.name, year: item.year) }
        }
        #if os(iOS)
        .sheet(item: Binding(get: { trailer.map { TrailerID(key: $0) } }, set: { trailer = $0?.key })) { t in TrailerSheet(key: t.key) }
        #endif
    }

    @ViewBuilder private func actionButtons(canResume: Bool) -> some View {
        HStack(spacing: 12) {
            Button { state.playMovie(item) } label: { Label(L.t(canResume ? "resume" : "play"), systemImage: "play.fill") }
                .buttonStyle(.borderedProminent)
            if canResume {
                Button { state.playMovie(item, fromStart: true) } label: { Label(L.t("startOver"), systemImage: "arrow.counterclockwise") }
                    .buttonStyle(.bordered)
            }
            #if os(iOS)
            if let key = tmdb?.trailer, !key.isEmpty {
                Button { trailer = key } label: { Label(L.t("trailer"), systemImage: "film") }.buttonStyle(.bordered)
            }
            #endif
            Button { state.toggleFavorite(.movie, item) } label: {
                Image(systemName: state.isFavorite(.movie, item) ? "heart.fill" : "heart")
            }.buttonStyle(.bordered).tint(state.isFavorite(.movie, item) ? Theme.live : nil)
        }
    }
}

struct SeriesDetailView: View {
    let item: MediaItem
    @EnvironmentObject var state: AppState
    @State private var info: SeriesInfo?
    @State private var error: String?
    @State private var season = -1
    @State private var tmdb: TMDBInfo?
    @State private var tmEpisodes: [Int: TMDBEpisode] = [:]
    @State private var trailer: String?

    var body: some View {
        let seasons = info.map { $0.seasons.keys.sorted() } ?? []
        let episodes = info?.seasons[season] ?? []
        let last = state.history.values.filter { $0.subID == state.activeID && $0.kind == .series && $0.stream.id == item.id }
            .max { $0.updated < $1.updated }
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 18) {
                DetailHeader(item: item, backdrop: tmdb?.backdrop.isEmpty == false ? tmdb!.backdrop : (info?.backdrop ?? ""),
                             poster: tmdb?.poster.isEmpty == false ? tmdb!.poster : (info?.cover.isEmpty == false ? info!.cover : item.logo),
                             tmdb: tmdb,
                             meta: [tmdb?.year ?? item.year, (tmdb?.seasons ?? 0) > 0 ? L.t("seasonsCount", tmdb!.seasons) : "",
                                    tmdb == nil ? (info?.genre ?? "") : ""]) {
                    HStack(spacing: 12) {
                        if let last {
                            Button { state.resume(last) } label: {
                                Label(L.t("resume") + " " + (last.subtitle.components(separatedBy: " —").first ?? ""), systemImage: "play.fill")
                            }.buttonStyle(.borderedProminent)
                        }
                        #if os(iOS)
                        if let key = tmdb?.trailer, !key.isEmpty {
                            Button { trailer = key } label: { Label(L.t("trailer"), systemImage: "film") }.buttonStyle(.bordered)
                        }
                        #endif
                        Button { state.toggleFavorite(.series, item) } label: {
                            Image(systemName: state.isFavorite(.series, item) ? "heart.fill" : "heart")
                        }.buttonStyle(.bordered).tint(state.isFavorite(.series, item) ? Theme.live : nil)
                    }
                }
                let plot = tmdb?.overview.isEmpty == false ? tmdb!.overview : (info?.plot.isEmpty == false ? info!.plot : item.plot)
                if !plot.isEmpty {
                    Text(plot).foregroundStyle(.white.opacity(0.85)).lineLimit(5).frame(maxWidth: .infinity, alignment: .leading)
                        .tvFocusable(radius: 14).padding(.horizontal, Theme.pad - (Theme.isTV ? 8 : 0))
                }
                if let cast = tmdb?.cast, !cast.isEmpty { CastRow(cast: cast) }
                if let error {
                    Text(error).foregroundStyle(Theme.live).padding(.horizontal, Theme.pad)
                } else if info == nil {
                    ProgressView().frame(maxWidth: .infinity).padding()
                } else if seasons.isEmpty {
                    Text(L.t("noEpisodes")).foregroundStyle(.secondary).padding(.horizontal, Theme.pad)
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(seasons, id: \.self) { s in
                                Button { season = s } label: {
                                    Text(L.t("season", s)).font(.subheadline.weight(.semibold)).padding(.horizontal, 14).padding(.vertical, 8)
                                        #if os(iOS)
                                        .foregroundStyle(s == season ? .white : .primary)
                                        .background(s == season ? AnyShapeStyle(Theme.gradient) : AnyShapeStyle(Theme.surface2), in: Capsule())
                                        #endif
                                }
                                #if os(tvOS)
                                .buttonStyle(.bordered).tint(s == season ? Theme.blue : nil)
                                #else
                                .buttonStyle(.plain)
                                #endif
                            }
                        }.padding(.horizontal, Theme.pad).padding(.vertical, 6)
                    }
                    ForEach(episodes) { e in
                        Button { state.playEpisode(e, of: item, all: episodes) } label: {
                            EpisodeRow(episode: e, tm: tmEpisodes[e.num], fallback: item.logo,
                                       progress: state.history[HistoryItem.key(state.activeID, .series, item.id, e.id)]?.progress)
                        }
                        #if os(tvOS)
                        .buttonStyle(.card)
                        #else
                        .buttonStyle(.plain)
                        #endif
                        .padding(.horizontal, Theme.pad)
                    }
                }
            }
            .padding(.bottom, 40)
        }
        .ignoresSafeArea(edges: Theme.isTV ? [] : .top)
        .task {
            guard let sub = state.active, sub.kind == .xtream else { error = L.t("noEpisodes"); return }
            do {
                let i = try await state.xtream(for: sub).seriesInfo(item.id)
                info = i
                if season < 0 || i.seasons[season] == nil { season = i.seasons.keys.sorted().first ?? -1 }
                if state.tmdbOn { tmdb = await TMDB.shared.details(kind: .series, tmdbID: i.tmdb, name: item.name, year: item.year) }
            } catch { self.error = error.localizedDescription }
        }
        .task(id: "\(season)|\(tmdb?.id ?? 0)") {
            guard let id = tmdb?.id, season >= 0 else { tmEpisodes = [:]; return }
            tmEpisodes = await TMDB.shared.season(tvID: id, season: season)
        }
        #if os(iOS)
        .sheet(item: Binding(get: { trailer.map { TrailerID(key: $0) } }, set: { trailer = $0?.key })) { t in TrailerSheet(key: t.key) }
        #endif
    }
}

private struct EpisodeRow: View {
    let episode: Episode
    let tm: TMDBEpisode?
    let fallback: String
    let progress: Double?

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            ZStack(alignment: .bottom) {
                RemoteImage(url: tm?.still.isEmpty == false ? tm!.still : (episode.image.isEmpty ? fallback : episode.image))
                    .frame(width: Theme.isTV ? 320 : 150, height: Theme.isTV ? 180 : 84).clipped()
                Image(systemName: "play.fill").padding(10).background(.black.opacity(0.45), in: Circle())
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                if let p = progress, p > 0 {
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Rectangle().fill(.white.opacity(0.25))
                            Rectangle().fill(Theme.blue).frame(width: g.size.width * p)
                        }
                    }.frame(height: 4)
                }
            }
            .frame(width: Theme.isTV ? 320 : 150, height: Theme.isTV ? 180 : 84)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            VStack(alignment: .leading, spacing: 4) {
                let name = tm?.name.isEmpty == false ? tm!.name : (episode.title.isEmpty ? L.t("episode", episode.num) : episode.title)
                Text("\(episode.num). \(name)").font(.subheadline.weight(.semibold)).lineLimit(2)
                if episode.duration > 0 { Text(L.t("minutes", episode.duration / 60)).font(.caption).foregroundStyle(.secondary) }
                let plot = tm?.overview.isEmpty == false ? tm!.overview : episode.plot
                if !plot.isEmpty { Text(plot).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
            }
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .foregroundStyle(.white)
    }
}

#if os(iOS)
struct TrailerID: Identifiable { let key: String; var id: String { key } }

/// YouTube's embedded player inside the app.
struct TrailerSheet: View {
    let key: String
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            YouTubeView(key: key).ignoresSafeArea()
            Button { dismiss() } label: { Image(systemName: "xmark.circle.fill").font(.title).foregroundStyle(.white) }.padding()
        }
    }
}

struct YouTubeView: UIViewRepresentable {
    let key: String
    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        let web = WKWebView(frame: .zero, configuration: cfg)
        web.isOpaque = false
        web.backgroundColor = .black
        let safe = key.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
        let html = """
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;height:100%;background:#000}
        iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style></head><body>
        <iframe src="https://www.youtube-nocookie.com/embed/\(safe)?autoplay=1&playsinline=1&rel=0" allow="autoplay; encrypted-media; fullscreen" allowfullscreen></iframe>
        </body></html>
        """
        web.loadHTMLString(html, baseURL: URL(string: "https://nova-iptv.app/"))
        return web
    }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
#endif
