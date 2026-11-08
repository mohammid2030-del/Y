import SwiftUI

enum Palette {
    static let all: [[Color]] = [
        [Color(red: 0.31, green: 0.48, blue: 1), Color(red: 0.61, green: 0.42, blue: 1)],
        [Color(red: 1, green: 0.37, blue: 0.54), Color(red: 1, green: 0.62, blue: 0.35)],
        [Color(red: 0.09, green: 0.78, blue: 0.66), Color(red: 0.18, green: 0.55, blue: 1)],
        [Color(red: 1, green: 0.71, blue: 0.28), Color(red: 1, green: 0.37, blue: 0.37)],
        [Color(red: 0.71, green: 0.36, blue: 1), Color(red: 1, green: 0.36, blue: 0.78)],
        [Color(red: 0.2, green: 0.8, blue: 0.4), Color(red: 0.1, green: 0.5, blue: 0.4)]
    ]
    static func colors(_ i: Int) -> [Color] { all[((i % all.count) + all.count) % all.count] }
}

private struct EditTarget: Identifiable { let id = UUID(); let viewer: Viewer? }

/// "Who's watching?" — the people on this subscription (up to 5), each with their own progress and optional PIN.
struct ViewersView: View {
    @EnvironmentObject var state: AppState
    @State private var editing = false
    @State private var edit: EditTarget?
    @State private var pinFor: Viewer?
    @State private var appear = false

    private var tile: CGFloat { Theme.isTV ? 200 : 110 }

    var body: some View {
        ZStack {
            Color(red: 0.02, green: 0.02, blue: 0.035).ignoresSafeArea()
            GlowBackground()
            ScrollView {
                VStack(spacing: Theme.isTV ? 36 : 22) {
                    HStack(spacing: 14) {
                        RoundedRectangle(cornerRadius: 14).fill(Theme.gradient).frame(width: 52, height: 52)
                            .overlay(Image(systemName: "play.fill").font(.title2).foregroundStyle(.white))
                        Text("NOVA").font(.system(size: Theme.isTV ? 48 : 36, weight: .black)).kerning(6)
                    }
                    VStack(spacing: 8) {
                        Text(L.t(editing ? "manageViewers" : "who")).font(.system(size: Theme.isTV ? 60 : 34, weight: .heavy))
                        Text((state.active?.name ?? "") + (Cloud.shared.enabled ? "  ·  " + L.t("cloudOn") : ""))
                            .foregroundStyle(.secondary)
                    }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: Theme.isTV ? 250 : 140), spacing: Theme.isTV ? 40 : 16)],
                              spacing: Theme.isTV ? 40 : 22) {
                        ForEach(state.viewers) { v in
                            Button {
                                if editing { edit = EditTarget(viewer: v) }
                                else if v.hasPIN { pinFor = v }
                                else { state.chooseViewer(v) }
                            } label: { viewerTile(v) }
                            .cardStyle()
                        }
                        if state.viewers.count < 5 {
                            Button { edit = EditTarget(viewer: nil) } label: {
                                VStack(spacing: 12) {
                                    RoundedRectangle(cornerRadius: 34, style: .continuous)
                                        .strokeBorder(style: StrokeStyle(lineWidth: 3, dash: [10, 8])).foregroundStyle(.white.opacity(0.4))
                                        .frame(width: tile, height: tile)
                                        .overlay(Image(systemName: "plus").font(.system(size: 44, weight: .semibold)))
                                    Text(L.t("addViewer")).font(.headline).lineLimit(1)
                                }.padding(10)
                            }.cardStyle()
                        }
                    }
                    .frame(maxWidth: 1400)
                    HStack(spacing: 20) {
                        Button { editing.toggle() } label: {
                            Label(L.t(editing ? "done" : "manageViewers"), systemImage: editing ? "checkmark" : "pencil")
                        }
                        Button { state.phase = .profiles } label: { Label(L.t("switchSub"), systemImage: "rectangle.stack") }
                    }
                    .buttonStyle(.bordered)
                }
                .padding(.vertical, 50).padding(.horizontal, Theme.pad)
                .opacity(appear ? 1 : 0).offset(y: appear ? 0 : 30)
            }
        }
        .onAppear { withAnimation(.easeOut(duration: 0.6)) { appear = true } }
        .sheet(item: $edit) { t in ViewerEditor(viewer: t.viewer).environmentObject(state) }
        .sheet(item: $pinFor) { v in
            PINPad(title: v.name) { pin in
                if state.checkPIN(v, pin) { pinFor = nil; state.chooseViewer(v); return true }
                return false
            }
        }
    }

    private func viewerTile(_ v: Viewer) -> some View {
        let pal = Palette.colors(v.color)
        return VStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 34, style: .continuous).fill(LinearGradient(colors: pal, startPoint: .topLeading, endPoint: .bottomTrailing))
                RoundedRectangle(cornerRadius: 34, style: .continuous)
                    .fill(RadialGradient(colors: [.white.opacity(0.35), .clear], center: UnitPoint(x: 0.25, y: 0.2), startRadius: 1, endRadius: tile * 0.7))
                Text(String(v.name.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased())
                    .font(.system(size: tile * 0.42, weight: .black)).foregroundStyle(.white)
                if v.hasPIN {
                    Image(systemName: "lock.fill").font(.headline).foregroundStyle(.white).padding(10)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                }
                if editing {
                    Image(systemName: "pencil.circle.fill").font(.title).foregroundStyle(.white, .black.opacity(0.5))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing).padding(8)
                }
            }
            .frame(width: tile, height: tile)
            Text(v.name).font(.headline).lineLimit(1)
        }
        .padding(10)
    }
}

/// Add / edit a viewer: name, colour, optional 4-digit PIN, delete.
struct ViewerEditor: View {
    let viewer: Viewer?
    @EnvironmentObject var state: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var color = 0
    @State private var pin = ""
    @State private var removePIN = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L.t("viewerName"), text: $name)
                    HStack(spacing: 14) {
                        ForEach(0..<Palette.all.count, id: \.self) { i in
                            Button { color = i } label: {
                                Circle().fill(LinearGradient(colors: Palette.colors(i), startPoint: .topLeading, endPoint: .bottomTrailing))
                                    .frame(width: Theme.isTV ? 60 : 34, height: Theme.isTV ? 60 : 34)
                                    .overlay(Circle().stroke(.white, lineWidth: color == i ? 4 : 0))
                            }
                            .buttonStyle(LiftButtonStyle(radius: 40))
                        }
                    }
                }
                Section {
                    SecureField(L.t(viewer?.hasPIN == true ? "newPIN" : "pinOptional"), text: $pin)
                        #if os(iOS)
                        .keyboardType(.numberPad)
                        #endif
                        .onChange(of: pin) { v in pin = String(v.filter(\.isNumber).prefix(4)) }
                    if viewer?.hasPIN == true { Toggle(L.t("removePIN"), isOn: $removePIN) }
                } footer: { Text(L.t("pinHint")) }
                Section {
                    Button(L.t("save")) {
                        let n = name.trimmingCharacters(in: .whitespaces)
                        state.saveViewer(id: viewer?.id, name: n.isEmpty ? L.t("viewer") : n, color: color,
                                         pin: pin.count == 4 ? pin : nil, clearPIN: removePIN)
                        dismiss()
                    }
                    .disabled(!pin.isEmpty && pin.count != 4)
                    if let v = viewer, state.viewers.count > 1 {
                        Button(L.t("deleteViewer"), role: .destructive) { state.deleteViewer(v); dismiss() }
                    }
                    Button(L.t("cancel"), role: .cancel) { dismiss() }
                }
            }
            .navigationTitle(L.t(viewer == nil ? "addViewer" : "editViewer"))
        }
        .onAppear {
            name = viewer?.name ?? ""
            color = viewer?.color ?? (state.viewers.count % Palette.all.count)
        }
    }
}

/// 4-digit PIN entry that works with the Apple TV remote (big number pad) and touch.
struct PINPad: View {
    let title: String
    let check: (String) -> Bool
    @Environment(\.dismiss) private var dismiss
    @State private var pin = ""
    @State private var wrong = false

    var body: some View {
        VStack(spacing: Theme.isTV ? 36 : 20) {
            Text(title).font(.title2.bold())
            Text(L.t(wrong ? "wrongPIN" : "enterPIN")).foregroundStyle(wrong ? Theme.live : .secondary)
            HStack(spacing: 18) {
                ForEach(0..<4, id: \.self) { i in
                    Circle().fill(i < pin.count ? Color.white : Color.white.opacity(0.2)).frame(width: 22, height: 22)
                }
            }
            .offset(x: wrong ? 8 : 0)
            let rows = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["⌫", "0", "✕"]]
            VStack(spacing: Theme.isTV ? 24 : 12) {
                ForEach(rows, id: \.self) { row in
                    HStack(spacing: Theme.isTV ? 24 : 12) {
                        ForEach(row, id: \.self) { k in
                            Button { tap(k) } label: {
                                Text(k).font(.system(size: Theme.isTV ? 40 : 26, weight: .semibold))
                                    .frame(width: Theme.isTV ? 130 : 72, height: Theme.isTV ? 90 : 56)
                            }
                            #if os(tvOS)
                            .buttonStyle(.card)
                            #else
                            .buttonStyle(.bordered)
                            #endif
                        }
                    }
                }
            }
        }
        .padding(Theme.isTV ? 80 : 30)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.bg.ignoresSafeArea())
    }

    private func tap(_ k: String) {
        switch k {
        case "⌫": if !pin.isEmpty { pin.removeLast() }
        case "✕": dismiss()
        default:
            guard pin.count < 4 else { return }
            wrong = false
            pin += k
            if pin.count == 4 {
                if !check(pin) {
                    withAnimation(.default.repeatCount(3, autoreverses: true)) { wrong = true }
                    pin = ""
                }
            }
        }
    }
}
