import SwiftUI

/// Movies / Series: category chips, then rows per category (or a grid for one category).
struct CatalogViewPhone: View {
    let kind: MediaKind
    @EnvironmentObject var state: AppState

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: Theme.isTV ? 36 : 22) {
                let cats = state.catalog.categories(kind)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(cats) { c in
                            NavigationLink(value: Route.category(kind, c)) {
                                Text(c.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                                    .padding(.horizontal, 16).padding(.vertical, 9)
                                    #if os(iOS)
                                    .background(Theme.surface2, in: Capsule())
                                    #endif
                            }
                            #if os(tvOS)
                            .buttonStyle(.bordered)
                            #else
                            .buttonStyle(.plain)
                            #endif
                        }
                    }
                    .padding(.horizontal, Theme.pad).padding(.vertical, Theme.isTV ? 16 : 4)
                }
                let cont = state.continueWatching.filter { $0.kind == kind }
                if !cont.isEmpty {
                    HRow(title: L.t("continueWatching"), accent: true) {
                        ForEach(cont) { h in Button { state.resume(h) } label: { ContinueCard(h: h) }.cardStyle() }
                    }
                }
                let recent = state.latest(kind, 20)
                if !recent.isEmpty {
                    HRow(title: L.t("recentlyAdded")) {
                        ForEach(recent) { m in NavigationLink(value: route(m)) { PosterCard(item: m, kind: kind) }.cardStyle() }
                    }
                }
                ForEach(cats) { c in CategoryRow(kind: kind, category: c) }
                if cats.isEmpty && recent.isEmpty { EmptyState(text: L.t("empty")).frame(height: 400) }
            }
            .padding(.vertical, 20)
        }
        .navigationTitle(L.t(kind == .movie ? "movies" : "series"))
    }

    private func route(_ m: MediaItem) -> Route { kind == .movie ? .movie(m) : .series(m) }
}

private struct CategoryRow: View {
    let kind: MediaKind
    let category: Category
    @EnvironmentObject var state: AppState

    var body: some View {
        let items = state.items(kind, category: category.id)
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    SectionHeader(title: category.name)
                    NavigationLink(value: Route.category(kind, category)) {
                        Text(L.t("all") + " (\(items.count))").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.blue)
                    }.buttonStyle(.plain).padding(.horizontal, Theme.pad)
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: Theme.isTV ? 40 : 14) {
                        ForEach(items.prefix(20)) { m in
                            NavigationLink(value: kind == .movie ? Route.movie(m) : Route.series(m)) { PosterCard(item: m, kind: kind) }.cardStyle()
                        }
                    }
                    .padding(.horizontal, Theme.pad).padding(.vertical, Theme.isTV ? 20 : 4)
                }
            }
        }
    }
}

struct CategoryGridView: View {
    let kind: MediaKind
    let category: Category
    @EnvironmentObject var state: AppState

    var body: some View {
        let items = state.items(kind, category: category.id)
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: Theme.posterW + (Theme.isTV ? 30 : 8)), spacing: Theme.isTV ? 40 : 14)],
                      spacing: Theme.isTV ? 50 : 20) {
                ForEach(items) { m in
                    if kind == .live {
                        Button { state.playLive(m, list: items) } label: { ChannelCard(item: m) }.cardStyle()
                    } else {
                        NavigationLink(value: kind == .movie ? Route.movie(m) : Route.series(m)) { PosterCard(item: m, kind: kind) }.cardStyle()
                    }
                }
            }
            .padding(Theme.pad)
        }
        .navigationTitle(category.name)
    }
}

/// Live TV: categories as chips, channels as a grid of logo tiles. Tapping plays with the category as the zapping list.
struct LiveViewPhone: View {
    @EnvironmentObject var state: AppState
    @State private var selected: String?

    var body: some View {
        let cats = state.catalog.liveCats
        let items = state.items(.live, category: selected)
        VStack(alignment: .leading, spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    chip(L.t("all"), selected == nil) { selected = nil }
                    ForEach(cats) { c in chip(c.name, selected == c.id) { selected = c.id } }
                }
                .padding(.horizontal, Theme.pad).padding(.vertical, Theme.isTV ? 20 : 10)
            }
            if items.isEmpty {
                EmptyState(text: L.t("empty"))
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: Theme.posterW * 1.25 + (Theme.isTV ? 30 : 10)), spacing: Theme.isTV ? 40 : 14)],
                              spacing: Theme.isTV ? 44 : 18) {
                        ForEach(items) { ch in
                            Button { state.playLive(ch, list: items) } label: { ChannelCard(item: ch) }.cardStyle()
                        }
                    }
                    .padding(Theme.pad)
                }
            }
        }
        .navigationTitle(L.t("live"))
    }

    private func chip(_ title: String, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
                .padding(.horizontal, 16).padding(.vertical, 9)
                #if os(iOS)
                .foregroundStyle(on ? .white : .primary)
                .background(on ? AnyShapeStyle(Theme.gradient) : AnyShapeStyle(Theme.surface2), in: Capsule())
                #endif
        }
        #if os(tvOS)
        .buttonStyle(.bordered).tint(on ? Theme.blue : nil)
        #else
        .buttonStyle(.plain)
        #endif
    }
}

private struct SearchHit: Identifiable, Sendable {
    let kind: MediaKind
    let item: MediaItem
    var id: String { kind.rawValue + item.id }
}

struct SearchView: View {
    @EnvironmentObject var state: AppState
    @State private var query = ""
    @State private var results: [SearchHit] = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                TextField(L.t("searchHint"), text: $query)
                    #if os(iOS)
                    .textFieldStyle(.roundedBorder)
                    #endif
                    .autocorrectionDisabled()
                    .padding(.horizontal, Theme.pad)
                if query.trimmingCharacters(in: .whitespaces).count < 2 {
                    Text(L.t("searchPrompt")).foregroundStyle(.secondary).padding(.horizontal, Theme.pad)
                } else if results.isEmpty {
                    Text(L.t("noResults")).foregroundStyle(.secondary).padding(.horizontal, Theme.pad)
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: Theme.posterW + (Theme.isTV ? 30 : 8)), spacing: Theme.isTV ? 40 : 14)],
                              spacing: Theme.isTV ? 50 : 20) {
                        ForEach(results) { r in
                            switch r.kind {
                            case .live:
                                Button { state.playLive(r.item, list: [r.item]) } label: { ChannelCard(item: r.item) }.cardStyle()
                            case .movie:
                                NavigationLink(value: Route.movie(r.item)) { PosterCard(item: r.item, kind: .movie) }.cardStyle()
                            case .series:
                                NavigationLink(value: Route.series(r.item)) { PosterCard(item: r.item, kind: .series) }.cardStyle()
                            }
                        }
                    }
                    .padding(.horizontal, Theme.pad)
                }
            }
            .padding(.vertical, 20)
        }
        .navigationTitle(L.t("search"))
        .onAppear { takePending() }
        .onChange(of: state.pendingSearch) { _ in takePending() }
        .task(id: query) {
            try? await Task.sleep(nanoseconds: 300_000_000)
            let q = query.trimmingCharacters(in: .whitespaces)
            guard q.count >= 2 else { results = []; return }
            let groups: [(MediaKind, [MediaItem])] = [(.live, state.catalog.live), (.movie, state.catalog.movies), (.series, state.catalog.series)]
            // Scan the catalog off the main thread so typing stays smooth.
            let out = await Task.detached(priority: .userInitiated) { () -> [SearchHit] in
                var out: [SearchHit] = []
                for (k, list) in groups {
                    var n = 0
                    for m in list where m.name.range(of: q, options: [.caseInsensitive, .diacriticInsensitive]) != nil {
                        out.append(SearchHit(kind: k, item: m)); n += 1
                        if n >= 120 { break }
                    }
                }
                return out
            }.value
            if !Task.isCancelled { results = out }
        }
    }

    private func takePending() {
        if let p = state.pendingSearch, !p.isEmpty { query = p; state.pendingSearch = nil }
    }
}

struct FavoritesView: View {
    @EnvironmentObject var state: AppState
    var body: some View {
        let mine = state.favorites.filter { $0.subID == state.ownerID }
        Group {
            if mine.isEmpty {
                EmptyState(text: L.t("emptyFav"))
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: Theme.posterW * 1.25 + 10), spacing: Theme.isTV ? 40 : 14)],
                              spacing: Theme.isTV ? 50 : 20) {
                        ForEach(mine) { f in
                            switch f.kind {
                            case .live:
                                Button { state.playLive(f.stream, list: mine.filter { $0.kind == .live }.map(\.stream)) } label: { ChannelCard(item: f.stream) }.cardStyle()
                            case .movie:
                                NavigationLink(value: Route.movie(f.stream)) { PosterCard(item: f.stream, kind: .movie) }.cardStyle()
                            case .series:
                                NavigationLink(value: Route.series(f.stream)) { PosterCard(item: f.stream, kind: .series) }.cardStyle()
                            }
                        }
                    }
                    .padding(Theme.pad)
                }
            }
        }
        .navigationTitle(L.t("favorites"))
    }
}

struct SettingsView: View {
    @EnvironmentObject var state: AppState
    @State private var tmdbKey = ""
    @State private var tmdbMessage: String?
    @State private var checking = false

    var body: some View {
        Form {
            Section(L.t("subscriptions")) {
                ForEach(state.subs) { s in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(s.name).font(.headline)
                            if s.id == state.activeID { Text(L.t("active")).font(.caption.bold()).padding(.horizontal, 8).padding(.vertical, 2).background(Theme.gradient, in: Capsule()) }
                        }
                        HStack(spacing: 12) {
                            if s.id != state.activeID { Button(L.t("use")) { state.activate(s.id) } }
                            Button(L.t("refresh")) { state.phase = .syncing(s.id) }
                            Button(L.t("editSub")) { state.phase = .addSubscription(s.id) }
                            Button(L.t("delete"), role: .destructive) { state.delete(s.id) }
                        }
                        .buttonStyle(.bordered)
                    }
                }
                Button(L.t("addSub")) { state.phase = .addSubscription(nil) }
                Button(L.t("switchProfile")) { state.phase = .profiles }
            }
            if Cloud.shared.googleAvailable {
                Section(L.t("googleAccount")) {
                    if let email = state.account {
                        Text(L.t("signedInAs", email))
                        Button(L.t("syncNow")) { Task { await state.pullAccount() } }
                        Button(L.t("signOut"), role: .destructive) { state.signOut() }
                    } else {
                        Text(L.t("googleHint")).font(.footnote).foregroundStyle(.secondary)
                        Button(L.t("signInGoogle")) { state.phase = .signIn }
                    }
                }
            }
            Section(L.t("viewers")) {
                Button(L.t("switchViewer") + " (" + (state.currentViewer?.name ?? "") + ")") { state.phase = .viewers }
                Text(Cloud.shared.enabled ? L.t("cloudOnHint") : L.t("cloudOffHint")).font(.footnote).foregroundStyle(.secondary)
                if Cloud.shared.enabled {
                    Button(L.t("syncNow")) { Task { await state.cloudPullViewers(); await state.cloudPullData() } }
                    if !state.cloudStatus.isEmpty { Text(state.cloudStatus).font(.footnote).foregroundStyle(Theme.blue) }
                }
            }
            Section {
                if state.tmdbOn {
                    Text(L.t("tmdbOn"))
                    Button(L.t("remove"), role: .destructive) {
                        Task { await TMDB.shared.removeKey(); state.tmdbOn = false }
                    }
                } else {
                    Text(L.t("tmdbOff")).font(.footnote).foregroundStyle(.secondary)
                    TextField(L.t("tmdbKey"), text: $tmdbKey).autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                    Button(checking ? "…" : L.t("save")) {
                        checking = true
                        Task {
                            let entered = tmdbKey
                            let ok = await TMDB.shared.setKey(entered)
                            checking = false
                            tmdbMessage = L.t(ok ? "tmdbSaved" : "tmdbInvalid")
                            if ok { state.tmdbOn = true; tmdbKey = ""; state.pushAccountSettings(tmdbKey: entered.trimmingCharacters(in: .whitespacesAndNewlines)) }
                        }
                    }.disabled(tmdbKey.count < 16 || checking)
                }
                if let tmdbMessage { Text(tmdbMessage).font(.footnote).foregroundStyle(Theme.blue) }
            } header: { Text(L.t("tmdb")) } footer: { Text(L.t("tmdbAttribution")) }
            Section(L.t("matchesSection")) {
                Toggle(L.t("showMatches"), isOn: Binding(get: { state.showMatches }, set: { state.setShowMatches($0) }))
                NavigationLink(value: Route.matchPrefs) { Label(L.t("matchPrefs"), systemImage: "star.circle.fill") }
            }
            Section {
                Button(L.t("clearHistory"), role: .destructive) { state.clearHistory() }
            }
            Section(L.t("about")) {
                Text("NOVA IPTV " + (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") +
                     " (" + (Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "") + ")")
                Text(L.t("aboutDesc")).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle(L.t("settings"))
    }
}


// MARK: - Platform switch

struct CatalogView: View {
    let kind: MediaKind
    var body: some View { CatalogBrowser(kind: kind) }
}

struct LiveView: View {
    var body: some View {
        #if os(tvOS)
        LiveViewTV()
        #else
        LiveViewPhone()
        #endif
    }
}

#if os(tvOS)
/// One row of the left-hand category list on Apple TV.
private struct SideRow: View {
    let title: String
    var icon: String? = nil
    var count: Int? = nil
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                if let icon { Image(systemName: icon).frame(width: 30) }
                Text(title).lineLimit(1)
                Spacer(minLength: 8)
                if let count { Text("\(count)").font(.caption).foregroundStyle(.secondary) }
                if selected { Image(systemName: "checkmark").foregroundStyle(Theme.blue) }
            }
            .font(.system(size: 26, weight: selected ? .bold : .medium))
        }
    }
}

/// Live TV on Apple TV: categories on the left, a fast channel list on the right.
struct LiveViewTV: View {
    @EnvironmentObject var state: AppState
    @State private var selected: String?
    @State private var didPick = false

    var body: some View {
        let cats = state.catalog.liveCats
        let items = state.items(.live, category: selected)
        HStack(alignment: .top, spacing: 30) {
            List {
                SideRow(title: L.t("all"), icon: "square.grid.2x2", count: state.catalog.live.count, selected: selected == nil) { selected = nil }
                ForEach(cats) { c in
                    SideRow(title: c.name, selected: selected == c.id) { selected = c.id }
                }
            }
            .frame(width: 520)
            if items.isEmpty {
                EmptyState(text: L.t("empty"))
            } else {
                List {
                    ForEach(items) { ch in
                        Button { state.playLive(ch, list: items) } label: {
                            HStack(spacing: 20) {
                                RemoteImage(url: ch.logo, contentMode: .fit, placeholderIcon: "tv", maxPixel: 200)
                                    .frame(width: 110, height: 64)
                                    .background(Theme.surface2, in: RoundedRectangle(cornerRadius: 10))
                                if ch.num > 0 { Text("\(ch.num)").font(.callout.monospacedDigit()).foregroundStyle(.secondary).frame(width: 70, alignment: .leading) }
                                Text(ch.name).font(.system(size: 26, weight: .semibold)).lineLimit(1)
                                Spacer()
                                if state.isFavorite(.live, ch) { Image(systemName: "heart.fill").foregroundStyle(Theme.live) }
                            }
                        }
                        .contextMenu {
                            Button(state.isFavorite(.live, ch) ? L.t("removeFav") : L.t("addFav")) { state.toggleFavorite(.live, ch) }
                        }
                    }
                }
                .id(selected ?? "all")
            }
        }
        .padding(.leading, 40)
        .onAppear {
            // Start on the first category instead of thousands of channels.
            if !didPick { didPick = true; if selected == nil, let first = cats.first { selected = first.id } }
        }
    }
}
#endif


// MARK: - Organised library (Movies / Series)

/// A selectable pill (sections, categories, genres).
struct Chip: View {
    let title: String
    var icon: String? = nil
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let icon { Image(systemName: icon) }
                Text(title).lineLimit(1)
                #if os(tvOS)
                if selected { Image(systemName: "checkmark") }
                #endif
            }
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, Theme.isTV ? 10 : 14).padding(.vertical, Theme.isTV ? 4 : 8)
            #if os(iOS)
            .foregroundStyle(selected ? .white : .primary)
            .background(selected ? AnyShapeStyle(Theme.gradient) : AnyShapeStyle(Theme.surface2), in: Capsule())
            #endif
        }
        #if os(tvOS)
        .buttonStyle(.bordered).tint(selected ? Theme.blue : nil)
        #else
        .buttonStyle(.plain)
        #endif
    }
}

/// Movies / Series organised like the big streaming apps: sections (Netflix, Shahid, Prime, Turkish, Korean…),
/// the provider's categories inside each section, genre filters, and one card per title (best quality first).
struct CatalogBrowser: View {
    let kind: MediaKind
    @EnvironmentObject var state: AppState
    @State private var selection = "__recent"
    @State private var category: String?
    @State private var genre: GenreFilter?
    @State private var filtered: [MediaItem]?
    @State private var done = 0
    @State private var total = 0
    @State private var mergeTick = 0

    private var shelves: [Shelf] { state.shelves(kind) }
    private var currentShelf: Shelf? { shelves.first { $0.id == selection } }

    private var baseItems: [MediaItem] {
        switch selection {
        case "__recent": return state.latest(kind, 60)
        case "__cont": return state.continueWatching.filter { $0.kind == kind }.map(\.stream)
        case "__fav": return state.favorites.filter { $0.subID == state.ownerID && $0.kind == kind }.map(\.stream)
        default: return state.browse(kind, shelf: selection, category: category)
        }
    }

    private var title: String {
        switch selection {
        case "__recent": return L.t("recentlyAdded")
        case "__cont": return L.t("continueWatching")
        case "__fav": return L.t("favorites")
        default:
            let shelf = currentShelf?.title ?? ""
            if let category { return shelf + " · " + state.categoryName(kind, category) }
            return shelf
        }
    }

    private var filterKey: String { "\(selection)|\(category ?? "")|\(genre?.rawValue ?? "")|\(state.tmdbOn)|\(state.index == nil)" }

    private func select(_ id: String) {
        selection = id
        category = nil
    }

    var body: some View {
        Group {
            if state.index == nil {
                VStack(spacing: 16) { ProgressView(); Text(L.t("organizing")).foregroundStyle(.secondary) }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                #if os(tvOS)
                HStack(alignment: .top, spacing: 30) {
                    List {
                        SideRow(title: L.t("recentlyAdded"), icon: "sparkles", selected: selection == "__recent") { select("__recent") }
                        if state.continueWatching.contains(where: { $0.kind == kind }) {
                            SideRow(title: L.t("continueWatching"), icon: "play.circle", selected: selection == "__cont") { select("__cont") }
                        }
                        SideRow(title: L.t("favorites"), icon: "heart", selected: selection == "__fav") { select("__fav") }
                        Section(L.t("sections")) {
                            ForEach(shelves) { sh in
                                SideRow(title: sh.title, icon: sh.icon, count: sh.count, selected: selection == sh.id) { select(sh.id) }
                            }
                        }
                    }
                    .frame(width: 440)
                    VStack(alignment: .leading, spacing: 14) {
                        header
                        filters
                        grid
                    }
                }
                .padding(.leading, 40)
                #else
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                Chip(title: L.t("recentlyAdded"), icon: "sparkles", selected: selection == "__recent") { select("__recent") }
                                if state.continueWatching.contains(where: { $0.kind == kind }) {
                                    Chip(title: L.t("continueWatching"), icon: "play.circle", selected: selection == "__cont") { select("__cont") }
                                }
                                Chip(title: L.t("favorites"), icon: "heart", selected: selection == "__fav") { select("__fav") }
                                ForEach(shelves) { sh in Chip(title: sh.title, icon: sh.icon, selected: selection == sh.id) { select(sh.id) } }
                            }.padding(.horizontal, Theme.pad)
                        }
                        header.padding(.horizontal, Theme.pad)
                        filters
                        grid
                    }
                    .padding(.vertical, 12)
                }
                .navigationTitle(L.t(kind == .movie ? "movies" : "series"))
                #endif
            }
        }
        .task(id: filterKey) { await runGenreFilter() }
        .task(id: "merge|\(selection)|\(category ?? "")|\(state.tmdbOn)|\(state.index == nil)") {
            // Identify titles with TMDB in the background so copies with different names merge; refresh a few times.
            guard state.tmdbOn, state.index != nil, selection != "__fav", selection != "__cont" else { return }
            let refresher = Task { @MainActor in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 4_000_000_000)
                    mergeTick += 1
                }
            }
            await state.identify(kind, baseItems)
            refresher.cancel()
            mergeTick += 1
        }
    }

    private var shown: [MediaItem] { _ = mergeTick; return genre == nil ? baseItems : (filtered ?? []) }

    private var header: some View {
        HStack(spacing: 14) {
            Text(title).font(.title3.bold()).lineLimit(1)
            Text("\(shown.count)").foregroundStyle(.secondary)
            if genre != nil && total > 0 && done < total {
                ProgressView()
                Text(L.t("classifying", done, total)).font(.footnote).foregroundStyle(.secondary)
            }
            Spacer()
            if category != nil || genre != nil {
                Button { category = nil; genre = nil } label: { Label(L.t("resetFilters"), systemImage: "arrow.uturn.backward") }
                    #if os(tvOS)
                    .buttonStyle(.bordered)
                    #else
                    .buttonStyle(.bordered).controlSize(.small)
                    #endif
            }
        }
    }

    @ViewBuilder private var filters: some View {
        if let sh = currentShelf, sh.categories.count > 1 {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Theme.isTV ? 16 : 8) {
                    Chip(title: L.t("all"), selected: category == nil) { category = nil }
                    ForEach(sh.categories) { c in Chip(title: c.name, selected: category == c.id) { category = c.id } }
                }
                .padding(.horizontal, Theme.isTV ? 10 : Theme.pad).padding(.vertical, Theme.isTV ? 14 : 2)
            }
        }
        if state.tmdbOn {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Theme.isTV ? 16 : 8) {
                    Chip(title: L.t("allGenres"), icon: "line.3.horizontal.decrease.circle", selected: genre == nil) { genre = nil }
                    ForEach(GenreFilter.available(kind)) { g in Chip(title: g.label, selected: genre == g) { genre = g } }
                }
                .padding(.horizontal, Theme.isTV ? 10 : Theme.pad).padding(.vertical, Theme.isTV ? 14 : 2)
            }
        } else {
            Text(L.t("genresNeedTMDB")).font(.footnote).foregroundStyle(.secondary).padding(.horizontal, Theme.isTV ? 10 : Theme.pad)
        }
    }

    @ViewBuilder private var grid: some View {
        let list = shown
        if list.isEmpty {
            if genre != nil && done < total {
                ProgressView().frame(maxWidth: .infinity).padding(40)
            } else {
                EmptyState(text: L.t(selection == "__fav" ? "emptyFav" : "empty")).frame(minHeight: 300)
            }
        } else {
            #if os(tvOS)
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.fixed(Theme.posterW), spacing: 44), count: 5), spacing: 56) {
                    ForEach(list) { m in
                        NavigationLink(value: kind == .movie ? Route.movie(m) : Route.series(m)) { PosterCard(item: m, kind: kind) }
                            .buttonStyle(.card)
                    }
                }
                .padding(.vertical, 30).padding(.horizontal, 20)
            }
            .id(filterKey)
            #else
            LazyVGrid(columns: [GridItem(.adaptive(minimum: Theme.posterW + 8), spacing: 14)], spacing: 20) {
                ForEach(list) { m in
                    NavigationLink(value: kind == .movie ? Route.movie(m) : Route.series(m)) { PosterCard(item: m, kind: kind) }.buttonStyle(.plain)
                }
            }
            .padding(.horizontal, Theme.pad)
            #endif
        }
    }

    /// Genre filter: categories that name the genre count at once; other titles are checked against TMDB
    /// (cached after the first time), and the grid fills in as results arrive.
    private func runGenreFilter() async {
        guard let g = genre, state.tmdbOn, state.index != nil else { filtered = nil; done = 0; total = 0; return }
        let base = baseItems
        let wanted = g.ids(kind)
        var out: [MediaItem] = []
        filtered = []
        done = 0
        total = base.count
        var i = 0
        while i < base.count {
            if Task.isCancelled { return }
            let chunk = Array(base[i..<min(base.count, i + 18)])
            var quick = Set<Int>()
            for (j, m) in chunk.enumerated() where g.matchesCategory(state.categoryName(kind, m.category)) { quick.insert(j) }
            let found = await withTaskGroup(of: (Int, [Int]).self) { group -> [Int: [Int]] in
                for (j, m) in chunk.enumerated() where !quick.contains(j) {
                    group.addTask { (j, await TMDB.shared.genres(kind: kind, name: m.name, year: m.year)) }
                }
                var d: [Int: [Int]] = [:]
                for await (j, gs) in group { d[j] = gs }
                return d
            }
            if Task.isCancelled { return }
            for (j, m) in chunk.enumerated() where quick.contains(j) || !Set(found[j] ?? []).isDisjoint(with: wanted) { out.append(m) }
            i += chunk.count
            filtered = out
            done = i
        }
    }
}
