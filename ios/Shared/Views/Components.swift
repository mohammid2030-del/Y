import SwiftUI

enum Theme {
    static let bg = Color(red: 0.027, green: 0.027, blue: 0.043)
    static let surface = Color(red: 0.067, green: 0.067, blue: 0.094)
    static let surface2 = Color(red: 0.10, green: 0.10, blue: 0.14)
    static let blue = Color(red: 0.424, green: 0.482, blue: 1.0)
    static let violet = Color(red: 0.608, green: 0.424, blue: 1.0)
    static let live = Color(red: 0.90, green: 0.22, blue: 0.31)
    static let gold = Color(red: 1.0, green: 0.79, blue: 0.30)
    static let gradient = LinearGradient(colors: [blue, violet], startPoint: .topLeading, endPoint: .bottomTrailing)

    #if os(tvOS)
    static let posterW: CGFloat = 230
    static let wideW: CGFloat = 520
    static let pad: CGFloat = 60
    static let isTV = true
    #else
    static let posterW: CGFloat = 132
    static let wideW: CGFloat = 300
    static let pad: CGFloat = 20
    static let isTV = false
    #endif
}

extension View {
    /// Lift-on-focus card style on Apple TV, plain on iPad/iPhone.
    @ViewBuilder func cardStyle() -> some View {
        #if os(tvOS)
        self.buttonStyle(.card)
        #else
        self.buttonStyle(.plain)
        #endif
    }
}

struct RemoteImage: View {
    let url: String
    var contentMode: ContentMode = .fill
    var placeholderIcon = "film"

    var body: some View {
        if let u = URL(string: url), !url.isEmpty {
            AsyncImage(url: u, transaction: Transaction(animation: .easeOut(duration: 0.25))) { phase in
                switch phase {
                case .success(let img): img.resizable().aspectRatio(contentMode: contentMode)
                case .failure: placeholder
                default: Theme.surface2
                }
            }
        } else {
            placeholder
        }
    }

    private var placeholder: some View {
        ZStack {
            LinearGradient(colors: [Theme.surface2, Theme.surface], startPoint: .top, endPoint: .bottom)
            Image(systemName: placeholderIcon).font(.title).foregroundStyle(.secondary)
        }
    }
}

struct SectionHeader: View {
    let title: String
    var accent: Bool = false
    var body: some View {
        HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 2).fill(accent ? AnyShapeStyle(LinearGradient(colors: [Theme.live, Theme.violet], startPoint: .top, endPoint: .bottom)) : AnyShapeStyle(Theme.gradient))
                .frame(width: 4, height: 22)
            Text(title).font(.title3.weight(.bold))
            Spacer()
        }
        .padding(.horizontal, Theme.pad)
    }
}

/// Poster that swaps to the TMDB poster/title in the app language when a key is set.
struct PosterCard: View {
    let item: MediaItem
    let kind: MediaKind
    @EnvironmentObject var state: AppState
    @State private var card: TMDBCard?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            RemoteImage(url: card?.poster.isEmpty == false ? card!.poster : item.logo)
                .frame(width: Theme.posterW, height: Theme.posterW * 1.5)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(alignment: .topLeading) {
                    if !item.rating.isEmpty && item.rating != "0" {
                        Text("★ " + String(item.rating.prefix(3)))
                            .font(.caption2.weight(.bold)).padding(.horizontal, 7).padding(.vertical, 3)
                            .background(.black.opacity(0.6), in: Capsule()).padding(6)
                    }
                }
                .overlay(alignment: .topTrailing) {
                    if state.isFavorite(kind, item) {
                        Image(systemName: "heart.fill").foregroundStyle(Theme.live).padding(8)
                    }
                }
            Text(card?.title.isEmpty == false ? card!.title : Clean.title(item.name))
                .font(.footnote.weight(.semibold)).lineLimit(1)
            if !item.year.isEmpty {
                Text(item.year).font(.caption2).foregroundStyle(.secondary)
            }
        }
        .frame(width: Theme.posterW, alignment: .leading)
        .task(id: item.id + "\(state.tmdbOn)") {
            guard state.tmdbOn, kind != .live else { card = nil; return }
            card = await TMDB.shared.card(kind: kind, name: item.name, year: item.year)
        }
    }
}

/// Wide 16:9 continue-watching card with "x% watched" and season/episode.
struct ContinueCard: View {
    let h: HistoryItem
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            RemoteImage(url: h.stream.logo).frame(width: Theme.wideW, height: Theme.wideW * 9 / 16).clipped()
            LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 4) {
                if h.kind == .series, let se = Clean.seasonEpisode(h.subtitle) {
                    Text(L.t("seasonEpisode", se.0, se.1)).font(.caption.weight(.semibold)).foregroundStyle(.white.opacity(0.85))
                }
                Text(Clean.title(h.stream.name)).font(.headline).lineLimit(1)
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(.white.opacity(0.25))
                        Capsule().fill(Theme.gradient).frame(width: g.size.width * h.progress)
                    }
                }.frame(height: 4)
            }
            .padding(12)
            Text(L.t("watched", Int((h.progress * 100).rounded()).clamped(1, 99)))
                .font(.caption2.weight(.semibold)).padding(.horizontal, 10).padding(.vertical, 4)
                .background(.black.opacity(0.5), in: Capsule())
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).padding(10)
            Image(systemName: "play.fill").font(.title2).padding(16).background(.white.opacity(0.2), in: Circle())
                .overlay(Circle().stroke(.white.opacity(0.8), lineWidth: 1.5))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(width: Theme.wideW, height: Theme.wideW * 9 / 16)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .foregroundStyle(.white)
    }
}

extension Int {
    func clamped(_ lo: Int, _ hi: Int) -> Int { Swift.min(hi, Swift.max(lo, self)) }
}

struct ChannelCard: View {
    let item: MediaItem
    var body: some View {
        VStack(spacing: 8) {
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(LinearGradient(colors: [Theme.surface2, Theme.surface], startPoint: .topLeading, endPoint: .bottomTrailing))
                RemoteImage(url: item.logo, contentMode: .fit, placeholderIcon: "tv").padding(18)
                Text("● " + L.t("liveBadge")).font(.caption2.weight(.heavy)).foregroundStyle(.white)
                    .padding(.horizontal, 6).padding(.vertical, 2).background(Theme.live, in: RoundedRectangle(cornerRadius: 6)).padding(8)
            }
            .frame(width: Theme.posterW * 1.25, height: Theme.posterW * 0.8)
            Text(Clean.title(item.name)).font(.footnote.weight(.semibold)).lineLimit(1).frame(width: Theme.posterW * 1.25)
        }
    }
}

struct HRow<Content: View>: View {
    let title: String
    var accent = false
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: title, accent: accent)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: Theme.isTV ? 40 : 14) { content() }
                    .padding(.horizontal, Theme.pad).padding(.vertical, Theme.isTV ? 20 : 4)
            }
        }
    }
}

struct EmptyState: View {
    let text: String
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "sparkles.tv").font(.system(size: 44)).foregroundStyle(.secondary)
            Text(text).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }.frame(maxWidth: .infinity, maxHeight: .infinity).padding()
    }
}
