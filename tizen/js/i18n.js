/* NOVA IPTV for Samsung Tizen TV — translations (Arabic / English). ES2017, no build step. */
(function (global) {
  'use strict';

  var STRINGS = {
    en: {
      tagline: 'Your IPTV, beautifully organised. Live TV, movies and series in one premium player.',
      getStarted: 'Get started', chooseLanguage: 'Choose your language', continueLabel: 'Continue',
      addSubscription: 'Add subscription', editSubscription: 'Edit subscription',
      xtream: 'Xtream Codes', m3u: 'M3U URL',
      name: 'Name (optional)', server: 'Server URL', username: 'Username', password: 'Password',
      m3uUrl: 'M3U / M3U8 URL', userAgent: 'Custom User-Agent (optional)',
      liveFormat: 'Live format', saveLoad: 'Save & load', test: 'Test connection', testing: 'Testing…',
      connectionOk: 'Connection successful', expiresOn: 'Expires on {0}', maxConn: 'Max connections: {0}',
      legal: 'Use only subscriptions you are legally entitled to access. NOVA provides no channels and does not bypass DRM.',
      stageConnecting: 'Verifying subscription…', stageContent: 'Loading channels, movies and series…',
      stagePlaylist: 'Reading playlist…', stageSaving: 'Saving…', stageDone: 'Ready!',
      items: '{0} items', syncFailed: 'Could not load the subscription', retry: 'Retry', back: 'Back',
      live: 'Live TV', movies: 'Movies', series: 'Series', favorites: 'Favorites', search: 'Search', settings: 'Settings',
      all: 'All', recentWatched: 'Recently watched', recentAdded: 'Recently added',
      continueWatching: 'Continue watching', play: 'Play', resume: 'Resume', startOver: 'Start over',
      addFav: 'Add to favorites', removeFav: 'Remove from favorites', watch: 'Watch', guide: 'TV guide',
      season: 'Season {0}', episode: 'Episode {0}', minutes: '{0} min', noEpisodes: 'No episodes available',
      noInfo: 'No programme information', now: 'Now', next: 'Next',
      emptyChannels: 'No channels in this category', emptyContent: 'Nothing here yet',
      emptyFavorites: 'Your favorites will appear here', searchHint: 'Search channels, movies, series',
      searchPrompt: 'Type at least 2 characters', noResults: 'No results',
      subscriptions: 'Subscriptions', active: 'Active', refresh: 'Refresh', remove: 'Delete',
      lastUpdate: 'Updated {0}', language: 'Language', parental: 'Parental control', setPin: 'Set PIN',
      changePin: 'Change PIN', removePin: 'Remove PIN', enterPin: 'Enter PIN', newPin: 'New PIN (4–8 digits)',
      wrongPin: 'Wrong PIN', hiddenCats: 'Hidden categories',
      hiddenCatsDesc: 'Hidden categories disappear from lists and search. Protected by your PIN if set.',
      hidden: 'Hidden', visible: 'Visible', clearHistory: 'Clear watch history', historyCleared: 'Watch history cleared',
      buffer: 'Buffer size', seconds: '{0} seconds', about: 'About',
      aboutDesc: 'A player for IPTV subscriptions you are authorised to use. NOVA does not provide any content.',
      confirmDelete: 'Delete this subscription?', yes: 'Yes', no: 'No', ok: 'OK', cancel: 'Cancel',
      exitQ: 'Exit NOVA IPTV?', exit: 'Exit',
      reconnecting: 'Reconnecting… ({0})', audio: 'Audio', subtitles: 'Subtitles', off: 'Off', aspect: 'Aspect',
      aspectFit: 'Fit', aspectFill: 'Fill', aspectAuto: 'Original',
      liveBadge: 'LIVE', catchup: 'Archive', watchArchive: 'Watch from archive',
      errNetwork: 'No connection to the server. Check your internet connection.',
      errTimeout: 'The server took too long to respond.',
      errAuth: 'Invalid username or password, or access denied by the provider.',
      errExpired: 'This subscription is expired or disabled.',
      errHttp: 'The server responded with an error (HTTP {0}).',
      errNotPlaylist: 'This is not a valid M3U playlist.', errParse: 'The provider returned unreadable data.',
      errInvalid: 'Please check the address and required fields.', errUnknown: 'Something went wrong.',
      errPlayback: 'The stream could not be played.',
      errPlaybackHint: 'The channel may be offline, the connection limit reached or the format unsupported.',
      noSubscription: 'No subscription yet.', other: 'Other',
      home: 'Home', whoWatching: "Who's watching?", whoHint: 'Each subscription keeps its own favorites and history',
      manageSubs: 'Manage subscriptions', switchProfile: 'Switch profile', daysLeft: '{0} days left', expired: 'Expired',
      latestMovies: 'Latest movies', latestSeries: 'Latest series', recentChannels: 'Recently watched channels', seeAll: 'See all',
      details: 'Details', watchNow: 'Watch now', trailer: 'Trailer', cast: 'Cast', director: 'Director', newBadge: 'NEW',
      typeMovie: 'Movie', typeSeries: 'Series', watchedPct: '{0}% watched', seasonEpisode: 'Season {0} · Episode {1}',
      votes: '{0} votes', seasonsCount: '{0} seasons',
      tmdbSection: 'TMDB — ratings & artwork', tmdbKey: 'TMDB API key', tmdbOn: 'Connected — details and artwork come from TMDB',
      tmdbOff: 'Add your free key (themoviedb.org/settings/api) for ratings, Arabic posters, overviews, cast and trailers',
      tmdbInvalid: 'TMDB rejected this key, or there is no connection', tmdbSaved: 'TMDB connected', tmdbChecking: 'Checking…',
      tmdbAttribution: 'This product uses the TMDB API but is not endorsed or certified by TMDB.', save: 'Save',
      matches: 'Top matches', today: 'Today', tomorrow: 'Tomorrow', matchLive: 'LIVE', matchHt: 'Half-time', matchFt: 'Full-time',
      matchPostponed: 'Postponed', noMatches: 'No top matches on this day', opensIn: 'Channel opens in {0}', findingChannel: 'Finding the channel…',
      pickChannel: 'Choose the channel', noChannel: 'No matching channel in your subscription',
      matchesSection: 'Matches', showMatches: 'Matches strip on Home', myTeams: 'My teams & competitions',
      onlyMyTeams: "Only my teams' matches", favTeams: 'Favourite teams', addTeam: 'Add team', clubComps: 'Club competitions',
      natComps: 'National teams', teamsError: "Couldn't load teams", on: 'On',
      keysHint: 'CH ▲▼ change channel · INFO details · BACK exit'
    },
    ar: {
      tagline: 'اشتراكك بتنظيم أنيق. بث مباشر وأفلام ومسلسلات في مشغل واحد فاخر.',
      getStarted: 'ابدأ الآن', chooseLanguage: 'اختر لغتك', continueLabel: 'متابعة',
      addSubscription: 'إضافة اشتراك', editSubscription: 'تعديل الاشتراك',
      xtream: 'Xtream Codes', m3u: 'رابط M3U',
      name: 'الاسم (اختياري)', server: 'رابط الخادم', username: 'اسم المستخدم', password: 'كلمة المرور',
      m3uUrl: 'رابط M3U / M3U8', userAgent: 'User-Agent مخصص (اختياري)',
      liveFormat: 'صيغة البث', saveLoad: 'حفظ وتحميل', test: 'اختبار الاتصال', testing: 'جارٍ الاختبار…',
      connectionOk: 'تم الاتصال بنجاح', expiresOn: 'ينتهي في {0}', maxConn: 'الحد الأقصى للاتصالات: {0}',
      legal: 'استخدم فقط الاشتراكات التي يحق لك قانونياً الوصول إليها. لا يوفر NOVA أي قنوات ولا يتجاوز DRM.',
      stageConnecting: 'جارٍ التحقق من الاشتراك…', stageContent: 'جارٍ تحميل القنوات والأفلام والمسلسلات…',
      stagePlaylist: 'جارٍ قراءة القائمة…', stageSaving: 'جارٍ الحفظ…', stageDone: 'جاهز!',
      items: '{0} عنصر', syncFailed: 'تعذر تحميل الاشتراك', retry: 'إعادة المحاولة', back: 'رجوع',
      live: 'البث المباشر', movies: 'الأفلام', series: 'المسلسلات', favorites: 'المفضلة', search: 'بحث', settings: 'الإعدادات',
      all: 'الكل', recentWatched: 'شوهدت مؤخراً', recentAdded: 'أضيفت مؤخراً',
      continueWatching: 'متابعة المشاهدة', play: 'تشغيل', resume: 'استئناف', startOver: 'من البداية',
      addFav: 'إضافة للمفضلة', removeFav: 'إزالة من المفضلة', watch: 'مشاهدة', guide: 'دليل البرامج',
      season: 'الموسم {0}', episode: 'الحلقة {0}', minutes: '{0} دقيقة', noEpisodes: 'لا توجد حلقات متاحة',
      noInfo: 'لا توجد معلومات عن البرنامج', now: 'الآن', next: 'التالي',
      emptyChannels: 'لا توجد قنوات في هذا التصنيف', emptyContent: 'لا يوجد محتوى هنا',
      emptyFavorites: 'ستظهر مفضلتك هنا', searchHint: 'ابحث عن قنوات وأفلام ومسلسلات',
      searchPrompt: 'اكتب حرفين على الأقل', noResults: 'لا توجد نتائج',
      subscriptions: 'الاشتراكات', active: 'نشط', refresh: 'تحديث', remove: 'حذف',
      lastUpdate: 'آخر تحديث {0}', language: 'اللغة', parental: 'الرقابة الأبوية', setPin: 'تعيين رمز PIN',
      changePin: 'تغيير رمز PIN', removePin: 'إزالة رمز PIN', enterPin: 'أدخل رمز PIN', newPin: 'رمز PIN جديد (4–8 أرقام)',
      wrongPin: 'رمز PIN غير صحيح', hiddenCats: 'التصنيفات المخفية',
      hiddenCatsDesc: 'تختفي التصنيفات المخفية من القوائم والبحث، ومحمية برمز PIN إن وُجد.',
      hidden: 'مخفي', visible: 'ظاهر', clearHistory: 'مسح سجل المشاهدة', historyCleared: 'تم مسح سجل المشاهدة',
      buffer: 'حجم التخزين المؤقت', seconds: '{0} ثانية', about: 'حول التطبيق',
      aboutDesc: 'مشغل لاشتراكات IPTV التي تملك صلاحية استخدامها. لا يوفر NOVA أي محتوى.',
      confirmDelete: 'حذف هذا الاشتراك؟', yes: 'نعم', no: 'لا', ok: 'موافق', cancel: 'إلغاء',
      exitQ: 'الخروج من NOVA IPTV؟', exit: 'خروج',
      reconnecting: 'جارٍ إعادة الاتصال… ({0})', audio: 'الصوت', subtitles: 'الترجمة', off: 'إيقاف', aspect: 'نسبة العرض',
      aspectFit: 'ملاءمة', aspectFill: 'ملء الشاشة', aspectAuto: 'الأصلي',
      liveBadge: 'مباشر', catchup: 'أرشيف', watchArchive: 'مشاهدة من الأرشيف',
      errNetwork: 'لا يوجد اتصال بالخادم. تحقق من اتصال الإنترنت.',
      errTimeout: 'استغرق الخادم وقتاً طويلاً للرد.',
      errAuth: 'اسم المستخدم أو كلمة المرور غير صحيحة، أو رفض المزود الوصول.',
      errExpired: 'هذا الاشتراك منتهي أو معطّل.',
      errHttp: 'استجاب الخادم بخطأ (HTTP {0}).',
      errNotPlaylist: 'هذه ليست قائمة M3U صالحة.', errParse: 'أعاد المزود بيانات لا يمكن قراءتها.',
      errInvalid: 'يرجى التحقق من العنوان والحقول المطلوبة.', errUnknown: 'حدث خطأ ما.',
      errPlayback: 'تعذر تشغيل البث.',
      errPlaybackHint: 'قد تكون القناة متوقفة، أو تم بلوغ حد الاتصالات، أو الصيغة غير مدعومة.',
      noSubscription: 'لا يوجد اشتراك بعد.', other: 'أخرى',
      home: 'الرئيسية', whoWatching: 'من يشاهد؟', whoHint: 'لكل اشتراك مفضلته وسجل مشاهدته الخاص',
      manageSubs: 'إدارة الاشتراكات', switchProfile: 'تبديل الملف', daysLeft: 'باقي {0} يوم', expired: 'منتهي',
      latestMovies: 'أحدث الأفلام', latestSeries: 'أحدث المسلسلات', recentChannels: 'قنوات شاهدتها مؤخراً', seeAll: 'عرض الكل',
      details: 'التفاصيل', watchNow: 'شاهد الآن', trailer: 'الإعلان', cast: 'طاقم التمثيل', director: 'المخرج', newBadge: 'جديد',
      typeMovie: 'فيلم', typeSeries: 'مسلسل', watchedPct: 'تمت مشاهدة {0}٪', seasonEpisode: 'الموسم {0} · الحلقة {1}',
      votes: '{0} تقييم', seasonsCount: '{0} مواسم',
      tmdbSection: 'TMDB — التقييمات والصور', tmdbKey: 'مفتاح TMDB', tmdbOn: 'متصل — التفاصيل والصور من TMDB',
      tmdbOff: 'أضف مفتاحك المجاني (themoviedb.org/settings/api) لعرض التقييمات والبوسترات العربية والنبذة وطاقم التمثيل والإعلانات',
      tmdbInvalid: 'رفض TMDB هذا المفتاح، أو لا يوجد اتصال', tmdbSaved: 'تم ربط TMDB', tmdbChecking: 'جارٍ التحقق…',
      tmdbAttribution: 'يستخدم هذا التطبيق واجهة TMDB البرمجية لكنه غير معتمد أو مصدّق من TMDB.', save: 'حفظ',
      matches: 'أهم المباريات', today: 'اليوم', tomorrow: 'غداً', matchLive: 'مباشر', matchHt: 'استراحة', matchFt: 'انتهت',
      matchPostponed: 'مؤجلة', noMatches: 'لا توجد مباريات مهمة في هذا اليوم', opensIn: 'تفتح القناة بعد {0}', findingChannel: 'جارٍ البحث عن القناة…',
      pickChannel: 'اختر القناة', noChannel: 'لا توجد قناة مطابقة في اشتراكك',
      matchesSection: 'المباريات', showMatches: 'شريط المباريات في الرئيسية', myTeams: 'فرقي والبطولات',
      onlyMyTeams: 'مباريات فرقي فقط', favTeams: 'الفرق المفضلة', addTeam: 'إضافة فريق', clubComps: 'بطولات الأندية',
      natComps: 'بطولات المنتخبات', teamsError: 'تعذّر تحميل الفرق', on: 'تشغيل',
      keysHint: 'CH ▲▼ تغيير القناة · INFO التفاصيل · BACK خروج'
    }
  };

  var lang = 'ar';

  function t(key) {
    var table = STRINGS[lang] || STRINGS.en;
    var s = table[key] != null ? table[key] : (STRINGS.en[key] != null ? STRINGS.en[key] : key);
    for (var i = 1; i < arguments.length; i++) s = s.split('{' + (i - 1) + '}').join(String(arguments[i]));
    return s;
  }

  function setLang(l) {
    lang = STRINGS[l] ? l : 'en';
    document.documentElement.lang = lang;
    document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr';
  }

  function detect() {
    var n = (navigator.language || 'en').toLowerCase();
    return n.indexOf('ar') === 0 ? 'ar' : 'en';
  }

  global.I18n = { t: t, setLang: setLang, detect: detect, get lang() { return lang; } };
})(window);
