import Foundation

/// Removes provider decorations from titles ("4K-AMZ - Drive (2025) (US) HEVC" → "Drive", year "2025").
enum Clean {
    private static let prefixCodes: Set<String> = ["AR", "EN", "TR", "FR", "DE", "ES", "IT", "IN", "US", "UK", "KSA", "SA", "EG", "AE", "KW", "QA",
                                                   "NF", "OSN", "AS", "KR", "CN", "JP", "HI", "4K", "8K", "FHD", "UHD", "HD", "VIP", "MULTI"]
    private static let tags: Set<String> = ["4K", "8K", "UHD", "FHD", "HD", "SD", "HEVC", "H265", "H264", "HDR", "AR", "EN", "TR", "DO", "DB",
                                            "SUB", "SUBS", "DUB", "DUBBED", "MULTI", "ARSUB", "VIP", "NF", "ULTRA", "ULTRAHD", "RAW", "AMZ", "ATV",
                                            "DSNP", "HMAX", "MAX", "HULU", "PRIME", "US", "UK", "GB", "CA", "AU", "KR", "JP", "FR", "DE", "ES", "IT",
                                            "MX", "BR", "SUIBS", "DV", "DOLBY", "ATMOS", "REMUX", "WEB", "WEBDL", "BLURAY", "1080P", "720P", "2160P"]

    private static func isTagGroup(_ s: String) -> Bool {
        let parts = s.split(whereSeparator: { "-_ ".contains($0) }).map { $0.uppercased() }
        return !parts.isEmpty && parts.allSatisfy { tags.contains($0) || prefixCodes.contains($0) }
    }

    private static func firstMatch(_ pattern: String, _ s: String) -> (range: Range<String.Index>, groups: [String])? {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return nil }
        let ns = s as NSString
        guard let m = re.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)),
              let r = Range(m.range, in: s) else { return nil }
        var groups: [String] = []
        for i in 1..<max(1, m.numberOfRanges) {
            if let gr = Range(m.range(at: i), in: s) { groups.append(String(s[gr])) } else { groups.append("") }
        }
        return (r, groups)
    }

    static func full(_ raw: String) -> (title: String, year: String) {
        var t = raw.trimmingCharacters(in: .whitespaces)
        if let m = firstMatch(#"^\s*[|\[(]?\s*([A-Za-z0-9]{2,5})\s*[|\])]?\s*[:|]\s*"#, t),
           prefixCodes.contains(m.groups.first?.uppercased() ?? ""), t[m.range.upperBound...].count > 0 {
            t = String(t[m.range.upperBound...])
        }
        if let m = firstMatch(#"^\s*([A-Za-z0-9][A-Za-z0-9_\-]{0,14})\s+[-|–—]\s+"#, t),
           isTagGroup(m.groups.first ?? ""), t[m.range.upperBound...].count > 0 {
            t = String(t[m.range.upperBound...])
        }
        var year = ""
        if let m = firstMatch(#"[(\[]((?:19|20)\d{2})[)\]]"#, t) {
            year = m.groups.first ?? ""
            if m.range.lowerBound > t.startIndex { t = String(t[..<m.range.lowerBound]) }
        }
        t = t.replacingOccurrences(of: #"[(\[][^)\]]*[)\]]"#, with: " ", options: .regularExpression)
        var words = t.split(separator: " ").map(String.init)
        while words.count > 1, let last = words.last,
              tags.contains(last.trimmingCharacters(in: CharacterSet(charactersIn: "-|")).uppercased()) || last == "-" || last == "|" {
            words.removeLast()
        }
        let out = words.joined(separator: " ").trimmingCharacters(in: .whitespaces)
        return (out.isEmpty ? raw.trimmingCharacters(in: .whitespaces) : out, year)
    }

    static func title(_ raw: String) -> String { full(raw).title }

    static func seasonEpisode(_ text: String) -> (Int, Int)? {
        guard let m = firstMatch(#"[Ss]\s*0*(\d{1,2})\s*[ .xX-]?\s*[Ee]\s*0*(\d{1,3})"#, text),
              let s = Int(m.groups[0]), let e = Int(m.groups[1]) else { return nil }
        return (s, e)
    }
}
