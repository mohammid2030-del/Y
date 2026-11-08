import SwiftUI

/// Movies / Series: category chips, then rows per category (or a grid for one category).
struct CatalogView: View {
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
struct LiveView: View {
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

private struct SearchHit: Identifiable {
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
        .task(id: query) {
            try? await Task.sleep(nanoseconds: 300_000_000)
            let q = query.trimmingCharacters(in: .whitespaces).lowercased()
            guard q.count >= 2 else { results = []; return }
            var out: [SearchHit] = []
            let groups: [(MediaKind, [MediaItem])] = [(.live, state.catalog.live), (.movie, state.catalog.movies), (.series, state.catalog.series)]
            for (k, list) in groups {
                for m in list where m.name.lowercased().contains(q) {
                    out.append(SearchHit(kind: k, item: m))
                    if out.count >= 240 { break }
                }
            }
            results = out
        }
    }
}

struct FavoritesView: View {
    @EnvironmentObject var state: AppState
    var body: some View {
        let mine = state.favorites.filter { $0.subID == state.activeID }
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
                            let ok = await TMDB.shared.setKey(tmdbKey)
                            checking = false
                            tmdbMessage = L.t(ok ? "tmdbSaved" : "tmdbInvalid")
                            if ok { state.tmdbOn = true; tmdbKey = "" }
                        }
                    }.disabled(tmdbKey.count < 16 || checking)
                }
                if let tmdbMessage { Text(tmdbMessage).font(.footnote).foregroundStyle(Theme.blue) }
            } header: { Text(L.t("tmdb")) } footer: { Text(L.t("tmdbAttribution")) }
            Section(L.t("matchesSection")) {
                Toggle(L.t("showMatches"), isOn: Binding(get: { state.showMatches }, set: { state.setShowMatches($0) }))
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
