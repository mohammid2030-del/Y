import SwiftUI

struct HomeView: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: Theme.isTV ? 40 : 26) {
                let feat = state.featured
                if !feat.isEmpty { HeroView(items: feat) }
                if state.showMatches { MatchesStrip() }
                let cont = state.continueWatching
                if !cont.isEmpty {
                    HRow(title: L.t("continueWatching"), accent: true) {
                        ForEach(cont) { h in
                            Button { state.resume(h) } label: { ContinueCard(h: h) }.cardStyle()
                        }
                    }
                }
                let chans = state.recentChannels
                if !chans.isEmpty {
                    HRow(title: L.t("recentChannels")) {
                        ForEach(chans) { c in
                            Button { state.playLive(c, list: chans) } label: { ChannelCard(item: c) }.cardStyle()
                        }
                    }
                }
                posterRow(L.t("latestMovies"), .movie, state.latest(.movie, 20))
                posterRow(L.t("latestSeries"), .series, state.latest(.series, 20))
                let favs = state.favorites.filter { $0.subID == state.activeID && $0.kind != .live }
                if !favs.isEmpty {
                    HRow(title: L.t("favorites")) {
                        ForEach(favs) { f in
                            NavigationLink(value: f.kind == .movie ? Route.movie(f.stream) : Route.series(f.stream)) {
                                PosterCard(item: f.stream, kind: f.kind)
                            }.cardStyle()
                        }
                    }
                }
            }
            .padding(.bottom, 40)
        }
        .ignoresSafeArea(edges: Theme.isTV ? [] : .top)
        #if os(iOS)
        .toolbar(.hidden, for: .navigationBar)
        #endif
    }

    @ViewBuilder
    private func posterRow(_ title: String, _ kind: MediaKind, _ list: [MediaItem]) -> some View {
        if !list.isEmpty {
            HRow(title: title) {
                ForEach(list) { m in
                    NavigationLink(value: kind == .movie ? Route.movie(m) : Route.series(m)) { PosterCard(item: m, kind: kind) }.cardStyle()
                }
            }
        }
    }
}

/// Full-bleed rotating hero with TMDB backdrop, title logo, rating, genres and overview.
struct HeroView: View {
    let items: [(MediaKind, MediaItem)]
    @EnvironmentObject var state: AppState
    @State private var index = 0
    @State private var backdrop = ""
    @State private var logo = ""
    @State private var title = ""
    @State private var meta = ""
    @State private var plot = ""
    private enum HeroField: Hashable { case primary, details, fav }
    #if os(tvOS)
    @FocusState private var heroFocus: HeroField?
    private var userIsHere: Bool { heroFocus != nil }
    #else
    private var userIsHere: Bool { false }
    #endif

    private var height: CGFloat { Theme.isTV ? 720 : 560 }

    var body: some View {
        let (kind, item) = items[min(index, items.count - 1)]
        ZStack(alignment: .bottomLeading) {
            RemoteImage(url: backdrop.isEmpty ? item.logo : backdrop, maxPixel: 1920)
                .frame(maxWidth: .infinity).frame(height: height).clipped()
                .id(backdrop + item.id).transition(.opacity)
            LinearGradient(colors: [.black.opacity(0.55), .clear, Theme.bg.opacity(0.85), Theme.bg],
                           startPoint: .top, endPoint: .bottom)
            LinearGradient(colors: [Theme.bg.opacity(0.85), .clear], startPoint: .leading, endPoint: .trailing)
            VStack(alignment: .leading, spacing: 12) {
                Text("● " + L.t("newBadge") + " · " + L.t(kind == .movie ? "movie" : "seriesOne"))
                    .font(.caption.weight(.semibold)).padding(.horizontal, 12).padding(.vertical, 5)
                    .background(.black.opacity(0.35), in: Capsule()).overlay(Capsule().stroke(.white.opacity(0.2)))
                if !logo.isEmpty {
                    RemoteImage(url: logo, contentMode: .fit, maxPixel: 900).frame(maxWidth: Theme.isTV ? 640 : 340, maxHeight: Theme.isTV ? 160 : 110, alignment: .leading)
                } else {
                    Text(title.isEmpty ? Clean.title(item.name) : title)
                        .font(.system(size: Theme.isTV ? 64 : 38, weight: .heavy)).lineLimit(2).shadow(radius: 12)
                }
                if !meta.isEmpty { Text(meta).font(.subheadline.weight(.semibold)).foregroundStyle(.white.opacity(0.9)) }
                if !plot.isEmpty { Text(plot).font(.callout).foregroundStyle(.white.opacity(0.78)).lineLimit(Theme.isTV ? 3 : 2).frame(maxWidth: 760, alignment: .leading) }
                HStack(spacing: 12) {
                    if kind == .movie {
                        Button { state.playMovie(item) } label: {
                            Label(L.t("play"), systemImage: "play.fill").font(.headline).padding(.horizontal, 22).padding(.vertical, 10)
                        }
                        #if os(tvOS)
                        .buttonStyle(.borderedProminent)
                        .focused($heroFocus, equals: .primary)
                        #else
                        .buttonStyle(.plain).foregroundStyle(.black).background(.white, in: Capsule())
                        #endif
                    }
                    NavigationLink(value: kind == .movie ? Route.movie(item) : Route.series(item)) {
                        Label(kind == .movie ? L.t("details") : L.t("watchNow"), systemImage: "info.circle").font(.headline)
                            .padding(.horizontal, 20).padding(.vertical, 10)
                    }
                    #if os(tvOS)
                    .buttonStyle(.bordered)
                    .focused($heroFocus, equals: .details)
                    #else
                    .buttonStyle(.plain).background(.white.opacity(0.18), in: Capsule()).overlay(Capsule().stroke(.white.opacity(0.3)))
                    #endif
                    Button { state.toggleFavorite(kind, item) } label: {
                        Image(systemName: state.isFavorite(kind, item) ? "checkmark" : "plus").font(.headline).padding(12)
                    }
                    #if os(tvOS)
                    .buttonStyle(.bordered)
                    .focused($heroFocus, equals: .fav)
                    #else
                    .buttonStyle(.plain).background(.white.opacity(0.18), in: Circle())
                    #endif
                }
                HStack(spacing: 6) {
                    ForEach(0..<items.count, id: \.self) { i in
                        Capsule().fill(i == index ? Color.white : Color.white.opacity(0.35)).frame(width: i == index ? 22 : 7, height: 7)
                    }
                }.padding(.top, 6)
            }
            .padding(.horizontal, Theme.isTV ? 80 : 24).padding(.bottom, 30)
            .foregroundStyle(.white)
        }
        .frame(height: height)
        .task(id: index) { await loadInfo(kind, item) }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 8_000_000_000)
                if userIsHere { continue }   // never swap the hero under the user's focus
                withAnimation(.easeInOut(duration: 0.6)) { index = (index + 1) % max(1, items.count) }
            }
        }
        #if os(iOS)
        .gesture(DragGesture(minimumDistance: 30).onEnded { v in
            withAnimation { index = (index + (v.translation.width < 0 ? 1 : items.count - 1)) % items.count }
        })
        #endif
    }

    private func loadInfo(_ kind: MediaKind, _ item: MediaItem) async {
        backdrop = ""; logo = ""; title = ""; plot = item.plot
        meta = [item.rating.isEmpty || item.rating == "0" ? "" : "★ " + String(item.rating.prefix(3)), item.year].filter { !$0.isEmpty }.joined(separator: "   •   ")
        var tmdbID = ""
        if let sub = state.active, sub.kind == .xtream {
            if kind == .movie, let i = await state.xtream(for: sub).vodInfo(item.id) {
                backdrop = i.backdrop; tmdbID = i.tmdb; if plot.isEmpty { plot = i.plot }
            } else if kind == .series, let i = try? await state.xtream(for: sub).seriesInfo(item.id) {
                backdrop = i.backdrop; tmdbID = i.tmdb; if plot.isEmpty { plot = i.plot }
            }
        }
        guard state.tmdbOn, let t = await TMDB.shared.details(kind: kind, tmdbID: tmdbID, name: item.name, year: item.year) else { return }
        withAnimation {
            if !t.backdrop.isEmpty { backdrop = t.backdrop }
            logo = t.logo; title = t.title
            if !t.overview.isEmpty { plot = t.overview }
            meta = [t.rating.isEmpty ? "" : "★ " + t.rating, t.year, t.genres.prefix(2).joined(separator: " · ")].filter { !$0.isEmpty }.joined(separator: "   •   ")
        }
    }
}

// MARK: - Matches

struct MatchesStrip: View {
    @EnvironmentObject var state: AppState
    @State private var day = 0
    @State private var matches: [Match]?
    @State private var choose: (Match, [MediaItem])?
    @State private var showChooser = false
    @State private var message: String?
    @State private var finding: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: "soccerball").font(.headline).foregroundStyle(.white).frame(width: 34, height: 34).background(Theme.gradient, in: Circle())
                Text(L.t("matches")).font(.title3.weight(.bold))
                Spacer()
                Picker("", selection: $day) {
                    Text(L.t("today")).tag(0)
                    Text(L.t("tomorrow")).tag(1)
                }.pickerStyle(.segmented).frame(width: Theme.isTV ? 420 : 200)
                NavigationLink(value: Route.matchPrefs) {
                    Image(systemName: "slider.horizontal.3").font(.headline)
                        #if os(iOS)
                        .padding(9).background(Theme.surface2, in: Circle())
                        #endif
                }
                #if os(tvOS)
                .buttonStyle(.bordered)
                #else
                .buttonStyle(.plain)
                #endif
            }
            .padding(.horizontal, Theme.pad)
            if let list = matches {
                if list.isEmpty {
                    Text(L.t(state.matchPrefs.only && !state.matchPrefs.teams.isEmpty ? "noFavMatches" : "noMatches"))
                        .foregroundStyle(.secondary).padding(.horizontal, Theme.pad).padding(.vertical, 8)
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: Theme.isTV ? 40 : 14) {
                            ForEach(list) { m in
                                Button { open(m) } label: { MatchCard(match: m) }.cardStyle()
                            }
                        }
                        .padding(.horizontal, Theme.pad).padding(.vertical, Theme.isTV ? 20 : 4)
                    }
                }
            } else {
                ProgressView().padding(.horizontal, Theme.pad)
            }
            if let finding {
                HStack(spacing: 10) { ProgressView(); Text(finding).font(.footnote) }.padding(.horizontal, Theme.pad)
            }
            if let message { Text(message).font(.footnote).foregroundStyle(.orange).padding(.horizontal, Theme.pad) }
        }
        .task(id: "\(day)|\(state.matchPrefs.hashValue)") {
            matches = nil
            while !Task.isCancelled {
                let list = await Matches.forDay(day, prefs: state.matchPrefs)
                if Task.isCancelled { return }
                if list != matches { matches = list }
                try? await Task.sleep(nanoseconds: list.contains { $0.isLive } ? 45_000_000_000 : 600_000_000_000)
            }
        }
        .confirmationDialog(L.t("pickChannel"), isPresented: $showChooser, titleVisibility: .visible) {
            if let c = choose {
                ForEach(c.1) { ch in
                    Button(ch.name) {
                        Matches.remember(c.0, subID: state.activeID, channel: ch)
                        state.playLive(ch, list: c.1)
                    }
                }
            }
            Button(L.t("cancel"), role: .cancel) {}
        }
    }

    private func open(_ m: Match) {
        message = nil
        guard m.canOpen else {
            let mins = max(0, Int(m.start.addingTimeInterval(-30 * 60).timeIntervalSinceNow / 60))
            message = L.t("opensIn", mins >= 60 ? "\(mins / 60)h \(mins % 60)m" : "\(mins)m")
            return
        }
        let ranked = Matches.rank(m, channels: state.catalog.live, subID: state.activeID)
        guard !ranked.isEmpty else { message = L.t("noChannel"); return }
        let learned = Storage.string("pick|\(state.activeID)|m|\(m.id)")
        if let first = ranked.first, first.0.id == learned || ranked.count == 1 {
            state.playLive(first.0, list: ranked.map { $0.0 })
            return
        }
        guard let sub = state.active, sub.kind == .xtream else { ask(m, ranked.map { $0.0 }); return }
        finding = L.t("findingChannel")
        Task {
            let boosted = await Matches.epgBoost(m, candidates: ranked, xtream: state.xtream(for: sub))
            finding = nil
            let list = boosted.map { $0.0 }
            if let top = boosted.first, top.2, !(boosted.count > 1 && boosted[1].2 && boosted[1].1 == top.1) {
                // The guide says this channel is showing the match.
                Matches.remember(m, subID: state.activeID, channel: top.0)
                state.playLive(top.0, list: list)
            } else {
                ask(m, list)
            }
        }
    }

    private func ask(_ m: Match, _ list: [MediaItem]) {
        choose = (m, list)
        showChooser = true
    }
}

/// Followed competitions, favourite clubs and national teams.
struct MatchPrefsView: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        List {
            Section {
                Toggle(L.t("onlyFavTeams"), isOn: Binding(get: { state.matchPrefs.only }, set: { v in state.updateMatchPrefs { $0.only = v } }))
            } footer: { Text(L.t("onlyFavHint")) }
            Section(L.t("favTeams")) {
                if state.matchPrefs.teams.isEmpty {
                    Text(L.t("noFavTeams")).foregroundStyle(.secondary)
                }
                ForEach(state.matchPrefs.teams) { t in
                    Button { state.toggleTeam(t) } label: {
                        HStack(spacing: 14) {
                            RemoteImage(url: t.logo, contentMode: .fit, placeholderIcon: "shield.fill", maxPixel: 160).frame(width: 44, height: 44)
                            Text(t.name)
                            Spacer()
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                        }
                    }
                }
            }
            Section(L.t("addTeams")) {
                ForEach(Matches.competitions, id: \.slug) { c in
                    NavigationLink(value: Route.teams(c.slug)) {
                        Label(c.label, systemImage: c.nat ? "flag.fill" : "soccerball")
                    }
                }
            }
            Section(L.t("followedComps")) {
                ForEach(Matches.competitions, id: \.slug) { c in
                    Button { state.toggleComp(c.slug) } label: {
                        HStack {
                            Text(c.label)
                            Spacer()
                            if state.matchPrefs.comps.contains(c.slug) { Image(systemName: "checkmark").foregroundStyle(Theme.blue) }
                        }
                    }
                }
            }
        }
        .navigationTitle(L.t("matchPrefs"))
    }
}

struct TeamPickerView: View {
    let slug: String
    @EnvironmentObject var state: AppState
    @State private var teams: [FavTeam]?
    @State private var query = ""

    var body: some View {
        let list = (teams ?? []).filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) }
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                #if os(iOS)
                TextField(L.t("searchHint"), text: $query).textFieldStyle(.roundedBorder).padding(.horizontal, Theme.pad)
                #endif
                if teams == nil {
                    ProgressView().frame(maxWidth: .infinity).padding(40)
                } else if list.isEmpty {
                    Text(L.t("noResults")).foregroundStyle(.secondary).padding(.horizontal, Theme.pad)
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: Theme.isTV ? 220 : 110), spacing: Theme.isTV ? 36 : 12)], spacing: Theme.isTV ? 40 : 14) {
                        ForEach(list) { t in
                            let on = state.matchPrefs.teams.contains { $0.id == t.id }
                            Button { state.toggleTeam(t) } label: {
                                VStack(spacing: 10) {
                                    RemoteImage(url: t.logo, contentMode: .fit, placeholderIcon: "shield.fill", maxPixel: 240)
                                        .frame(width: Theme.isTV ? 110 : 60, height: Theme.isTV ? 110 : 60)
                                    Text(t.name).font(.caption.weight(.semibold)).lineLimit(2).multilineTextAlignment(.center)
                                }
                                .frame(width: Theme.isTV ? 200 : 100, height: Theme.isTV ? 220 : 120)
                                .padding(8)
                                .background(on ? AnyShapeStyle(Theme.gradient) : AnyShapeStyle(Theme.surface), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                                .overlay(alignment: .topTrailing) {
                                    if on { Image(systemName: "star.fill").foregroundStyle(Theme.gold).padding(10) }
                                }
                                .foregroundStyle(.white)
                            }
                            .cardStyle()
                        }
                    }
                    .padding(.horizontal, Theme.pad)
                }
            }
            .padding(.vertical, 20)
        }
        .navigationTitle(Matches.competition(slug)?.label ?? "")
        .task { if teams == nil { teams = await Matches.teams(slug) } }
    }
}

struct MatchCard: View {
    let match: Match
    private var w: CGFloat { Theme.isTV ? 560 : 300 }

    var body: some View {
        let m = match
        ZStack {
            LinearGradient(colors: [(m.home.color ?? Color(red: 0.16, green: 0.18, blue: 0.35)).opacity(0.75), Color(red: 0.04, green: 0.04, blue: 0.08),
                                    (m.away.color ?? Color(red: 0.29, green: 0.16, blue: 0.35)).opacity(0.75)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            HStack {
                RemoteImage(url: m.home.logo, contentMode: .fit, placeholderIcon: "soccerball").frame(width: w * 0.4).opacity(0.14).rotationEffect(.degrees(-12)).offset(x: -w * 0.12)
                Spacer()
                RemoteImage(url: m.away.logo, contentMode: .fit, placeholderIcon: "soccerball").frame(width: w * 0.4).opacity(0.14).rotationEffect(.degrees(12)).offset(x: w * 0.12)
            }
            Rectangle().fill(.white.opacity(0.07)).frame(width: 1.5)
            Circle().stroke(.white.opacity(0.07), lineWidth: 1.5).frame(width: w * 0.28)
            LinearGradient(colors: [.black.opacity(0.1), .black.opacity(0.55)], startPoint: .top, endPoint: .bottom)
            VStack(spacing: 10) {
                HStack(spacing: 8) {
                    if !m.leagueLogo.isEmpty {
                        RemoteImage(url: m.leagueLogo, contentMode: .fit).frame(width: 20, height: 20).padding(3).background(.white, in: Circle())
                    }
                    Text(m.league).font(.caption).foregroundStyle(.white.opacity(0.8)).lineLimit(1)
                    if m.fav { Image(systemName: "star.fill").font(.caption).foregroundStyle(Theme.gold) }
                    Spacer()
                    status(m)
                }
                HStack {
                    team(m.home)
                    VStack(spacing: 2) {
                        if m.state == .pre || m.state == .postponed {
                            Text(m.start, style: .time).font(.title2.weight(.black))
                            Text("VS").font(.caption2).foregroundStyle(.white.opacity(0.6))
                        } else {
                            Text("\(m.home.score) - \(m.away.score)").font(.system(size: Theme.isTV ? 44 : 30, weight: .black))
                        }
                    }.frame(width: w * 0.28)
                    team(m.away)
                }
                footer(m)
            }
            .padding(14)
        }
        .frame(width: w, height: w * 0.5)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous)
            .stroke(m.isLive ? AnyShapeStyle(LinearGradient(colors: [Theme.live, .orange], startPoint: .leading, endPoint: .trailing)) : AnyShapeStyle(Color.white.opacity(0.14)),
                    lineWidth: m.isLive ? 2 : 1))
        .foregroundStyle(.white)
    }

    private func team(_ t: MatchTeam) -> some View {
        VStack(spacing: 6) {
            RemoteImage(url: t.logo, contentMode: .fit, placeholderIcon: "soccerball").frame(width: w * 0.12, height: w * 0.12).padding(7)
                .background(.white.opacity(0.12), in: Circle()).overlay(Circle().stroke(.white.opacity(0.2)))
            Text(t.short).font(.caption.weight(.semibold)).lineLimit(1)
        }.frame(maxWidth: .infinity)
    }

    @ViewBuilder private func status(_ m: Match) -> some View {
        switch m.state {
        case .live, .halftime:
            Text("● " + (m.state == .halftime ? L.t("matchHT") : L.t("matchLive") + " " + m.clock))
                .font(.caption2.weight(.heavy)).padding(.horizontal, 8).padding(.vertical, 3).background(Theme.live, in: Capsule())
        case .post: Text(L.t("matchFT")).font(.caption2.weight(.bold)).padding(.horizontal, 8).padding(.vertical, 3).background(.white.opacity(0.16), in: Capsule())
        case .postponed: Text(L.t("postponed")).font(.caption2.weight(.bold)).foregroundStyle(.orange)
        case .pre: EmptyView()
        }
    }

    @ViewBuilder private func footer(_ m: Match) -> some View {
        let net = Matches.network(m.slug)
        HStack(spacing: 6) {
            Image(systemName: m.canOpen ? "play.fill" : m.state == .pre ? "lock.fill" : "tv")
            if m.canOpen {
                Text(L.t("watchNow") + (net.isEmpty ? "" : " · " + net))
            } else if m.state == .pre {
                Text((L.isArabic ? "تفتح قبل المباراة بـ٣٠ دقيقة" : "Opens 30 min before kick-off") + (net.isEmpty ? "" : " · " + net))
            } else {
                Text(net)
            }
            Spacer()
        }
        .font(.caption.weight(.semibold)).lineLimit(1)
        .padding(.horizontal, 10).padding(.vertical, 7)
        .background(m.canOpen ? Color.white.opacity(0.16) : Color.black.opacity(0.28), in: RoundedRectangle(cornerRadius: 12))
    }
}
