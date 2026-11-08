import Foundation

/// Arabic / English strings. The app follows the device language (Arabic when the device prefers Arabic).
enum L {
    static let isArabic: Bool = (Locale.preferredLanguages.first ?? "ar").hasPrefix("ar")

    static func t(_ key: String) -> String {
        (isArabic ? ar[key] : en[key]) ?? en[key] ?? key
    }

    static func t(_ key: String, _ args: CVarArg...) -> String {
        String(format: t(key), arguments: args)
    }

    private static let en: [String: String] = [
        "home": "Home", "live": "Live TV", "movies": "Movies", "series": "Series", "search": "Search",
        "favorites": "Favorites", "settings": "Settings",
        "who": "Who's watching?", "whoHint": "Each subscription keeps its own favorites and history",
        "addSub": "Add subscription", "editSub": "Edit subscription", "manageSubs": "Manage subscriptions",
        "daysLeft": "%d days left", "expired": "Expired", "switchProfile": "Switch profile",
        "xtream": "Xtream Codes", "m3u": "M3U URL", "name": "Name (optional)", "server": "Server URL",
        "username": "Username", "password": "Password", "m3uURL": "M3U / M3U8 URL", "saveLoad": "Save & load",
        "legal": "Use only subscriptions you are legally entitled to. NOVA provides no channels and does not bypass DRM.",
        "connecting": "Verifying subscription…", "loading": "Loading channels, movies and series…", "saving": "Saving…",
        "syncFailed": "Could not load the subscription", "retry": "Retry", "back": "Back", "cancel": "Cancel",
        "all": "All", "recentlyAdded": "Recently added", "continueWatching": "Continue watching",
        "latestMovies": "Latest movies", "latestSeries": "Latest series", "recentChannels": "Recently watched channels",
        "play": "Play", "resume": "Resume", "startOver": "Start over", "details": "Details", "watchNow": "Watch now",
        "trailer": "Trailer", "addFav": "Add to favorites", "removeFav": "Remove from favorites",
        "season": "Season %d", "episode": "Episode %d", "minutes": "%d min", "noEpisodes": "No episodes available",
        "watched": "%d%% watched", "seasonEpisode": "Season %d · Episode %d", "seasonsCount": "%d seasons",
        "votes": "%d votes", "cast": "Cast", "director": "Director", "newBadge": "NEW", "movie": "Movie", "seriesOne": "Series",
        "empty": "Nothing here yet", "emptyFav": "Your favorites will appear here", "searchHint": "Search channels, movies, series",
        "searchPrompt": "Type at least 2 characters", "noResults": "No results",
        "subscriptions": "Subscriptions", "active": "Active", "use": "Use", "refresh": "Refresh", "delete": "Delete",
        "tmdb": "TMDB — ratings & artwork", "tmdbKey": "TMDB API key (v3) or read token",
        "tmdbOn": "Connected — details and artwork come from TMDB",
        "tmdbOff": "Add your free key from themoviedb.org/settings/api for ratings, Arabic posters, overviews, cast and trailers",
        "tmdbInvalid": "TMDB rejected this key, or there is no connection", "tmdbSaved": "TMDB connected", "save": "Save", "remove": "Remove",
        "tmdbAttribution": "This product uses the TMDB API but is not endorsed or certified by TMDB.",
        "clearHistory": "Clear watch history", "about": "About",
        "aboutDesc": "A player for IPTV subscriptions you are authorised to use. NOVA does not provide any content.",
        "matches": "Top matches", "today": "Today", "tomorrow": "Tomorrow", "matchLive": "LIVE", "matchHT": "Half-time",
        "matchFT": "Full-time", "postponed": "Postponed", "noMatches": "No top matches on this day",
        "opensIn": "Channel opens in %@", "pickChannel": "Choose the channel", "noChannel": "No matching channel in your subscription",
        "audio": "Audio", "subtitles": "Subtitles", "off": "Off", "playbackError": "The stream could not be played.",
        "close": "Close", "notInGuide": "The guide doesn't list this match — official broadcaster channels:", "knownFor": "Known for", "notInSub": "“%@” is not in your subscription", "matchPrefs": "Favorite teams & leagues", "onlyFavTeams": "Only my teams", "onlyFavHint": "Show only matches of your favorite clubs and national teams.", "favTeams": "Favorite teams", "noFavTeams": "No favorite teams yet — add them below", "addTeams": "Add teams from", "followedComps": "Followed competitions", "noFavMatches": "None of your teams plays on this day", "findingChannel": "Finding the channel showing this match…", "noTracks": "No other tracks in this stream", "scrubHint": "◀ ▶ to seek","liveBadge": "LIVE", "showMatches": "Matches strip on Home", "matchesSection": "Matches"
    ]

    private static let ar: [String: String] = [
        "home": "الرئيسية", "live": "البث المباشر", "movies": "الأفلام", "series": "المسلسلات", "search": "بحث",
        "favorites": "المفضلة", "settings": "الإعدادات",
        "who": "من يشاهد؟", "whoHint": "لكل اشتراك مفضلته وسجل مشاهدته الخاص",
        "addSub": "إضافة اشتراك", "editSub": "تعديل الاشتراك", "manageSubs": "إدارة الاشتراكات",
        "daysLeft": "باقي %d يوم", "expired": "منتهي", "switchProfile": "تبديل الملف",
        "xtream": "Xtream Codes", "m3u": "رابط M3U", "name": "الاسم (اختياري)", "server": "رابط الخادم",
        "username": "اسم المستخدم", "password": "كلمة المرور", "m3uURL": "رابط M3U / M3U8", "saveLoad": "حفظ وتحميل",
        "legal": "استخدم فقط الاشتراكات التي تملك حق استخدامها. NOVA لا يوفر أي قنوات ولا يتجاوز الحماية.",
        "connecting": "جارٍ التحقق من الاشتراك…", "loading": "جارٍ تحميل القنوات والأفلام والمسلسلات…", "saving": "جارٍ الحفظ…",
        "syncFailed": "تعذّر تحميل الاشتراك", "retry": "إعادة المحاولة", "back": "رجوع", "cancel": "إلغاء",
        "all": "الكل", "recentlyAdded": "أضيفت مؤخراً", "continueWatching": "متابعة المشاهدة",
        "latestMovies": "أحدث الأفلام", "latestSeries": "أحدث المسلسلات", "recentChannels": "قنوات شاهدتها مؤخراً",
        "play": "تشغيل", "resume": "استئناف", "startOver": "من البداية", "details": "التفاصيل", "watchNow": "شاهد الآن",
        "trailer": "الإعلان", "addFav": "إضافة للمفضلة", "removeFav": "إزالة من المفضلة",
        "season": "الموسم %d", "episode": "الحلقة %d", "minutes": "%d دقيقة", "noEpisodes": "لا توجد حلقات",
        "watched": "تمت مشاهدة %d٪", "seasonEpisode": "الموسم %d · الحلقة %d", "seasonsCount": "%d مواسم",
        "votes": "%d تقييم", "cast": "طاقم التمثيل", "director": "المخرج", "newBadge": "جديد", "movie": "فيلم", "seriesOne": "مسلسل",
        "empty": "لا يوجد محتوى هنا", "emptyFav": "ستظهر مفضلتك هنا", "searchHint": "ابحث في القنوات والأفلام والمسلسلات",
        "searchPrompt": "اكتب حرفين على الأقل", "noResults": "لا توجد نتائج",
        "subscriptions": "الاشتراكات", "active": "نشط", "use": "استخدام", "refresh": "تحديث", "delete": "حذف",
        "tmdb": "TMDB — التقييمات والصور", "tmdbKey": "مفتاح TMDB (v3) أو رمز القراءة",
        "tmdbOn": "متصل — التفاصيل والصور من TMDB",
        "tmdbOff": "أضف مفتاحك المجاني من themoviedb.org/settings/api لعرض التقييمات والبوسترات العربية والنبذة وطاقم التمثيل والإعلانات",
        "tmdbInvalid": "رفض TMDB هذا المفتاح، أو لا يوجد اتصال", "tmdbSaved": "تم ربط TMDB", "save": "حفظ", "remove": "إزالة",
        "tmdbAttribution": "يستخدم هذا التطبيق واجهة TMDB البرمجية لكنه غير معتمد أو مصدّق من TMDB.",
        "clearHistory": "مسح سجل المشاهدة", "about": "حول التطبيق",
        "aboutDesc": "مشغل لاشتراكات IPTV التي تملك صلاحية استخدامها. لا يوفر NOVA أي محتوى.",
        "matches": "أهم المباريات", "today": "اليوم", "tomorrow": "غداً", "matchLive": "مباشر", "matchHT": "استراحة",
        "matchFT": "انتهت", "postponed": "مؤجلة", "noMatches": "لا توجد مباريات مهمة في هذا اليوم",
        "opensIn": "تفتح القناة بعد %@", "pickChannel": "اختر القناة", "noChannel": "لا توجد قناة مطابقة في اشتراكك",
        "audio": "الصوت", "subtitles": "الترجمة", "off": "إيقاف", "playbackError": "تعذّر تشغيل البث.",
        "close": "إغلاق", "notInGuide": "لم أجد المباراة في دليل البرامج — قنوات الناقل الرسمي:", "knownFor": "اشتهر بـ", "notInSub": "«%@» غير متوفر في اشتراكك", "matchPrefs": "الفرق والدوريات المفضلة", "onlyFavTeams": "فرقي فقط", "onlyFavHint": "اعرض مباريات أنديتك ومنتخباتك المفضلة فقط.", "favTeams": "الفرق المفضلة", "noFavTeams": "لا توجد فرق مفضلة بعد — أضفها من الأسفل", "addTeams": "أضف فرقاً من", "followedComps": "البطولات المتابعة", "noFavMatches": "لا توجد مباريات لفرقك في هذا اليوم", "findingChannel": "جارٍ البحث عن القناة الناقلة للمباراة…", "noTracks": "لا توجد مسارات أخرى في هذا البث", "scrubHint": "◀ ▶ للتقديم والترجيع","liveBadge": "مباشر", "showMatches": "شريط المباريات في الرئيسية", "matchesSection": "المباريات"
    ]
}
