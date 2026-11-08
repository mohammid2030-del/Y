import SwiftUI

@main
struct NovaApp: App {
    @StateObject private var state = AppState()
    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(state)
                .environment(\.layoutDirection, L.isArabic ? .rightToLeft : .leftToRight)
                .preferredColorScheme(.dark)
                .tint(Theme.blue)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var state: AppState
    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            switch state.phase {
            case .profiles: ProfilesView()
            case .addSubscription(let id): AddSubscriptionView(editingID: id)
            case .syncing(let id): SyncView(subID: id)
            case .main: MainTabs()
            }
        }
        .fullScreenCover(item: $state.playing) { req in
            PlayerScreen(request: req).environmentObject(state)
        }
    }
}

struct MainTabs: View {
    @EnvironmentObject var state: AppState
    var body: some View {
        TabView(selection: $state.tab) {
            NavigationStack { HomeView().navigationDestinations() }
                .tabItem { Label(L.t("home"), systemImage: "house.fill") }.tag(Tab.home)
            NavigationStack { LiveView() }
                .tabItem { Label(L.t("live"), systemImage: "tv") }.tag(Tab.live)
            NavigationStack { CatalogView(kind: .movie).navigationDestinations() }
                .tabItem { Label(L.t("movies"), systemImage: "film") }.tag(Tab.movies)
            NavigationStack { CatalogView(kind: .series).navigationDestinations() }
                .tabItem { Label(L.t("series"), systemImage: "play.rectangle.on.rectangle") }.tag(Tab.series)
            NavigationStack { SearchView().navigationDestinations() }
                .tabItem { Label(L.t("search"), systemImage: "magnifyingglass") }.tag(Tab.search)
            NavigationStack { FavoritesView().navigationDestinations() }
                .tabItem { Label(L.t("favorites"), systemImage: "heart.fill") }.tag(Tab.favorites)
            NavigationStack { SettingsView().navigationDestinations() }
                .tabItem { Label(L.t("settings"), systemImage: "gearshape.fill") }.tag(Tab.settings)
        }
    }
}

extension View {
    func navigationDestinations() -> some View {
        navigationDestination(for: Route.self) { route in
            switch route {
            case .movie(let m): MovieDetailView(item: m)
            case .series(let s): SeriesDetailView(item: s)
            case .category(let k, let c): CategoryGridView(kind: k, category: c)
            case .matchPrefs: MatchPrefsView()
            case .teams(let slug): TeamPickerView(slug: slug)
            }
        }
    }
}

// MARK: - Who's watching

struct ProfilesView: View {
    @EnvironmentObject var state: AppState
    @State private var appear = false
    private let palettes: [[Color]] = [
        [Color(red: 0.31, green: 0.48, blue: 1), Color(red: 0.61, green: 0.42, blue: 1)],
        [Color(red: 1, green: 0.37, blue: 0.54), Color(red: 1, green: 0.62, blue: 0.35)],
        [Color(red: 0.09, green: 0.78, blue: 0.66), Color(red: 0.18, green: 0.55, blue: 1)],
        [Color(red: 1, green: 0.71, blue: 0.28), Color(red: 1, green: 0.37, blue: 0.37)],
        [Color(red: 0.71, green: 0.36, blue: 1), Color(red: 1, green: 0.36, blue: 0.78)]
    ]

    var body: some View {
        ZStack {
            Color(red: 0.02, green: 0.02, blue: 0.035).ignoresSafeArea()
            GlowBackground()
            ScrollView {
                VStack(spacing: Theme.isTV ? 40 : 26) {
                    HStack(spacing: 14) {
                        RoundedRectangle(cornerRadius: 14).fill(Theme.gradient).frame(width: 52, height: 52)
                            .overlay(Image(systemName: "play.fill").font(.title2).foregroundStyle(.white))
                        Text("NOVA").font(.system(size: Theme.isTV ? 48 : 36, weight: .black)).kerning(6)
                    }
                    VStack(spacing: 8) {
                        Text(L.t("who")).font(.system(size: Theme.isTV ? 60 : 36, weight: .heavy))
                        Text(L.t("whoHint")).foregroundStyle(.secondary)
                    }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: Theme.isTV ? 260 : 150), spacing: Theme.isTV ? 40 : 18)],
                              spacing: Theme.isTV ? 40 : 24) {
                        ForEach(Array(state.subs.enumerated()), id: \.element.id) { i, s in
                            Button { state.activate(s.id) } label: { profileTile(s, i) }.cardStyle()
                        }
                        Button { state.phase = .addSubscription(nil) } label: {
                            VStack(spacing: 12) {
                                RoundedRectangle(cornerRadius: 34, style: .continuous)
                                    .strokeBorder(style: StrokeStyle(lineWidth: 3, dash: [10, 8])).foregroundStyle(.white.opacity(0.4))
                                    .frame(width: tile, height: tile)
                                    .overlay(Image(systemName: "plus").font(.system(size: 44, weight: .semibold)))
                                Text(L.t("addSub")).font(.headline).lineLimit(1)
                            }.padding(10)
                        }.cardStyle()
                    }
                    .frame(maxWidth: 1400)
                }
                .padding(.vertical, 60).padding(.horizontal, Theme.pad)
                .opacity(appear ? 1 : 0).offset(y: appear ? 0 : 30)
            }
        }
        .onAppear { withAnimation(.easeOut(duration: 0.7)) { appear = true } }
    }

    private var tile: CGFloat { Theme.isTV ? 210 : 120 }

    private func profileTile(_ s: Subscription, _ i: Int) -> some View {
        let pal = palettes[i % palettes.count]
        return VStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 34, style: .continuous).fill(LinearGradient(colors: pal, startPoint: .topLeading, endPoint: .bottomTrailing))
                RoundedRectangle(cornerRadius: 34, style: .continuous)
                    .fill(RadialGradient(colors: [.white.opacity(0.35), .clear], center: UnitPoint(x: 0.25, y: 0.2), startRadius: 1, endRadius: tile * 0.7))
                Text(String(s.name.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased())
                    .font(.system(size: tile * 0.42, weight: .black)).foregroundStyle(.white)
                Text(s.kind == .xtream ? "XTREAM" : "M3U").font(.system(size: 10, weight: .heavy)).kerning(1.5)
                    .padding(.horizontal, 8).padding(.vertical, 2).background(.black.opacity(0.25), in: Capsule())
                    .frame(maxHeight: .infinity, alignment: .bottom).padding(.bottom, 10).foregroundStyle(.white)
                if s.id == state.activeID {
                    Image(systemName: "checkmark.circle.fill").font(.title2).foregroundStyle(.white, pal[0])
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing).padding(6)
                }
            }
            .frame(width: tile, height: tile)
            Text(s.name).font(.headline).lineLimit(1)
            if let exp = s.expires {
                let days = Calendar.current.dateComponents([.day], from: Date(), to: exp).day ?? 0
                Text(days < 0 ? L.t("expired") : L.t("daysLeft", days)).font(.caption)
                    .foregroundStyle(days < 0 ? Theme.live : days <= 7 ? .orange : .secondary)
            }
        }
        .padding(10)
    }
}

struct GlowBackground: View {
    @State private var move = false
    var body: some View {
        ZStack {
            Circle().fill(RadialGradient(colors: [Theme.violet.opacity(0.45), .clear], center: .center, startRadius: 1, endRadius: 500))
                .frame(width: 1000, height: 1000).offset(x: move ? -150 : -350, y: move ? -250 : -350)
            Circle().fill(RadialGradient(colors: [Theme.blue.opacity(0.35), .clear], center: .center, startRadius: 1, endRadius: 500))
                .frame(width: 1000, height: 1000).offset(x: move ? 350 : 200, y: move ? 300 : 450)
        }
        .ignoresSafeArea()
        .onAppear { withAnimation(.easeInOut(duration: 14).repeatForever(autoreverses: true)) { move = true } }
        .allowsHitTesting(false)
    }
}

// MARK: - Add / sync

struct AddSubscriptionView: View {
    let editingID: String?
    @EnvironmentObject var state: AppState
    @State private var kind: SourceKind = .xtream
    @State private var name = ""
    @State private var server = ""
    @State private var user = ""
    @State private var pass = ""
    @State private var m3u = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("", selection: $kind) {
                        Text(L.t("xtream")).tag(SourceKind.xtream)
                        Text(L.t("m3u")).tag(SourceKind.m3u)
                    }.pickerStyle(.segmented)
                    TextField(L.t("name"), text: $name)
                    if kind == .xtream {
                        TextField(L.t("server"), text: $server).textContentType(.URL).autocorrectionDisabled()
                        TextField(L.t("username"), text: $user).textContentType(.username).autocorrectionDisabled()
                        SecureField(L.t("password"), text: $pass)
                    } else {
                        TextField(L.t("m3uURL"), text: $m3u).textContentType(.URL).autocorrectionDisabled()
                    }
                } footer: { Text(L.t("legal")) }
                Section {
                    Button(L.t("saveLoad")) {
                        state.save(id: editingID, name: name, kind: kind, server: server, user: user, pass: pass, m3u: m3u)
                    }
                    .disabled(kind == .xtream ? (server.isEmpty || user.isEmpty || pass.isEmpty) : m3u.isEmpty)
                    if !state.subs.isEmpty {
                        Button(L.t("cancel"), role: .cancel) { state.phase = .profiles }
                    }
                }
            }
            .navigationTitle(L.t(editingID == nil ? "addSub" : "editSub"))
        }
        .onAppear {
            guard let id = editingID, let s = state.subs.first(where: { $0.id == id }) else { return }
            kind = s.kind; name = s.name; server = s.server; user = s.username
            pass = Keychain.get("pass|\(id)") ?? ""; m3u = Keychain.get("m3u|\(id)") ?? ""
        }
    }
}

struct SyncView: View {
    let subID: String
    @EnvironmentObject var state: AppState
    var body: some View {
        VStack(spacing: 24) {
            RoundedRectangle(cornerRadius: 26).fill(Theme.gradient).frame(width: 96, height: 96)
                .overlay(Image(systemName: "play.fill").font(.largeTitle).foregroundStyle(.white))
            if let err = state.syncError {
                Text(L.t("syncFailed")).font(.title2.bold())
                Text(err).foregroundStyle(Theme.live).multilineTextAlignment(.center)
                HStack(spacing: 16) {
                    Button(L.t("retry")) { Task { await state.sync(subID) } }.buttonStyle(.borderedProminent)
                    Button(L.t("editSub")) { state.phase = .addSubscription(subID) }.buttonStyle(.bordered)
                    if Storage.loadCatalog(for: subID) != nil {
                        Button(L.t("back")) { state.activate(subID) }.buttonStyle(.bordered)
                    } else if state.subs.count > 1 {
                        Button(L.t("back")) { state.phase = .profiles }.buttonStyle(.bordered)
                    }
                }
            } else {
                ProgressView().scaleEffect(1.4)
                Text(state.syncMessage).font(.title3)
            }
        }
        .padding(40)
        .task(id: subID) { await state.sync(subID) }
    }
}
