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
            #if os(iOS)
            ZStack(alignment: .bottom) {
                RemoteImage(url: backdrop.isEmpty ? poster : backdrop, maxPixel: 1920)
                    .frame(maxWidth: .infinity).frame(height: 300).clipped()
                    .blur(radius: backdrop.isEmpty ? 24 : 0)
                LinearGradient(colors: [.black.opacity(0.35), .clear, Theme.bg], startPoint: .top, endPoint: .bottom)
            }
            #endif
            HStack(alignment: .top, spacing: Theme.isTV ? 50 : 18) {
                #if os(tvOS)
                // Apple TV: the backdrop fills the screen behind the page; poster, info and buttons fit on the first screen.
                RemoteImage(url: poster, maxPixel: 900).frame(width: 260, height: 390)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).shadow(radius: 16)
                #else
                RemoteImage(url: poster, maxPixel: 900).frame(width: 120, height: 180)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).shadow(radius: 16)
                    .offset(y: -70)
                    .padding(.bottom, -70)
                #endif
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
            .padding(.top, Theme.isTV ? 40 : 0)
            if let tag = tmdb?.tagline, !tag.isEmpty {
                Text("“\(tag)”").italic().foregroundStyle(Theme.blue).padding(.horizontal, Theme.pad).padding(.top, 14)
            }
        }
    }
}

/// Copies of the same title, Arabic first (dubbed, subtitled, Arabic), then by quality.
@MainActor
enum Versions {
    static func ordered(_ kind: MediaKind, _ list: [MediaItem], _ state: AppState) -> [MediaItem] {
        list.map { m -> (MediaItem, Int, Int) in
            (m, CatalogIndex.language(name: m.name, category: state.categoryName(kind, m.category)).rank, CatalogIndex.qualityScore(m.name))
        }
        .sorted { $0.1 != $1.1 ? $0.1 < $1.1 : $0.2 > $1.2 }
        .map { $0.0 }
    }

    static func label(_ kind: MediaKind, _ m: MediaItem, _ state: AppState) -> String {
        let cat = state.categoryName(kind, m.category)
        let lang = CatalogIndex.language(name: m.name, category: cat).label
        let q = ["", "HD", "FHD", "4K"][min(3, CatalogIndex.qualityScore(m.name))]
        return [lang, q, cat].filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

/// Asks which copy to open every time the page is entered (Arabic copies listed first and pre-selected).
struct VersionChooser: ViewModifier {
    let kind: MediaKind
    let variants: [MediaItem]
    @Binding var pick: MediaItem?
    @Binding var show: Bool
    @EnvironmentObject var state: AppState
    @State private var asked = false

    func body(content: Content) -> some View {
        let ordered = Versions.ordered(kind, variants, state)
        content
            .onAppear {
                guard !asked, ordered.count > 1 else { return }
                asked = true
                if pick == nil { pick = ordered.first }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { show = true }
            }
            .confirmationDialog(L.t("chooseVersion"), isPresented: $show, titleVisibility: .visible) {
                ForEach(ordered) { v in
                    Button((v.id == pick?.id ? "✓ " : "") + Versions.label(kind, v, state)) { pick = v }
                }
                Button(L.t("cancel"), role: .cancel) {}
            } message: {
                Text(L.t("chooseVersionHint"))
            }
    }
}

/// Full-screen backdrop behind a detail page on Apple TV (stays still while the page scrolls).
struct DetailBackdrop: View {
    let url: String
    var body: some View {
        #if os(tvOS)
        ZStack {
            Theme.bg
            if !url.isEmpty {
                RemoteImage(url: url, maxPixel: 1920).frame(maxWidth: .infinity, maxHeight: .infinity).clipped().opacity(0.55)
            }
            LinearGradient(colors: [Theme.bg.opacity(0.95), Theme.bg.opacity(0.75), Theme.bg.opacity(0.35)], startPoint: .leading, endPoint: .trailing)
            LinearGradient(colors: [.clear, Theme.bg], startPoint: .center, endPoint: .bottom)
        }
        .ignoresSafeArea()
        #else
        Theme.bg.ignoresSafeArea()
        #endif
    }
}

struct CastRow: View {
    let cast: [TMDBPerson]
    @EnvironmentObject var state: AppState
    @State private var person: TMDBPerson?
    @State private var target: (MediaKind, MediaItem)?
    @State private var go = false
    var body: some View {
        if !cast.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: L.t("cast"))
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: Theme.isTV ? 24 : 16) {
                        ForEach(cast, id: \.self) { p in
                            Button { person = p } label: {
                                VStack(spacing: 6) {
                                    RemoteImage(url: p.photo, placeholderIcon: "person.fill", maxPixel: 300)
                                        .frame(width: Theme.isTV ? 130 : 76, height: Theme.isTV ? 130 : 76).clipShape(Circle())
                                    Text(p.name).font(.caption.weight(.semibold)).lineLimit(2).multilineTextAlignment(.center)
                                    Text(p.role).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                                }
                                .frame(width: Theme.isTV ? 160 : 90)
                                .foregroundStyle(.white)
                            }
                            .buttonStyle(LiftButtonStyle(radius: 20))
                        }
                    }
                    .padding(.horizontal, Theme.pad).padding(.vertical, Theme.isTV ? 24 : 4)
                }
            }
            .sheet(item: $person) { p in
                PersonSheet(person: p) { kind, item in
                    // Close the sheet, then open the title in this same screen stack (Back returns here).
                    person = nil
                    target = (kind, item)
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { go = true }
                }
                .environmentObject(state)
            }
            .navigationDestination(isPresented: $go) {
                Group {
                    if let t = target {
                        if t.0 == .movie { MovieDetailView(item: t.1) } else { SeriesDetailView(item: t.1) }
                    }
                }
                .toolbar(.hidden, for: .tabBar)
            }
        }
    }
}

extension TMDBPerson: Identifiable {}

/// Cast member: photo, role, biography and best-known titles.
struct PersonSheet: View {
    let person: TMDBPerson
    var open: (MediaKind, MediaItem) -> Void = { _, _ in }
    @State private var info: TMDBPersonInfo?
    @State private var bioOpen = false
    @State private var notice: String?
    @State private var available: [Int: (MediaKind, MediaItem)] = [:]
    @EnvironmentObject var state: AppState
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack(alignment: .top, spacing: 30) {
                    RemoteImage(url: person.photo, placeholderIcon: "person.fill", maxPixel: 500)
                        .frame(width: Theme.isTV ? 260 : 120, height: Theme.isTV ? 260 : 120).clipShape(Circle())
                    VStack(alignment: .leading, spacing: 10) {
                        Text(person.name).font(Theme.isTV ? .largeTitle.bold() : .title2.bold())
                        if !person.role.isEmpty { Text(person.role).foregroundStyle(Theme.blue) }
                        if let i = info, !(i.born + i.place).isEmpty {
                            Text([i.born, i.place].filter { !$0.isEmpty }.joined(separator: " · ")).font(.footnote).foregroundStyle(.secondary)
                        }
                        #if os(iOS)
                        Button(L.t("close")) { dismiss() }.buttonStyle(.bordered).padding(.top, 6)
                        #endif
                    }
                    Spacer(minLength: 0)
                }
                if info == nil { ProgressView().frame(maxWidth: .infinity) }
                if let bio = info?.bio, !bio.isEmpty {
                    Button { bioOpen.toggle() } label: {
                        Text(bio).font(.callout).foregroundStyle(.white.opacity(0.85)).lineLimit(bioOpen ? nil : 6)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }.buttonStyle(LiftButtonStyle(radius: 14))
                }
                if let known = info?.knownFor, !known.isEmpty {
                    Text(L.t("knownFor")).font(.title3.bold())
                    if let notice { Text(notice).font(.footnote).foregroundStyle(.orange) }
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: Theme.isTV ? 30 : 12) {
                            ForEach(known) { w in
                                let found = available[w.id]
                                Button {
                                    if let f = found { open(f.0, f.1) } else { notice = L.t("notInSub", w.title) }
                                } label: {
                                    VStack(alignment: .leading, spacing: 6) {
                                        RemoteImage(url: w.poster).frame(width: Theme.posterW * 0.8, height: Theme.posterW * 1.2)
                                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                            .overlay(alignment: .topTrailing) {
                                                if found != nil {
                                                    Image(systemName: "play.circle.fill").font(.title2).foregroundStyle(.white, Theme.blue).padding(6)
                                                }
                                            }
                                            .opacity(found == nil ? 0.55 : 1)
                                        Text(w.title).font(.caption).lineLimit(1).frame(width: Theme.posterW * 0.8, alignment: .leading)
                                    }.foregroundStyle(.white)
                                }.buttonStyle(LiftButtonStyle(radius: 14))
                            }
                        }.padding(.vertical, 20)
                    }
                }
            }
            .padding(Theme.isTV ? 80 : 20)
        }
        .background(Theme.bg.ignoresSafeArea())
        .task {
            let i = await TMDB.shared.person(person.id) ?? TMDBPersonInfo()
            info = i
            for w in i.knownFor { if let f = await state.findInCatalog(w) { available[w.id] = f } }
        }
    }
}

/// Overview as a focusable block; selecting it shows the full text.
struct PlotBlock: View {
    let text: String
    var lines = 4
    @State private var full = false
    var body: some View {
        Button { full = true } label: {
            Text(text).font(.body).foregroundStyle(.white.opacity(0.85)).lineLimit(lines)
                .multilineTextAlignment(.leading).frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(LiftButtonStyle(radius: 14))
        .padding(.horizontal, Theme.pad - (Theme.isTV ? 10 : 0))
        .sheet(isPresented: $full) {
            ScrollView {
                Text(text).font(Theme.isTV ? .title3 : .body).padding(Theme.isTV ? 80 : 24).frame(maxWidth: .infinity, alignment: .leading)
            }.background(Theme.bg.ignoresSafeArea())
        }
    }
}

struct MovieDetailView: View {
    let item: MediaItem
    @EnvironmentObject var state: AppState
    @State private var info: VodInfo?
    @State private var tmdb: TMDBInfo?
    @State private var trailer: String?
    @State private var pick: MediaItem?
    @State private var showVersions = false
    private var shown: MediaItem { pick ?? item }
    #if os(tvOS)
    @FocusState private var playFocused: Bool
    @State private var didFocus = false
    #endif

    var body: some View {
        let h = state.history[HistoryItem.key(state.ownerID, .movie, shown.id)]
        let canResume = h?.resumable ?? false
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                DetailHeader(item: shown, backdrop: tmdb?.backdrop.isEmpty == false ? tmdb!.backdrop : (info?.backdrop ?? ""),
                             poster: tmdb?.poster.isEmpty == false ? tmdb!.poster : (info?.image.isEmpty == false ? info!.image : shown.logo),
                             tmdb: tmdb,
                             meta: [tmdb?.year ?? shown.year, (tmdb?.runtime ?? 0) > 0 ? L.t("minutes", tmdb!.runtime) : (info?.duration ?? ""),
                                    tmdb == nil ? (info?.genre ?? "") : ""]) {
                    actionButtons(canResume: canResume)
                }
                if canResume, let h {
                    ProgressView(value: h.progress).tint(Theme.blue).padding(.horizontal, Theme.pad)
                }
                let plot = tmdb?.overview.isEmpty == false ? tmdb!.overview : (info?.plot.isEmpty == false ? info!.plot : shown.plot)
                if !plot.isEmpty {
                    PlotBlock(text: plot)
                }
                if let cast = tmdb?.cast, !cast.isEmpty { CastRow(cast: cast) }
                else if let c = info?.cast, !c.isEmpty { Text(L.t("cast") + ": " + c).font(.footnote).foregroundStyle(.secondary).padding(.horizontal, Theme.pad) }
                let crew = tmdb?.creators.joined(separator: "، ") ?? info?.director ?? ""
                if !crew.isEmpty { Text(L.t("director") + ": " + crew).font(.footnote).foregroundStyle(.secondary).padding(.horizontal, Theme.pad) }
            }
            .padding(.bottom, 40)
        }
        .ignoresSafeArea(edges: Theme.isTV ? [] : .top)
        .background(DetailBackdrop(url: tmdb?.backdrop.isEmpty == false ? tmdb!.backdrop : (info?.backdrop ?? "")))
        .modifier(VersionChooser(kind: .movie, variants: state.variants(.movie, item), pick: $pick, show: $showVersions))
        #if os(tvOS)
        .onAppear {
            // Always start on Play, so the remote works immediately.
            guard !didFocus else { return }
            didFocus = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { playFocused = true }
        }
        #endif
        .task(id: shown.id) {
            info = nil
            if let sub = state.active, sub.kind == .xtream { info = await state.xtream(for: sub).vodInfo(shown.id) }
            if state.tmdbOn { tmdb = await TMDB.shared.details(kind: .movie, tmdbID: info?.tmdb ?? "", name: shown.name, year: shown.year) }
        }
        #if os(iOS)
        .sheet(item: Binding(get: { trailer.flatMap { $0.isEmpty ? nil : TrailerID(key: $0) } }, set: { trailer = $0?.key })) { t in TrailerSheet(key: t.key) }
        #else
        .alert(L.t("youtubeMissing"), isPresented: Binding(get: { trailer != nil }, set: { if !$0 { trailer = nil } })) {
            Button(L.t("ok")) { trailer = nil }
        }
        #endif
    }

    @ViewBuilder private func actionButtons(canResume: Bool) -> some View {
        HStack(spacing: 12) {
            Button { state.playMovie(shown) } label: { Label(L.t(canResume ? "resume" : "play"), systemImage: "play.fill") }
                .buttonStyle(.borderedProminent)
                #if os(tvOS)
                .focused($playFocused)
                #endif
            if canResume {
                Button { state.playMovie(shown, fromStart: true) } label: { Label(L.t("startOver"), systemImage: "arrow.counterclockwise") }
                    .buttonStyle(.bordered)
            }
            // YouTube app: TMDB trailer, or a YouTube search when TMDB has none (Arabic / Turkish titles)
            Button { TrailerLauncher.open(key: tmdb?.trailer, query: TrailerLauncher.query(tmdb, shown)) { trailer = $0 ?? "" } } label: {
                Label(L.t("trailer"), systemImage: "film")
            }.buttonStyle(.bordered)
            if state.variants(.movie, item).count > 1 {
                Button { showVersions = true } label: { Label(Versions.label(.movie, shown, state), systemImage: "rectangle.stack") }
                    .buttonStyle(.bordered).lineLimit(1)
            }
            Button { state.toggleFavorite(.movie, shown) } label: {
                Image(systemName: state.isFavorite(.movie, shown) ? "heart.fill" : "heart")
            }.buttonStyle(.bordered).tint(state.isFavorite(.movie, shown) ? Theme.live : nil)
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
    @State private var pick: MediaItem?
    @State private var showVersions = false
    private var shown: MediaItem { pick ?? item }
    private enum SField: Hashable { case resume, firstEpisode, season }
    #if os(tvOS)
    @FocusState private var sFocus: SField?
    @State private var didFocus = false
    #endif

    var body: some View {
        let seasons = info.map { $0.seasons.keys.sorted() } ?? []
        let episodes = info?.seasons[season] ?? []
        let last = state.history.values.filter { $0.subID == state.ownerID && $0.kind == .series && $0.stream.id == shown.id }
            .max { $0.updated < $1.updated }
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                DetailHeader(item: shown, backdrop: tmdb?.backdrop.isEmpty == false ? tmdb!.backdrop : (info?.backdrop ?? ""),
                             poster: tmdb?.poster.isEmpty == false ? tmdb!.poster : (info?.cover.isEmpty == false ? info!.cover : shown.logo),
                             tmdb: tmdb,
                             meta: [tmdb?.year ?? shown.year, (tmdb?.seasons ?? 0) > 0 ? L.t("seasonsCount", tmdb!.seasons) : "",
                                    tmdb == nil ? (info?.genre ?? "") : ""]) {
                    HStack(spacing: 12) {
                        if let last {
                            Button { state.resume(last) } label: {
                                Label(L.t("resume") + " " + (last.subtitle.components(separatedBy: " —").first ?? ""), systemImage: "play.fill")
                            }.buttonStyle(.borderedProminent)
                            #if os(tvOS)
                            .focused($sFocus, equals: .resume)
                            #endif
                        }
                        Button { TrailerLauncher.open(key: tmdb?.trailer, query: TrailerLauncher.query(tmdb, shown)) { trailer = $0 ?? "" } } label: {
                            Label(L.t("trailer"), systemImage: "film")
                        }.buttonStyle(.bordered)
                        if state.variants(.series, item).count > 1 {
                            Button { showVersions = true } label: { Label(Versions.label(.series, shown, state), systemImage: "rectangle.stack") }
                                .buttonStyle(.bordered).lineLimit(1)
                        }
                        Button { state.toggleFavorite(.series, shown) } label: {
                            Image(systemName: state.isFavorite(.series, shown) ? "heart.fill" : "heart")
                        }.buttonStyle(.bordered).tint(state.isFavorite(.series, shown) ? Theme.live : nil)
                    }
                }
                let plot = tmdb?.overview.isEmpty == false ? tmdb!.overview : (info?.plot.isEmpty == false ? info!.plot : shown.plot)
                if !plot.isEmpty { PlotBlock(text: plot) }
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
                        let isFirst = e.id == episodes.first?.id
                        Button { state.playEpisode(e, of: shown, all: episodes) } label: {
                            EpisodeRow(episode: e, tm: tmEpisodes[e.num], fallback: shown.logo,
                                       progress: state.history[HistoryItem.key(state.ownerID, .series, shown.id, e.id)]?.progress)
                        }
                        #if os(tvOS)
                        .buttonStyle(.card)
                        .modifier(FirstEpisodeFocus(focus: $sFocus, on: isFirst))
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
        .background(DetailBackdrop(url: tmdb?.backdrop.isEmpty == false ? tmdb!.backdrop : (info?.backdrop ?? "")))
        .modifier(VersionChooser(kind: .series, variants: state.variants(.series, item), pick: $pick, show: $showVersions))
        #if os(tvOS)
        .onChange(of: info?.seasons.count ?? -1) { _ in focusStart() }
        .onAppear { focusStart() }
        #endif
        .task(id: shown.id) {
            info = nil; error = nil
            guard let sub = state.active, sub.kind == .xtream else { error = L.t("noEpisodes"); return }
            do {
                let i = try await state.xtream(for: sub).seriesInfo(shown.id)
                info = i
                if season < 0 || i.seasons[season] == nil { season = i.seasons.keys.sorted().first ?? -1 }
                if state.tmdbOn { tmdb = await TMDB.shared.details(kind: .series, tmdbID: i.tmdb, name: shown.name, year: shown.year) }
            } catch { self.error = error.localizedDescription }
        }
        .task(id: "\(season)|\(tmdb?.id ?? 0)") {
            guard let id = tmdb?.id, season >= 0 else { tmEpisodes = [:]; return }
            tmEpisodes = await TMDB.shared.season(tvID: id, season: season)
        }
        #if os(iOS)
        .sheet(item: Binding(get: { trailer.flatMap { $0.isEmpty ? nil : TrailerID(key: $0) } }, set: { trailer = $0?.key })) { t in TrailerSheet(key: t.key) }
        #else
        .alert(L.t("youtubeMissing"), isPresented: Binding(get: { trailer != nil }, set: { if !$0 { trailer = nil } })) {
            Button(L.t("ok")) { trailer = nil }
        }
        #endif
    }

    #if os(tvOS)
    private struct FirstEpisodeFocus: ViewModifier {
        var focus: FocusState<SField?>.Binding
        let on: Bool
        @ViewBuilder func body(content: Content) -> some View {
            if on { content.focused(focus, equals: .firstEpisode) } else { content }
        }
    }

    /// Start on "Resume", or on the first episode once the list has loaded, so the remote works immediately.
    private func focusStart() {
        guard !didFocus else { return }
        let hasResume = state.history.values.contains { $0.subID == state.ownerID && $0.kind == .series && $0.stream.id == shown.id }
        guard hasResume || !(info?.seasons.isEmpty ?? true) else { return }
        didFocus = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { sFocus = hasResume ? .resume : .firstEpisode }
    }
    #endif
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
    /// YouTube's official embedded player on NOVA's GitHub Pages page (a real web origin, which YouTube requires).
    static let page = "https://mohammid2030-del.github.io/Y/trailer.html"
    func makeCoordinator() -> Coordinator { Coordinator(key: key) }
    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        let web = WKWebView(frame: .zero, configuration: cfg)
        web.isOpaque = false
        web.backgroundColor = .black
        web.navigationDelegate = context.coordinator
        let safe = key.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
        if let url = URL(string: "\(Self.page)?v=\(safe)&hl=\(L.isArabic ? "ar" : "en")") { web.load(URLRequest(url: url)) }
        return web
    }
    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate {
        let key: String
        private var fellBack = false
        init(key: String) { self.key = key }
        /// Page not reachable: embed directly instead.
        private func fallback(_ web: WKWebView) {
            guard !fellBack else { return }
            fellBack = true
            let safe = key.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
            let html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;height:100%;background:#000}
            iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style></head><body>
            <iframe src="https://www.youtube-nocookie.com/embed/\(safe)?autoplay=1&playsinline=1&rel=0" allow="autoplay; encrypted-media; fullscreen" allowfullscreen></iframe>
            </body></html>
            """
            web.loadHTMLString(html, baseURL: URL(string: "https://nova-iptv.app/"))
        }
        func webView(_ web: WKWebView, decidePolicyFor response: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            if response.isForMainFrame, let h = response.response as? HTTPURLResponse, h.statusCode >= 400 {
                decisionHandler(.cancel); fallback(web); return
            }
            decisionHandler(.allow)
        }
        func webView(_ web: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { fallback(web) }
    }
}
#endif
