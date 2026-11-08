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

    func open(_ url: URL, startMs: Int, live: Bool) {
        failed = false
        buffering = true
        let media = VLCMedia(url: url)
        media.addOption(":network-caching=\(live ? 2500 : 4000)")
        media.addOption(":http-user-agent=NOVA-IPTV/1.0")
        media.addOption(":http-reconnect")
        if startMs > 5000 { media.addOption(":start-time=\(startMs / 1000)") }
        player.media = media
        player.play()
    }

    /// Polled from the view (more robust than VLC delegate signatures across versions).
    func tick() {
        timeMs = Int(player.time.intValue)
        lengthMs = Int(player.media?.length.intValue ?? 0)
        isPlaying = player.isPlaying
        let st = player.state
        buffering = st == .buffering || st == .opening
        if st == .playing && player.isPlaying { buffering = false }
        if st == .error { failed = true }
    }

    func togglePause() { player.isPlaying ? player.pause() : player.play() }
    func seek(by seconds: Int) {
        guard lengthMs > 0 else { return }
        let target = max(0, min(lengthMs - 1000, timeMs + seconds * 1000))
        player.time = VLCTime(int: Int32(target))
    }
    func seek(toFraction f: Double) {
        guard lengthMs > 0 else { return }
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
    private let ticker = Timer.publish(every: 0.5, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VLCSurface(player: engine.player).ignoresSafeArea()
            if engine.buffering && !engine.failed { ProgressView().scaleEffect(1.6).tint(.white) }
            if engine.failed {
                VStack(spacing: 14) {
                    Image(systemName: "exclamationmark.triangle.fill").font(.largeTitle).foregroundStyle(.orange)
                    Text(L.t("playbackError")).font(.headline)
                    HStack {
                        Button(L.t("retry")) { start() }.buttonStyle(.borderedProminent)
                        Button(L.t("close")) { close() }.buttonStyle(.bordered)
                    }
                }.padding(30).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
            }
            if controls && !engine.failed { overlay.transition(.opacity) }
        }
        .foregroundStyle(.white)
        .onAppear { start(); UIApplication.shared.isIdleTimerDisabled = true; bumpControls() }
        .onDisappear { save(); engine.stop(); UIApplication.shared.isIdleTimerDisabled = false }
        .onReceive(ticker) { _ in
            engine.tick()
            if engine.failed && request.kind == .live && retries < 3 { retries += 1; start() }
            if request.kind != .live, engine.lengthMs > 0, engine.timeMs > 0, Int(Date().timeIntervalSince1970) % 10 == 0 { save() }
        }
        #if os(tvOS)
        .focusable(!controls)
        .onPlayPauseCommand { engine.togglePause(); bumpControls() }
        .onMoveCommand { dir in
            if controls { bumpControls(); return }
            switch dir {
            case .left: request.kind == .live ? zap(-1) : engine.seek(by: -10)
            case .right: request.kind == .live ? zap(1) : engine.seek(by: 10)
            case .up, .down: if request.kind == .live { zap(dir == .up ? -1 : 1) } else { bumpControls() }
            @unknown default: break
            }
        }
        .onExitCommand { if controls { withAnimation { controls = false } } else { close() } }
        .onTapGesture { bumpControls() }
        #else
        .onTapGesture { withAnimation { controls.toggle() }; if controls { bumpControls() } }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        #endif
        .confirmationDialog(L.t("audio"), isPresented: $showAudio, titleVisibility: .visible) {
            ForEach(engine.audioTracks, id: \.0) { t in
                Button((t.0 == engine.currentAudio ? "✓ " : "") + t.1) { engine.setAudio(t.0) }
            }
        }
        .confirmationDialog(L.t("subtitles"), isPresented: $showSubs, titleVisibility: .visible) {
            ForEach(engine.subtitleTracks, id: \.0) { t in
                Button((t.0 == engine.currentSubtitle ? "✓ " : "") + (t.0 < 0 ? L.t("off") : t.1)) { engine.setSubtitle(t.0) }
            }
        }
    }

    private var overlay: some View {
        VStack {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(request.title).font(Theme.isTV ? .title2.bold() : .headline).lineLimit(1)
                    if !request.subtitle.isEmpty { Text(request.subtitle).font(.subheadline).foregroundStyle(.white.opacity(0.75)).lineLimit(1) }
                }
                Spacer()
                #if os(iOS)
                Button { close() } label: { Image(systemName: "xmark").font(.title3.weight(.bold)).padding(12).background(.black.opacity(0.4), in: Circle()) }
                #endif
            }
            .padding(Theme.isTV ? 60 : 20)
            .background(LinearGradient(colors: [.black.opacity(0.8), .clear], startPoint: .top, endPoint: .bottom))
            Spacer()
            HStack(spacing: Theme.isTV ? 60 : 40) {
                if request.kind == .live {
                    Button { zap(-1) } label: { Image(systemName: "backward.end.fill").font(.title) }
                } else {
                    Button { engine.seek(by: -10); bumpControls() } label: { Image(systemName: "gobackward.10").font(.title) }
                }
                Button { engine.togglePause(); bumpControls() } label: {
                    Image(systemName: engine.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 34)).frame(width: 74, height: 74)
                        #if os(iOS)
                        .background(.white, in: Circle()).foregroundStyle(.black)
                        #endif
                }
                if request.kind == .live {
                    Button { zap(1) } label: { Image(systemName: "forward.end.fill").font(.title) }
                } else {
                    Button { engine.seek(by: 10); bumpControls() } label: { Image(systemName: "goforward.10").font(.title) }
                }
            }
            #if os(iOS)
            .buttonStyle(.plain)
            #endif
            Spacer()
            VStack(spacing: 12) {
                if request.kind != .live && engine.lengthMs > 0 {
                    HStack(spacing: 12) {
                        Text(fmt(engine.timeMs)).font(.caption.monospacedDigit())
                        #if os(iOS)
                        Slider(value: Binding(get: { Double(engine.timeMs) / Double(max(1, engine.lengthMs)) },
                                              set: { engine.seek(toFraction: $0); bumpControls() }))
                        #else
                        ProgressView(value: Double(engine.timeMs), total: Double(max(1, engine.lengthMs))).tint(Theme.blue)
                        #endif
                        Text(fmt(engine.lengthMs)).font(.caption.monospacedDigit())
                    }
                }
                HStack(spacing: 14) {
                    if request.kind == .live {
                        Text("● " + L.t("liveBadge")).font(.caption.weight(.heavy)).padding(.horizontal, 10).padding(.vertical, 4).background(Theme.live, in: Capsule())
                    }
                    Spacer()
                    Button { showAudio = true; bumpControls() } label: { Label(L.t("audio"), systemImage: "speaker.wave.2.fill") }
                    Button { showSubs = true; bumpControls() } label: { Label(L.t("subtitles"), systemImage: "captions.bubble.fill") }
                    if let next = nextEpisode {
                        Button { playEpisode(next) } label: { Label(L.t("episode", next.num), systemImage: "forward.fill") }
                    }
                }
                #if os(iOS)
                .buttonStyle(.bordered)
                #endif
            }
            .padding(Theme.isTV ? 60 : 20)
            .background(LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom))
        }
    }

    private var nextEpisode: Episode? {
        guard request.kind == .series, let cur = request.episode,
              let i = request.seriesEpisodes.firstIndex(where: { $0.id == cur.id }), i + 1 < request.seriesEpisodes.count else { return nil }
        return request.seriesEpisodes[i + 1]
    }

    private func fmt(_ ms: Int) -> String {
        let s = ms / 1000
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }

    private func start() {
        engine.open(request.url, startMs: request.startMs, live: request.kind == .live)
    }

    private func bumpControls() {
        withAnimation { controls = true }
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            if !Task.isCancelled { await MainActor.run { withAnimation { controls = false } } }
        }
    }

    private func zap(_ delta: Int) {
        let list = request.channels
        guard request.kind == .live, list.count > 1, let i = list.firstIndex(where: { $0.id == request.stream.id }) else { return }
        let ch = list[(i + delta + list.count) % list.count]
        guard let u = state.url(.live, ch) else { return }
        request.stream = ch; request.title = ch.name; request.url = u
        state.record(kind: .live, item: ch, position: 0, duration: 0)
        retries = 0
        start()
        bumpControls()
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
        guard request.kind != .live, engine.lengthMs > 0, engine.timeMs > 0 else { return }
        state.record(kind: request.kind, item: request.stream, episode: request.episode, subtitle: request.subtitle,
                     position: engine.timeMs, duration: engine.lengthMs)
    }

    private func close() {
        save()
        engine.stop()
        dismiss()
        state.playing = nil
    }
}
