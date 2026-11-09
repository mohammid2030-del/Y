import Foundation
import Darwin

/// Records why the app last crashed (signal or exception + call stack) to a file, and shows it on the
/// next launch so the cause can be reported. Nothing leaves the device.
enum CrashLog {
    private static var url: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("last-crash.txt")
    }

    /// The report of the previous crash, if any (read once at launch).
    static let pending: String? = {
        guard let s = try? String(contentsOf: url, encoding: .utf8),
              !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return s
    }()

    static func clear() { if fd >= 0 { ftruncate(fd, 0) } else { try? FileManager.default.removeItem(at: url) } }

    static func install() {
        _ = pending
        let path = url.path
        let version = (Bundle.main.infoDictionary?["CFBundleVersion"] as? String) ?? "?"
        header = Array("build \(version)\n".utf8)
        fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0o644)
        if pending == nil { ftruncate(fd, 0) }   // keep an unread report until it was shown
        NSSetUncaughtExceptionHandler { e in
            let text = "build \((Bundle.main.infoDictionary?["CFBundleVersion"] as? String) ?? "?")\nexception: \(e.name.rawValue)\n\(e.reason ?? "")\n"
                + e.callStackSymbols.prefix(40).joined(separator: "\n") + "\n"
            guard fd >= 0 else { return }
            ftruncate(fd, 0)
            let bytes = Array(text.utf8)
            bytes.withUnsafeBufferPointer { _ = write(fd, $0.baseAddress, $0.count) }
            fsync(fd)
            fd = -1   // the abort that follows must not overwrite the reason
        }
        for sig in [SIGABRT, SIGSEGV, SIGBUS, SIGTRAP, SIGILL, SIGFPE] {
            signal(sig, crashSignal)
        }
    }
}

// Only async-signal-safe calls in here.
private var fd: Int32 = -1
private var header: [UInt8] = []
private func crashSignal(_ sig: Int32) {
    if fd >= 0 {
        ftruncate(fd, 0)
        header.withUnsafeBufferPointer { _ = write(fd, $0.baseAddress, $0.count) }
        let label: StaticString
        switch sig {
        case SIGTRAP: label = "signal SIGTRAP (Swift runtime check)\n"
        case SIGSEGV: label = "signal SIGSEGV (bad memory access)\n"
        case SIGBUS: label = "signal SIGBUS\n"
        case SIGABRT: label = "signal SIGABRT\n"
        case SIGILL: label = "signal SIGILL\n"
        default: label = "signal\n"
        }
        _ = write(fd, label.utf8Start, label.utf8CodeUnitCount)
        var frames = [UnsafeMutableRawPointer?](repeating: nil, count: 48)
        let n = backtrace(&frames, 48)
        backtrace_symbols_fd(&frames, n, fd)
        fsync(fd)
    }
    signal(sig, SIG_DFL)
    raise(sig)
}
