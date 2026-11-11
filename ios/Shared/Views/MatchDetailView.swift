import SwiftUI

/// A match's own page: the teams, kick-off (the viewer's time and the stadium's), the stadium with a photo,
/// the official broadcaster and the exact channel showing the match — every copy of it (Arabic / English, quality)
/// — then form, table positions, line-ups or squads, and the last meetings.
struct MatchDetailView: View {
    let match: Match
    @EnvironmentObject var state: AppState
    @State private var d: MatchDetails?
    @State private var groups: [Matches.ChannelGroup]?
    @State private var guideChecked = false
    private var ar: Bool { L.isArabic }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.isTV ? 36 : 22) {
                header
                channels
                facts
                if let d, !d.venueImage.isEmpty || !d.venue.isEmpty { venue(d) }
                if let d { teams(d) }
                if let d, !d.meetings.isEmpty { meetings(d) }
                if d == nil { ProgressView().frame(maxWidth: .infinity).padding(40) }
            }
            .padding(.horizontal, Theme.pad).padding(.vertical, Theme.isTV ? 40 : 16)
        }
        .background(backdrop)
        .navigationTitle(match.league)
        .task { d = await Matches.details(match) }
        .task { await loadChannels() }
    }

    // MARK: sections

    private var backdrop: some View {
        LinearGradient(colors: [(match.home.color ?? Theme.blue).opacity(0.45), Theme.bg, (match.away.color ?? .purple).opacity(0.35)],
                       startPoint: .topLeading, endPoint: .bottomTrailing).ignoresSafeArea()
    }

    private var header: some View {
        HStack(spacing: Theme.isTV ? 40 : 14) {
            team(match.home)
            VStack(spacing: 8) {
                HStack(spacing: 8) {
                    if !match.leagueLogo.isEmpty {
                        RemoteImage(url: match.leagueLogo, contentMode: .fit).frame(width: 34, height: 34).padding(4).background(.white, in: Circle())
                    }
                    Text(match.league).font(.headline).foregroundStyle(.secondary)
                }
                if match.state == .pre || match.state == .postponed {
                    Text(match.start, style: .time).font(.system(size: Theme.isTV ? 70 : 40, weight: .black))
                    Text(match.state == .postponed ? L.t("postponed") : relative).font(.headline).foregroundStyle(.secondary)
                } else {
                    Text("\(match.home.score) - \(match.away.score)").font(.system(size: Theme.isTV ? 80 : 44, weight: .black))
                    Text(match.isLive ? "● " + (match.state == .halftime ? L.t("matchHT") : match.clock) : L.t("matchFT"))
                        .font(.headline).foregroundStyle(match.isLive ? Theme.live : .secondary)
                }
            }
            .frame(maxWidth: .infinity)
            team(match.away)
        }
        .padding(Theme.isTV ? 30 : 16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
    }

    private var relative: String {
        let mins = Int(match.start.timeIntervalSinceNow / 60)
        guard mins > 0 else { return ar ? "تبدأ الآن" : "Starting" }
        let t = mins >= 60 ? "\(mins / 60)" + (ar ? " س " : "h ") + "\(mins % 60)" + (ar ? " د" : "m") : "\(mins)" + (ar ? " دقيقة" : " min")
        return ar ? "تبدأ بعد \(t)" : "Starts in \(t)"
    }

    private func team(_ t: MatchTeam) -> some View {
        VStack(spacing: 10) {
            RemoteImage(url: t.logo, contentMode: .fit, placeholderIcon: "soccerball")
                .frame(width: Theme.isTV ? 150 : 72, height: Theme.isTV ? 150 : 72).padding(12)
                .background(.white.opacity(0.12), in: Circle())
            Text(t.name).font(Theme.isTV ? .title3.bold() : .headline).multilineTextAlignment(.center).lineLimit(2)
        }
        .frame(maxWidth: .infinity)
    }

    /// The official broadcaster and the exact channel(s) — every version of each.
    @ViewBuilder private var channels: some View {
        card(title: ar ? "القناة الناقلة" : "Where to watch", icon: "tv") {
            let net = Matches.broadcaster(match.slug)
            if !net.isEmpty {
                Label((ar ? "الناقل الرسمي: " : "Official broadcaster: ") + net, systemImage: "checkmark.seal.fill").foregroundStyle(Theme.blue)
            }
            if let groups {
                if groups.isEmpty {
                    Text(L.t("noChannel")).foregroundStyle(.secondary)
                } else {
                    if !groups.contains(where: \.showing) && guideChecked {
                        Text(L.t("notInGuide")).font(.footnote).foregroundStyle(.orange)
                    }
                    ForEach(groups) { g in
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(spacing: 10) {
                                Text(g.name).font(.headline)
                                if g.showing {
                                    Text(ar ? "تعرض المباراة" : "Showing this match").font(.caption.bold())
                                        .padding(.horizontal, 10).padding(.vertical, 4).background(Theme.live, in: Capsule())
                                }
                            }
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: Theme.isTV ? 24 : 10) {
                                    ForEach(g.versions) { ch in
                                        Button {
                                            Matches.remember(match, subID: state.activeID, channel: ch)
                                            state.playLive(ch, list: g.versions)
                                        } label: { versionLabel(ch) }
                                        #if os(tvOS)
                                        .buttonStyle(.bordered)
                                        #else
                                        .buttonStyle(.borderedProminent)
                                        #endif
                                    }
                                }
                                .padding(.vertical, Theme.isTV ? 12 : 2).padding(.horizontal, 4)
                            }
                        }
                    }
                }
            } else {
                HStack(spacing: 10) { ProgressView(); Text(L.t("findingChannel")).font(.footnote) }
            }
        }
    }

    private func versionLabel(_ ch: MediaItem) -> some View {
        let lang = Matches.isArabicChannel(ch.name) ? (ar ? "عربي" : "Arabic") : Matches.isEnglishChannel(ch.name) ? (ar ? "إنجليزي" : "English") : ""
        let t = ch.name.lowercased()
        let q = t.contains("4k") || t.contains("uhd") ? "4K" : t.contains("fhd") ? "FHD" : t.contains("hd") ? "HD" : t.contains("sd") ? "SD" : ""
        let title = [lang, q].filter { !$0.isEmpty }.joined(separator: " · ")
        return VStack(alignment: .leading, spacing: 2) {
            Text(title.isEmpty ? ch.name : title).font(.headline)
            if !title.isEmpty { Text(ch.name).font(.caption2).foregroundStyle(.secondary).lineLimit(1) }
        }
        .padding(.horizontal, 6)
    }

    /// Date, the viewer's time, the stadium's local time, referee.
    private var facts: some View {
        card(title: ar ? "موعد المباراة" : "Kick-off", icon: "calendar") {
            row(ar ? "التاريخ" : "Date", format(match.start, zone: .current, date: true))
            row(ar ? "بتوقيتك" : "Your time", format(match.start, zone: .current, date: false))
            if let z = d?.venueZone, z.secondsFromGMT(for: match.start) != TimeZone.current.secondsFromGMT(for: match.start) {
                row((ar ? "بتوقيت " : "Local time in ") + (d?.city.isEmpty == false ? d!.city : (ar ? "الملعب" : "the stadium")),
                    format(match.start, zone: z, date: false))
            }
            if let r = d?.referee, !r.isEmpty { row(ar ? "الحكم" : "Referee", r) }
        }
    }

    private func venue(_ d: MatchDetails) -> some View {
        card(title: ar ? "الملعب" : "Stadium", icon: "sportscourt") {
            if !d.venueImage.isEmpty {
                RemoteImage(url: d.venueImage).frame(maxWidth: .infinity).frame(height: Theme.isTV ? 420 : 200)
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            }
            if !d.venue.isEmpty { row(ar ? "الاسم" : "Name", d.venue) }
            let place = [d.city, d.country].filter { !$0.isEmpty }.joined(separator: "، ")
            if !place.isEmpty { row(ar ? "المدينة والدولة" : "City & country", place) }
            if d.capacity > 0 { row(ar ? "السعة" : "Capacity", NumberFormatter.localizedString(from: NSNumber(value: d.capacity), number: .decimal)) }
        }
    }

    private func teams(_ d: MatchDetails) -> some View {
        HStack(alignment: .top, spacing: Theme.isTV ? 30 : 12) {
            side(match.home, d.home)
            side(match.away, d.away)
        }
    }

    private func side(_ t: MatchTeam, _ s: MatchDetails.Side) -> some View {
        card(title: t.short, icon: "person.3") {
            if !s.rank.isEmpty { row(ar ? "الترتيب" : "Position", s.rank + (s.points.isEmpty ? "" : (ar ? " · \(s.points) نقطة" : " · \(s.points) pts"))) }
            if !s.form.isEmpty {
                HStack(spacing: 8) {
                    Text(ar ? "آخر النتائج" : "Form").foregroundStyle(.secondary)
                    ForEach(Array(s.form.enumerated()), id: \.offset) { _, r in
                        Text(r).font(.caption.bold()).frame(width: 34, height: 34)
                            .background(r == "W" ? Color.green : r == "L" ? Color.red : Color.gray, in: Circle())
                    }
                }
            }
            if !s.players.isEmpty {
                Text(s.lineup ? (ar ? "التشكيلة" : "Line-up") + (s.formation.isEmpty ? "" : " · \(s.formation)") : (ar ? "قائمة الفريق" : "Squad"))
                    .font(.headline).padding(.top, 6)
                let list = s.lineup ? s.players.filter(\.starter) + s.players.filter { !$0.starter } : s.players
                ForEach(Array(list.prefix(26).enumerated()), id: \.offset) { i, p in
                    if s.lineup && i == list.filter(\.starter).count && i > 0 {
                        Text(ar ? "الاحتياط" : "Substitutes").font(.subheadline.bold()).foregroundStyle(.secondary).padding(.top, 4)
                    }
                    HStack(spacing: 10) {
                        Text(p.number.isEmpty ? "–" : p.number).font(.caption.bold().monospacedDigit()).frame(width: 34)
                        Text(p.name).lineLimit(1)
                        Spacer()
                        Text(p.position).font(.caption).foregroundStyle(.secondary)
                    }
                    .font(Theme.isTV ? .body : .subheadline)
                }
            }
        }
    }

    private func meetings(_ d: MatchDetails) -> some View {
        card(title: ar ? "آخر المواجهات" : "Last meetings", icon: "clock.arrow.circlepath") {
            ForEach(d.meetings, id: \.self) { g in
                HStack {
                    Text(g.date).font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(width: Theme.isTV ? 170 : 90, alignment: .leading)
                    Text(g.home).lineLimit(1)
                    Spacer()
                    Text(g.score).font(.headline.monospacedDigit())
                    Spacer()
                    Text(g.away).lineLimit(1)
                }
            }
        }
    }

    // MARK: helpers

    /// A section; on Apple TV it can take the focus so the remote scrolls through the page.
    private func card<C: View>(title: String, icon: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(title, systemImage: icon).font(Theme.isTV ? .title3.bold() : .headline)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Theme.isTV ? 28 : 14)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .modifier(FocusableOnTV())
    }

    private func row(_ k: String, _ v: String) -> some View {
        HStack(alignment: .top) {
            Text(k).foregroundStyle(.secondary).frame(width: Theme.isTV ? 280 : 120, alignment: .leading)
            Text(v).fontWeight(.semibold)
            Spacer(minLength: 0)
        }
    }

    private func format(_ date: Date, zone: TimeZone, date withDate: Bool) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: ar ? "ar" : "en")
        f.timeZone = zone
        if withDate { f.dateStyle = .full; f.timeStyle = .none } else { f.dateStyle = .none; f.timeStyle = .short }
        return f.string(from: date)
    }

    // MARK: channels

    private func loadChannels() async {
        let live = state.catalog.live, subID = state.activeID, m = match
        // ranking scans every channel: off the main thread
        let ranked = await Task.detached(priority: .userInitiated) { Matches.rank(m, channels: live, subID: subID) }.value
        let holders = ranked.filter { Matches.isRightsHolder(m, $0.0.name) }
        let pool = Array((holders.isEmpty ? ranked : holders).prefix(40))
        var boosted = pool.map { ($0.0, $0.1, false) }
        if let sub = state.active, sub.kind == .xtream, !pool.isEmpty {
            boosted = await Matches.epgBoost(m, candidates: pool, xtream: state.xtream(for: sub))
            guideChecked = true
        }
        let b = boosted
        groups = await Task.detached(priority: .userInitiated) {
            Matches.channelGroups(m, all: live.filter { Matches.isSportsChannel($0.name) }, ranked: b)
        }.value
    }
}

/// On Apple TV a block of text can take the focus (so the remote scrolls the page through it).
struct FocusableOnTV: ViewModifier {
    func body(content: Content) -> some View {
        #if os(tvOS)
        content.focusable()
        #else
        content
        #endif
    }
}
