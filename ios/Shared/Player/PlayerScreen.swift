import SwiftUI
import Combine
#if os(tvOS)
import TVVLCKit
#else
import MobileVLCKit
#endif

/// VLC-based playback: HLS, MPEG-TS, MP4, MKV, Dolby / DTS audio, embedded subtitles.
final class PlayerEngine: ObservableObject {
    let player = VLCMediaPlayer()
    @Published var isPlaying = false
    @Published var buffering = true
    @Published var failed = false
    @Published var timeMs = 0
    @Published var lengthMs = 0
    /// Bumped when VLC reports new tracks, so the audio / subtitle menus refresh.
    @Published var tracksVersion = 0

    private var lastTimeMs = -1
    private var lastProgress = Date()
    private var userPaused = false
    private var trackCount = -1

    func open(_ url: URL, startMs: Int, live: Bool) {
        failed = false
        buffering = true
        userPaused = false
        lastTimeMs = -1
        lastProgress = Date()
        trackCount = -1
        let media = VLCMedia(url: url)
        media.addOption(":network-caching=\(live ? 2500 : 4000)")
        media.addOption(":http-user-agent=NOVA-IPTV/1.0")
        media.addOption(":http-reconnect")
        if startMs > 5000 { media.addOption(":start-time=\(startMs / 1000)") }
        player.media = media
        player.play()
    }

    /// Polled from the view (more robust than VLC delegate signatures across versions).
    /// "Buffering" means the clock is not moving while we expect playback, not VLC's noisy buffering state.
    func tick() {
        let t = Int(player.time.intValue)
        let len = Int(player.media?.length.intValue ?? 0)
        if t != timeMs { timeMs = t }
        if len != lengthMs { lengthMs = len }
        let playing = player.isPlaying
        if playing != isPlaying { isPlaying = playing }
        if t != lastTimeMs { lastTimeMs = t; lastProgress = Date() }
        let st = player.state
        if st == .error && !failed { failed = true }
        let stalled = Date().timeIntervalSince(lastProgress) > 1.2
        let b = !failed && !userPaused && st != .paused && st != .stopped && st != .ended && (st == .opening || stalled)
        if b != buffering { buffering = b }
        let n = ((player.audioTrackNames as? [Any])?.count ?? 0) + ((player.videoSubTitlesNames as? [Any])?.count ?? 0)
        if n != trackCount { trackCount = n; tracksVersion += 1 }
    }

    func togglePause() {
        if player.isPlaying { userPaused = true; player.pause() } else { userPaused = false; player.play() }
    }
    func seek(toMs ms: Int) {
        lastProgress = Date()
        player.time = VLCTime(int: Int32(max(0, ms)))
    }
    func seek(toFraction f: Double) {
        guard lengthMs > 0 else { return }
        lastProgress = Date()
        player.position = Float(max(0, min(0.999, f)))
    }

    var audioTracks: [(Int32, String)] {
        let names = player.audioTrackNames as? [String] ?? []
        let ids = player.audioTrackIndexes as? [NSNumber] ?? []
        return zip(ids, names).map { ($0.int32Value, $1) }.filter { $0.0 >= 0 }
    }
    var subtitleTracks: [(Int32, String)] {
        let names = player.videoSubTitlesNames as? [String] ?? []
        let ids = player.videoSubTitlesIndexes as? [NSNumber] ?? []
        return zip(ids, names).map { ($0.int32Value, $1) }
    }
    var currentAudio: Int32 { player.currentAudioTrackIndex }
    var currentSubtitle: Int32 { player.currentVideoSubTitleIndex }
    func setAudio(_ id: Int32) { player.currentAudioTrackIndex = id }
    func setSubtitle(_ id: Int32) { player.currentVideoSubTitleIndex = id }

    func stop() { player.stop() }
}

struct VLCSurface: UIViewRepresentable {
    let player: VLCMediaPlayer
    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.backgroundColor = .black
        player.drawable = v
        return v
    }
    func updateUIView(_ uiView: UIView, context: Context) {}
}

#if os(tvOS)
/// A button that draws nothing extra; focus is shown by the views themselves.
private struct BareButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { configuration.label.contentShape(Rectangle()) }
}

/// Round control that clearly lights up when it has focus.
private struct RoundControl: View {
    let icon: String
    var big = false
    let focused: Bool
    var body: some View {
        Image(systemName: icon)
            .font(.system(size: big ? 44 : 34, weight: .bold))
            .frame(width: big ? 120 : 96, height: big ? 120 : 96)
            .foregroundStyle(focused ? Color.black : Color.white)
            .background(Circle().fill(focused ? Color.white : Color.white.opacity(0.16)))
            .overlay(Circle().stroke(Color.white.opacity(focused ? 0 : 0.35), lineWidth: 2))
            .scaleEffect(focused ? 1.12 : 1)
            .shadow(color: .black.opacity(0.5), radius: focused ? 18 : 0)
            .animation(.easeOut(duration: 0.15), value: focused)
    }
}

private struct PillControl: View {
    let title: String
    let icon: String
    let focused: Bool
    var body: some View {
        Label(title, systemImage: icon)
            .font(.system(size: 26, weight: .semibold))
            .padding(.horizontal, 28).padding(.vertical, 14)
            .foregroundStyle(focused ? Color.black : Color.white)
            .background(Capsule().fill(focused ? Color.white : Color.white.opacity(0.16)))
            .scaleEffect(focused ? 1.08 : 1)
            .animation(.easeOut(duration: 0.15), value: focused)
    }
}
#endif

struct PlayerScreen: View {
    @State var request: PlayRequest
    @EnvironmentObject var state: AppState
    @Environment(\.dismiss) private var dismiss
    @StateObject private var engine = PlayerEngine()
    @State private var controls = true
    @State private var hideTask: Task<Void, Never>?
    @State private var showAudio = false
    @State private var showSubs = false
    @State private var retries = 0
    @State private var pendingSeekMs: Int?
    @State private var seekTask: Task<Void, Never>?
    @State private var lastNudge = Date.distantPast
    @State private var streak = 0
    @State private var lastSave = Date()
    // series: intro / end credits and the next episode (all seasons)
    @State private var segs: AppState.Segs?
    @State private var introSkipped = false
    @State private var allEpisodes: [Episode] = []
    @State private var tmdbID = ""
    @State private var upNextSince: Date?
    @State private var upNextDismissed = false
    @State private var advancing = false
    private enum Field: Hashable { case catcher, back, play, fwd, scrub, audio, subs, next, retry, skip, upNext }
    #if os(tvOS)
    @FocusState private var focus: Field?
    #endif
    private let ticker = Timer.publish(every: 0.5, on: .main, in: .common).autoconnect()

    private var isLive: Bool { request.kind == .live }
    private var shownTime: Int { pendingSeekMs ?? engine.timeMs }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VLCSurface(player: engine.player).ignoresSafeArea()
            if engine.buffering && !engine.failed && pendingSeekMs == nil {
                ProgressView().scaleEffect(1.6).tint(.white)
            }
            if engine.failed { errorBox }
            #if os(tvOS)
            if !controls && !engine.failed {
                // Invisible full-screen target that owns focus while the controls are hidden.
                Button { showControls(.play) } label: { Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity) }
                    .buttonStyle(BareButtonStyle())
                    .focused($focus, equals: .catcher)
                    .onMoveCommand { hiddenMove($0) }
                    .ignoresSafeArea()
            }
            #endif
            if controls && !engine.failed { overlay.transition(.opacity) }
            if !controls, let p = pendingSeekMs { seekBadge(p) }
            if !engine.failed && (introActive || upNextVisible) { seriesCorner }
        }
        .foregroundStyle(.white)
        .onAppear { start(); UIApplication.shared.isIdleTimerDisabled = true; showControls(.play) }
        .onDisappear { save(); engine.stop(); hideTask?.cancel(); seekTask?.cancel(); UIApplication.shared.isIdleTimerDisabled = false }
        .onReceive(ticker) { _ in
            engine.tick()
            seriesTick()
            if engine.failed && isLive && retries < 3 { retries += 1; start() }
            if !isLive, engine.lengthMs > 0, engine.timeMs > 0, Date().timeIntervalSince(lastSave) > 30 { save() }
        }
        #if os(tvOS)
        .onPlayPauseCommand { engine.togglePause(); if controls { bumpControls() } else { showControls(.play) } }
        .onExitCommand {
            if pendingSeekMs != nil { seekTask?.cancel(); pendingSeekMs = nil; return }
            // Back over "Next episode": stay and watch the credits
            if !controls && upNextVisible && !introActive { upNextDismissed = true; focus = .catcher; return }
            if controls { hideControls() } else { close() }
        }
        .onChange(of: focus) { f in if controls && f != nil && f != .catcher { bumpControls() } }
        #else
        .onTapGesture { if controls { hideControls() } else { showControls() } }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        #endif
        .confirmationDialog(L.t("audio"), isPresented: $showAudio, titleVisibility: .visible) {
            let tracks = engine.audioTracks
            if tracks.isEmpty { Button(L.t("noTracks")) {} }
            ForEach(tracks, id: \.0) { t in
                Button((t.0 == engine.currentAudio ? "✓ " : "") + t.1) { engine.setAudio(t.0); bumpControls() }
            }
            Button(L.t("cancel"), role: .cancel) { bumpControls() }
        }
        .confirmationDialog(L.t("subtitles"), isPresented: $showSubs, titleVisibility: .visible) {
            let tracks = engine.subtitleTracks
            if tracks.isEmpty { Button(L.t("noTracks")) {} }
            ForEach(tracks, id: \.0) { t in
                Button((t.0 == engine.currentSubtitle ? "✓ " : "") + (t.0 < 0 ? L.t("off") : t.1)) { engine.setSubtitle(t.0); bumpControls() }
            }
            Button(L.t("cancel"), role: .cancel) { bumpControls() }
        }
    }

    // MARK: pieces

    private var errorBox: some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle.fill").font(.largeTitle).foregroundStyle(.orange)
            Text(L.t("playbackError")).font(.headline)
            HStack {
                Button(L.t("retry")) { start() }.buttonStyle(.borderedProminent)
                    #if os(tvOS)
                    .focused($focus, equals: .retry)
                    #endif
                Button(L.t("close")) { close() }.buttonStyle(.bordered)
            }
        }
        .padding(30).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
    }

    private func seekBadge(_ ms: Int) -> some View {
        VStack {
            Spacer()
            VStack(spacing: 10) {
                Text(fmt(ms) + (engine.lengthMs > 0 ? "  /  " + fmt(engine.lengthMs) : ""))
                    .font(.system(size: Theme.isTV ? 34 : 20, weight: .bold).monospacedDigit())
                if engine.lengthMs > 0 {
                    ProgressView(value: Double(ms), total: Double(max(1, engine.lengthMs))).tint(Theme.blue).frame(width: Theme.isTV ? 900 : 300)
                }
            }
            .padding(.horizontal, 30).padding(.vertical, 18)
            .background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 18))
            .padding(.bottom, Theme.isTV ? 80 : 40)
        }
    }

    private var overlay: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(request.title).font(Theme.isTV ? .title2.bold() : .headline).lineLimit(1)
                    if !request.subtitle.isEmpty { Text(request.subtitle).font(.subheadline).foregroundStyle(.white.opacity(0.75)).lineLimit(1) }
                }
                Spacer()
                if isLive {
                    Text("● " + L.t("liveBadge")).font(.caption.weight(.heavy)).padding(.horizontal, 12).padding(.vertical, 5).background(Theme.live, in: Capsule())
                }
                #if os(iOS)
                Button { close() } label: { Image(systemName: "xmark").font(.title3.weight(.bold)).padding(12).background(.black.opacity(0.4), in: Circle()) }
                    .padding(.leading, 12)
                #endif
            }
            .padding(Theme.isTV ? 60 : 20)
            .background(LinearGradient(colors: [.black.opacity(0.8), .clear], startPoint: .top, endPoint: .bottom))
            Spacer()
            centerButtons
            Spacer()
            VStack(spacing: Theme.isTV ? 28 : 12) {
                if !isLive { scrubber }
                bottomButtons
            }
            .padding(Theme.isTV ? 60 : 20)
            .background(LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom))
        }
    }

    @ViewBuilder private var centerButtons: some View {
        #if os(tvOS)
        HStack(spacing: 70) {
            Button { isLive ? zap(-1) : nudge(-10) } label: {
                RoundControl(icon: isLive ? "backward.end.fill" : "gobackward.10", focused: focus == .back)
            }.buttonStyle(BareButtonStyle()).focused($focus, equals: .back)
            Button { engine.togglePause(); bumpControls() } label: {
                RoundControl(icon: engine.isPlaying ? "pause.fill" : "play.fill", big: true, focused: focus == .play)
            }.buttonStyle(BareButtonStyle()).focused($focus, equals: .play)
            Button { isLive ? zap(1) : nudge(10) } label: {
                RoundControl(icon: isLive ? "forward.end.fill" : "goforward.10", focused: focus == .fwd)
            }.buttonStyle(BareButtonStyle()).focused($focus, equals: .fwd)
        }
        #else
        HStack(spacing: 40) {
            Button { isLive ? zap(-1) : nudge(-10) } label: { Image(systemName: isLive ? "backward.end.fill" : "gobackward.10").font(.title) }
            Button { engine.togglePause(); bumpControls() } label: {
                Image(systemName: engine.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 34)).frame(width: 74, height: 74)
                    .background(.white, in: Circle()).foregroundStyle(.black)
            }
            Button { isLive ? zap(1) : nudge(10) } label: { Image(systemName: isLive ? "forward.end.fill" : "goforward.10").font(.title) }
        }
        .buttonStyle(.plain)
        #endif
    }

    @ViewBuilder private var scrubber: some View {
        let total = max(1, engine.lengthMs)
        let frac = engine.lengthMs > 0 ? Double(shownTime) / Double(total) : 0
        #if os(tvOS)
        let on = focus == .scrub
        Button { engine.togglePause(); bumpControls() } label: {
            HStack(spacing: 18) {
                Text(fmt(shownTime)).font(.system(size: 24, weight: .semibold).monospacedDigit())
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.25))
                        Capsule().fill(Theme.gradient).frame(width: g.size.width * frac)
                        Circle().fill(Color.white).frame(width: on ? 30 : 0, height: on ? 30 : 0)
                            .offset(x: max(0, g.size.width * frac - (on ? 15 : 0)))
                    }
                    .frame(height: on ? 12 : 8)
                    .frame(maxHeight: .infinity)
                }
                .frame(height: 34)
                Text(engine.lengthMs > 0 ? fmt(engine.lengthMs) : "--:--").font(.system(size: 24, weight: .semibold).monospacedDigit())
            }
            .padding(.horizontal, 20).padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 18).fill(on ? Color.white.opacity(0.12) : .clear))
            .overlay(alignment: .top) {
                if on { Text(L.t("scrubHint")).font(.caption).foregroundStyle(.white.opacity(0.7)).offset(y: -34) }
            }
        }
        .buttonStyle(BareButtonStyle())
        .focused($focus, equals: .scrub)
        .onMoveCommand { dir in
            switch dir {
            case .left: nudge(-10)
            case .right: nudge(10)
            default: break
            }
        }
        #else
        HStack(spacing: 12) {
            Text(fmt(shownTime)).font(.caption.monospacedDigit())
            Slider(value: Binding(get: { frac }, set: { engine.seek(toFraction: $0); bumpControls() }))
            Text(fmt(engine.lengthMs)).font(.caption.monospacedDigit())
        }
        #endif
    }

    @ViewBuilder private var bottomButtons: some View {
        #if os(tvOS)
        HStack(spacing: 24) {
            Spacer()
            Button { showAudio = true; bumpControls() } label: { PillControl(title: L.t("audio"), icon: "speaker.wave.2.fill", focused: focus == .audio) }
                .buttonStyle(BareButtonStyle()).focused($focus, equals: .audio)
            Button { showSubs = true; bumpControls() } label: { PillControl(title: L.t("subtitles"), icon: "captions.bubble.fill", focused: focus == .subs) }
                .buttonStyle(BareButtonStyle()).focused($focus, equals: .subs)
            if let next = nextEpisode {
                Button { playEpisode(next) } label: { PillControl(title: L.t("episode", next.num), icon: "forward.fill", focused: focus == .next) }
                    .buttonStyle(BareButtonStyle()).focused($focus, equals: .next)
            }
        }
        #else
        HStack(spacing: 14) {
            Spacer()
            Button { showAudio = true; bumpControls() } label: { Label(L.t("audio"), systemImage: "speaker.wave.2.fill") }
            Button { showSubs = true; bumpControls() } label: { Label(L.t("subtitles"), systemImage: "captions.bubble.fill") }
            if let next = nextEpisode {
                Button { playEpisode(next) } label: { Label(L.t("episode", next.num), systemImage: "forward.fill") }
            }
        }
        .buttonStyle(.bordered)
        #endif
    }

    /// The next episode, across seasons (season 2 episode 1 after season 1's last).
    private var nextEpisode: Episode? {
        let list = allEpisodes.isEmpty ? request.seriesEpisodes : allEpisodes
        guard request.kind == .series, let cur = request.episode,
              let i = list.firstIndex(where: { $0.id == cur.id }), i + 1 < list.count else { return nil }
        return list[i + 1]
    }

    // MARK: skip intro / next episode

    private var introActive: Bool {
        guard let s = segs, s.introEnd > s.introStart, !introSkipped, pendingSeekMs == nil else { return false }
        return engine.timeMs >= s.introStart && engine.timeMs < s.introEnd - 1_500
    }

    /// Where "Next episode" appears: the end credits when known, else 25 s before the end.
    private var creditsAt: Int? {
        let len = engine.lengthMs
        guard len > 120_000 else { return nil }
        if let c = segs?.credits, c > len / 2, c < len - 5_000 { return c }
        return len - 25_000
    }
    private var creditsKnown: Bool { if let c = segs?.credits, c > engine.lengthMs / 2 { return true }; return false }

    private var upNextVisible: Bool {
        guard nextEpisode != nil, !upNextDismissed, let at = creditsAt, pendingSeekMs == nil else { return false }
        return engine.timeMs >= at
    }

    /// Seconds before the next episode starts by itself (10 s over the credits; else the time left).
    private var upNextSeconds: Int {
        if creditsKnown, let since = upNextSince { return max(0, 10 - Int(Date().timeIntervalSince(since))) }
        return max(0, (engine.lengthMs - engine.timeMs) / 1000)
    }

    @ViewBuilder private var seriesCorner: some View {
        VStack {
            Spacer()
            HStack {
                Spacer()
                if introActive {
                    Button { skipIntro() } label: { cornerLabel(L.t("skipIntro"), icon: "forward.end.fill", focused: isFocused(.skip)) }
                        .buttonStyle(BareButtonStyle())
                        #if os(tvOS)
                        .focused($focus, equals: .skip)
                        #endif
                } else if upNextVisible, let n = nextEpisode {
                    Button { playEpisode(n) } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(L.t("nextIn", upNextSeconds)).font(.caption.weight(.semibold)).foregroundStyle(isFocused(.upNext) ? Color.black.opacity(0.7) : Color.white.opacity(0.8))
                            Text("S\(n.season) E\(n.num)" + (n.title.isEmpty ? "" : " — \(n.title)")).font(Theme.isTV ? .title3.bold() : .headline).lineLimit(1)
                        }
                        .padding(.horizontal, Theme.isTV ? 30 : 18).padding(.vertical, Theme.isTV ? 18 : 12)
                        .foregroundStyle(isFocused(.upNext) ? Color.black : Color.white)
                        .background(isFocused(.upNext) ? AnyShapeStyle(Color.white) : AnyShapeStyle(Color.black.opacity(0.7)), in: RoundedRectangle(cornerRadius: 16))
                    }
                    .buttonStyle(BareButtonStyle())
                    #if os(tvOS)
                    .focused($focus, equals: .upNext)
                    #endif
                }
            }
            .padding(.trailing, Theme.isTV ? 80 : 24)
            .padding(.bottom, controls ? (Theme.isTV ? 260 : 150) : (Theme.isTV ? 80 : 40))
        }
    }

    private func isFocused(_ f: Field) -> Bool {
        #if os(tvOS)
        return focus == f
        #else
        return true
        #endif
    }

    private func cornerLabel(_ title: String, icon: String, focused: Bool) -> some View {
        Label(title, systemImage: icon)
            .font(Theme.isTV ? .title3.bold() : .headline)
            .padding(.horizontal, Theme.isTV ? 34 : 20).padding(.vertical, Theme.isTV ? 18 : 12)
            .foregroundStyle(focused ? Color.black : Color.white)
            .background(focused ? AnyShapeStyle(Color.white) : AnyShapeStyle(Color.black.opacity(0.7)), in: Capsule())
    }

    private func skipIntro() {
        guard let s = segs else { return }
        introSkipped = true
        engine.seek(toMs: s.introEnd)
        #if os(tvOS)
        if !controls { focus = .catcher }
        #endif
    }

    /// Loads all episodes (for the next one across seasons) and this episode's intro / credits times.
    private func loadSeriesExtras() {
        guard request.kind == .series, let e = request.episode else { return }
        segs = nil; introSkipped = false; upNextSince = nil; upNextDismissed = false; advancing = false
        let series = request.stream, epID = e.id
        Task {
            if allEpisodes.isEmpty {
                let r = await state.allEpisodes(series)
                allEpisodes = r.episodes; tmdbID = r.tmdb
            }
            // the resumed episode may only know its id: take season / number from the full list
            let full = allEpisodes.first { $0.id == epID } ?? e
            let found = await state.segments(seriesID: series.id, season: full.season, episode: full.num, tmdb: tmdbID)
            if request.episode?.id == epID { segs = found }
        }
    }

    /// Every half second: focus the skip / next buttons when they appear, count down, and move on at the end.
    private func seriesTick() {
        guard request.kind == .series, !advancing else { return }
        #if os(tvOS)
        if !controls {
            if introActive && focus != .skip { focus = .skip }
            else if upNextVisible && !introActive && focus != .upNext { focus = .upNext }
            else if !introActive && !upNextVisible && (focus == .skip || focus == .upNext) { focus = .catcher }
        }
        #endif
        if upNextVisible { if upNextSince == nil { upNextSince = Date() } } else if engine.timeMs < (creditsAt ?? .max) { upNextSince = nil }
        let ended = engine.player.state == .ended
        if let n = nextEpisode, (upNextVisible && creditsKnown && upNextSeconds == 0) || ended {
            advancing = true
            playEpisode(n)
        } else if ended {
            advancing = true
            close()
        }
    }

    private func fmt(_ ms: Int) -> String {
        let s = max(0, ms) / 1000
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }

    // MARK: behaviour

    private func start() {
        engine.open(request.url, startMs: request.startMs, live: isLive)
        loadSeriesExtras()
    }

    #if os(tvOS)
    /// Remote arrows while the controls are hidden: seek (films) or change channel (live).
    private func hiddenMove(_ dir: MoveCommandDirection) {
        switch dir {
        case .left: isLive ? zap(-1) : nudge(-10)
        case .right: isLive ? zap(1) : nudge(10)
        case .up: isLive ? zap(-1) : showControls(.scrub)
        case .down: isLive ? zap(1) : showControls(.play)
        @unknown default: break
        }
    }
    #endif

    private func showControls(_ target: Field = .play) {
        withAnimation(.easeOut(duration: 0.2)) { controls = true }
        #if os(tvOS)
        DispatchQueue.main.async { focus = target }
        #endif
        bumpControls()
    }

    private func hideControls() {
        hideTask?.cancel()
        withAnimation(.easeOut(duration: 0.25)) { controls = false }
        #if os(tvOS)
        DispatchQueue.main.async { focus = .catcher }
        #endif
    }

    /// Keeps the controls up while the user is using them; hides them 6 s after the last action.
    private func bumpControls() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            if Task.isCancelled { return }
            await MainActor.run {
                if showAudio || showSubs || !engine.isPlaying || engine.failed { return }
                hideControls()
            }
        }
    }

    /// Accumulating seek: quick repeated presses jump further and VLC only seeks once you stop.
    private func nudge(_ seconds: Int) {
        guard !isLive else { return }
        let now = Date()
        streak = now.timeIntervalSince(lastNudge) < 0.7 ? streak + 1 : 0
        lastNudge = now
        let step = seconds * (streak >= 8 ? 6 : streak >= 4 ? 3 : 1)
        let base = pendingSeekMs ?? engine.timeMs
        var target = max(0, base + step * 1000)
        if engine.lengthMs > 0 { target = min(engine.lengthMs - 2000, target) }
        pendingSeekMs = target
        if controls { bumpControls() }
        seekTask?.cancel()
        seekTask = Task {
            try? await Task.sleep(nanoseconds: 700_000_000)
            if Task.isCancelled { return }
            await MainActor.run {
                engine.seek(toMs: target)
                pendingSeekMs = nil
            }
        }
    }

    private func zap(_ delta: Int) {
        let list = request.channels
        guard isLive, list.count > 1, let i = list.firstIndex(where: { $0.id == request.stream.id }) else { return }
        let ch = list[(i + delta + list.count) % list.count]
        guard let u = state.url(.live, ch) else { return }
        request.stream = ch; request.title = ch.name; request.url = u
        state.record(kind: .live, item: ch, position: 0, duration: 0)
        retries = 0
        start()
        if controls { bumpControls() } else { showControls(.play) }
    }

    private func playEpisode(_ e: Episode) {
        save()
        guard let u = state.url(.series, request.stream, episode: e) else { return }
        request.episode = e
        request.subtitle = "S\(e.season) E\(e.num)" + (e.title.isEmpty ? "" : " — \(e.title)")
        request.url = u
        request.startMs = 0
        start()
        bumpControls()
    }

    private func save() {
        lastSave = Date()
        guard !isLive, engine.lengthMs > 0, engine.timeMs > 0 else { return }
        // finished: 95 %, into the end credits, or the end
        let len = engine.lengthMs
        let done = engine.timeMs >= len * 95 / 100 || (creditsKnown && engine.timeMs >= (segs?.credits ?? .max)) || engine.player.state == .ended
        state.record(kind: request.kind, item: request.stream, episode: request.episode, subtitle: request.subtitle,
                     position: done ? len : engine.timeMs, duration: len)
        // Continue watching moves on to the next episode (next season's first after a season's last)
        if done, request.kind == .series, let n = nextEpisode { state.recordUpNext(series: request.stream, next: n) }
    }

    private func close() {
        save()
        engine.stop()
        dismiss()
        state.playing = nil
    }
}
