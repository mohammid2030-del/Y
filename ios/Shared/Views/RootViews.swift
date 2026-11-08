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
            Group {
                switch route {
                case .movie(let m): MovieDetailView(item: m)
                case .series(let s): SeriesDetailView(item: s)
                case .category(let k, let c): CategoryGridView(kind: k, category: c)
                case .matchPrefs: MatchPrefsView()
                case .teams(let slug): TeamPickerView(slug: slug)
                }
            }
            // Inner screens are full-screen: the tab bar only belongs to the top level, and Menu/Back returns here.
            .toolbar(.hidden, for: .tabBar)
        }
    }
}
