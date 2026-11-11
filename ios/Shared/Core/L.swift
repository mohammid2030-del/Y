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
        "trailer": "Trailer", "ok": "OK", "partsOf": "All parts · %@", "skipIntro": "Skip intro", "nextEpisode": "Next episode", "nextIn": "Next episode in %d", "playNow": "Play now", "trailerSeason": "Trailer of which season?", "nowShowing": "Now", "youtubeMissing": "No trailer found for this title — or the YouTube app isn't installed", "addFav": "Add to favorites", "removeFav": "Remove from favorites",
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
        "close": "Close", "gErrClient": "Google rejected the TV client: check GOOGLE_TV_CLIENT_ID / SECRET (type TVs and Limited Input devices). A new client can take a few minutes to work.", "gErrConsent": "The Google consent screen blocks this account: add your e-mail under Audience → Test users, or publish the app.", "gErrProvider": "Enable Google in Firebase → Authentication → Sign-in method.", "gErrAudience": "Firebase doesn't accept this client: in Firebase → Authentication → Google → Web SDK configuration, add the TV client ID under allowed client IDs.", "googleAccount": "Google account", "signedInAs": "Signed in as %@", "signOut": "Sign out", "googleHint": "Sign in once and your subscriptions appear on every device with the same Google account.", "signInGoogle": "Sign in with Google", "googleWhy": "Your subscriptions, viewers and where everyone stopped follow your Google account to Apple TV, Samsung and your other devices.", "googleStep1": "On your phone, open", "googleStep2": "and enter this code", "googleWaiting": "Waiting for you to approve on the phone…", "newCode": "New code", "addManually": "Add a subscription manually", "later": "Later", "googleFailed": "Could not reach Google. Check the connection and try again.", "googleDenied": "Sign-in was cancelled on the phone.", "chooseSub": "Choose a subscription", "chooseSubHint": "Each subscription has its own viewers", "manageViewers": "Manage viewers", "addViewer": "Add viewer", "editViewer": "Edit viewer", "switchSub": "Switch subscription", "done": "Done", "viewerName": "Name", "viewer": "Viewer", "pinOptional": "PIN (optional, 4 digits)", "newPIN": "New PIN (4 digits)", "removePIN": "Remove PIN", "pinHint": "With a PIN, this viewer's profile asks for it before opening.", "deleteViewer": "Delete viewer", "enterPIN": "Enter PIN", "wrongPIN": "Wrong PIN, try again", "viewers": "Viewers", "switchViewer": "Switch viewer", "cloudOn": "Synced", "cloudOnHint": "Viewers and where each one stopped are synced to every device signed in to this subscription.", "cloudOffHint": "Sync is not set up yet: viewers are saved on this device.", "syncNow": "Sync now", "cloudSynced": "Synced just now", "chooseVersion": "Choose a version", "chooseVersionHint": "This title is in your subscription more than once. Arabic versions are listed first.", "organizing": "Organizing your library…", "sections": "Sections", "allGenres": "All genres", "resetFilters": "Reset", "classifying": "Sorting by genre %d / %d", "genresNeedTMDB": "Add a TMDB key in Settings to filter by genre (action, drama, comedy…).", "versions": "Available versions", "g_action": "Action", "g_drama": "Drama", "g_comedy": "Comedy", "g_romance": "Romance", "g_thriller": "Thriller", "g_horror": "Horror", "g_crime": "Crime", "g_scifi": "Sci-fi & fantasy", "g_adventure": "Adventure", "g_animation": "Animation", "g_family": "Family", "g_mystery": "Mystery", "g_war": "War", "g_documentary": "Documentary", "notInGuide": "The guide doesn't list this match — official broadcaster channels:", "knownFor": "Known for", "notInSub": "“%@” is not in your subscription", "matchPrefs": "Favorite teams & leagues", "onlyFavTeams": "Only my teams", "onlyFavHint": "Show only matches of your favorite clubs and national teams.", "favTeams": "Favorite teams", "noFavTeams": "No favorite teams yet — add them below", "addTeams": "Add teams from", "followedComps": "Followed competitions", "noFavMatches": "None of your teams plays on this day", "findingChannel": "Finding the channel showing this match…", "noTracks": "No other tracks in this stream", "scrubHint": "◀ ▶ to seek","liveBadge": "LIVE", "showMatches": "Matches strip on Home", "matchesSection": "Matches"
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
        "trailer": "الإعلان", "ok": "حسناً", "partsOf": "كل الأجزاء · %@", "skipIntro": "تخطي المقدمة", "nextEpisode": "الحلقة التالية", "nextIn": "الحلقة التالية خلال %d", "playNow": "شغّل الآن", "trailerSeason": "إعلان أي موسم؟", "nowShowing": "الحالي", "youtubeMissing": "لم يُعثر على إعلان لهذا العنوان، أو تطبيق YouTube غير مثبّت", "addFav": "إضافة للمفضلة", "removeFav": "إزالة من المفضلة",
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
        "close": "إغلاق", "gErrClient": "رفضت Google مفتاح التلفزيون: تأكد من GOOGLE_TV_CLIENT_ID و SECRET (نوع TVs and Limited Input devices). المفتاح الجديد قد يحتاج دقائق ليعمل.", "gErrConsent": "شاشة موافقة Google تمنع هذا الحساب: أضف إيميلك في Audience ← Test users أو انشر التطبيق.", "gErrProvider": "فعّل Google في Firebase ← Authentication ← Sign-in method.", "gErrAudience": "Firebase لا يقبل هذا المفتاح: في Firebase ← Authentication ← Google ← Web SDK configuration أضف Client ID التلفزيون ضمن المعرّفات المسموحة.", "googleAccount": "حساب Google", "signedInAs": "مسجّل باسم %@", "signOut": "تسجيل الخروج", "googleHint": "سجّل دخولك مرة واحدة، وتظهر اشتراكاتك في كل جهاز بنفس حساب Google.", "signInGoogle": "تسجيل الدخول بحساب Google", "googleWhy": "اشتراكاتك والمشاهدون ومكان توقف كل واحد ينتقلون مع حساب Google إلى Apple TV وسامسونج وبقية أجهزتك.", "googleStep1": "من جوالك افتح", "googleStep2": "واكتب هذا الرمز", "googleWaiting": "بانتظار موافقتك من الجوال…", "newCode": "رمز جديد", "addManually": "إضافة اشتراك يدوياً", "later": "لاحقاً", "googleFailed": "تعذّر الاتصال بـ Google. تأكد من الاتصال وحاول مرة أخرى.", "googleDenied": "تم إلغاء الدخول من الجوال.", "chooseSub": "اختر الاشتراك", "chooseSubHint": "لكل اشتراك مشاهدوه الخاصون", "manageViewers": "إدارة المشاهدين", "addViewer": "إضافة مشاهد", "editViewer": "تعديل المشاهد", "switchSub": "تغيير الاشتراك", "done": "تم", "viewerName": "الاسم", "viewer": "مشاهد", "pinOptional": "رمز (اختياري، ٤ أرقام)", "newPIN": "رمز جديد (٤ أرقام)", "removePIN": "إزالة الرمز", "pinHint": "إذا وضعت رمزاً، سيُطلب قبل فتح ملف هذا المشاهد.", "deleteViewer": "حذف المشاهد", "enterPIN": "أدخل الرمز", "wrongPIN": "رمز غير صحيح، حاول مرة أخرى", "viewers": "المشاهدون", "switchViewer": "تبديل المشاهد", "cloudOn": "متزامن", "cloudOnHint": "المشاهدون ومكان توقف كل واحد يتزامنون مع كل جهاز يدخل بنفس هذا الاشتراك.", "cloudOffHint": "المزامنة غير مفعّلة بعد: المشاهدون محفوظون على هذا الجهاز.", "syncNow": "زامن الآن", "cloudSynced": "تمت المزامنة الآن", "chooseVersion": "اختر النسخة", "chooseVersionHint": "هذا العمل متوفر بأكثر من نسخة في اشتراكك. النسخ العربية في الأعلى.", "organizing": "جارٍ ترتيب مكتبتك…", "sections": "الأقسام", "allGenres": "كل الأنواع", "resetFilters": "إعادة ضبط", "classifying": "جارٍ التصنيف %d / %d", "genresNeedTMDB": "أضف مفتاح TMDB من الإعدادات لتفعيل التصنيف حسب النوع (أكشن، دراما، كوميديا…).", "versions": "النسخ المتوفرة", "g_action": "أكشن", "g_drama": "دراما", "g_comedy": "كوميديا", "g_romance": "رومانسي", "g_thriller": "إثارة", "g_horror": "رعب", "g_crime": "جريمة", "g_scifi": "خيال علمي", "g_adventure": "مغامرة", "g_animation": "رسوم متحركة", "g_family": "عائلي", "g_mystery": "غموض", "g_war": "حرب", "g_documentary": "وثائقي", "notInGuide": "لم أجد المباراة في دليل البرامج — قنوات الناقل الرسمي:", "knownFor": "اشتهر بـ", "notInSub": "«%@» غير متوفر في اشتراكك", "matchPrefs": "الفرق والدوريات المفضلة", "onlyFavTeams": "فرقي فقط", "onlyFavHint": "اعرض مباريات أنديتك ومنتخباتك المفضلة فقط.", "favTeams": "الفرق المفضلة", "noFavTeams": "لا توجد فرق مفضلة بعد — أضفها من الأسفل", "addTeams": "أضف فرقاً من", "followedComps": "البطولات المتابعة", "noFavMatches": "لا توجد مباريات لفرقك في هذا اليوم", "findingChannel": "جارٍ البحث عن القناة الناقلة للمباراة…", "noTracks": "لا توجد مسارات أخرى في هذا البث", "scrubHint": "◀ ▶ للتقديم والترجيع","liveBadge": "مباشر", "showMatches": "شريط المباريات في الرئيسية", "matchesSection": "المباريات"
    ]
}
