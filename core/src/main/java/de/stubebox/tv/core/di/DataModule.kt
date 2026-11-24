package de.stubebox.tv.core.di

import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import de.stubebox.tv.core.backup.BackupManager
import de.stubebox.tv.core.database.BulkInsertHelper
import de.stubebox.tv.core.backup.UserDataResolver
import de.stubebox.tv.core.customize.CustomizationStore
import de.stubebox.tv.core.download.DownloadEngine
import de.stubebox.tv.core.download.DownloadManager
import de.stubebox.tv.core.network.ConnectivityObserver
import de.stubebox.tv.core.network.HttpClient
import de.stubebox.tv.core.parser.M3uParser
import de.stubebox.tv.core.parser.XtreamClient
import de.stubebox.tv.core.launcher.LauncherIntegrationRepository
import de.stubebox.tv.core.launcher.LauncherLaunchResolver
import de.stubebox.tv.core.launcher.LauncherRecommendationPlanner
import de.stubebox.tv.core.repository.EpgRepository
import de.stubebox.tv.core.repository.SeriesRepository
import de.stubebox.tv.core.repository.SourceRepository
import de.stubebox.tv.core.tv.TvHomeRepository
import de.stubebox.tv.core.tv.LiveLogoPosterArt
import de.stubebox.tv.core.update.UpdateManager
import de.stubebox.tv.core.sync.SyncManager
import de.stubebox.tv.core.sync.work.CatalogSyncScheduler
import de.stubebox.tv.core.sync.work.EpgSyncScheduler
import de.stubebox.tv.core.weather.WeatherRepository
import java.util.concurrent.TimeUnit

/** Networking, parsers, sync engine, and repositories. */
val dataModule = module {
    // Live snapshot of the global proxy. Backs OkHttp's ProxySelector/Authenticator AND mpv's http-proxy,
    // so the proxy can be toggled at runtime without rebuilding the singleton OkHttpClient below.
    single { de.stubebox.tv.core.network.ProxyConfigHolder(get<de.stubebox.tv.core.settings.SettingsRepository>().proxyConfig) }
    // Live snapshot of the global custom DNS (plain UDP or DoH). Same pattern as proxy — reads the
    // live DataStore snapshot so DNS can be toggled without rebuilding the OkHttpClient singleton.
    single { de.stubebox.tv.core.network.DnsConfigHolder(get<de.stubebox.tv.core.settings.SettingsRepository>().dnsConfig) }
    single {
        val proxyHolder = get<de.stubebox.tv.core.network.ProxyConfigHolder>()
        val dnsHolder = get<de.stubebox.tv.core.network.DnsConfigHolder>()
        val trust = de.stubebox.tv.core.network.ExtraTrustAnchors(
            androidContext().resources.openRawResource(de.stubebox.tv.core.R.raw.isrg_extra_roots),
        )
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)  // fast fail on dead host
            .readTimeout(20, TimeUnit.SECONDS)    // detect mid-sync disconnect quickly
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)       // let SyncManager handle retries, not OkHttp
            // Global proxy (Approach 1): a ProxySelector/Authenticator that read the live snapshot, so
            // enabling/disabling the proxy takes effect immediately. Proxy off = DIRECT = exact prior
            // behavior. Credentials are never logged.
            .proxySelector(proxyHolder.proxySelector)
            .proxyAuthenticator(proxyHolder.proxyAuthenticator)
            // Global custom DNS: a Dns that reads the live snapshot so the DNS server can be changed
            // at runtime without rebuilding this singleton client. Off = system DNS = exact prior behavior.
            .dns(dnsHolder.dns)
            // Platform trust plus the Let's Encrypt roots older/current devices lack. Clients
            // derived with newBuilder() (streaming, players) inherit it.
            .sslSocketFactory(trust.sslSocketFactory, trust.trustManager)
            // Force HTTP/1.1. Several IPTV panels / EPG hosts (and their CDNs) have flaky HTTP/2 stacks
            // that send RST_STREAM(PROTOCOL_ERROR) on large/slow responses — e.g. big EPG XML downloads
            // which OkHttp surfaces as "stream was reset: PROTOCOL_ERROR". HTTP/1.1 sidesteps it
            // with no real downside for our mostly-single-stream downloads.
            .protocols(listOf(Protocol.HTTP_1_1))
            // Default a player-style UA for any request that didn't set one (e.g. Coil image loads),
            // since some IPTV panels reject the stock OkHttp UA. Per-source UAs still override this.
            .addInterceptor { chain ->
                val req = chain.request()
                val out = if (req.header("User-Agent").isNullOrBlank()) {
                    req.newBuilder().header("User-Agent", HttpClient.DEFAULT_USER_AGENT).build()
                } else {
                    req
                }
                chain.proceed(out)
            }
            .build()
    }
    // The playback engines' client: same proxy/DNS/UA/protocol configuration as the singleton above,
    // but its own connection pool, so a live stop can evict *stream* sockets without dropping keep-alive
    // for EPG, panel API, metadata and image traffic.
    single { de.stubebox.tv.core.network.StreamingHttpClient(get()) }
    // Live timeshift buffers download on the streaming client, as the players would.
    single {
        de.stubebox.tv.core.timeshift.TimeshiftManager(
            androidContext(),
            get<de.stubebox.tv.core.network.StreamingHttpClient>().client,
        )
    }
    single { HttpClient(get()) }
    single { ConnectivityObserver(androidContext()) }
    // Category locks: the in-memory unlocks of this session, and the view that applies them (settings,
    // session, categoryDao).
    single { de.stubebox.tv.core.customize.CategoryUnlockSession() }
    single { de.stubebox.tv.core.categorylock.MenuChangeGuard(get(), get(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)) }
    single { de.stubebox.tv.core.customize.CategoryLockView(get(), get(), get()) }
    // One count of wrong PINs per lock (profile, category, Settings) for the whole app, so moving to
    // another category or section does not start a fresh run of guesses, and guessing at one lock never
    // makes another wait. Monotonic clock: changing the time cannot end a wait.
    single { de.stubebox.tv.core.categorylock.PinAttemptLimiters(android.os.SystemClock::elapsedRealtime) }
    // Which locked category a downloaded or recorded file the player is on belongs to, for the relock.
    single { de.stubebox.tv.core.categorylock.PlayingFileCategory() }
    single { CustomizationStore(androidContext(), get()) }
    single { de.stubebox.tv.core.customize.LockedNotificationTitles(get(), get(), get(), get(), get(), get()) }
    single { de.stubebox.tv.core.epg.EpgSourceStore(androidContext()) }
    single { de.stubebox.tv.core.player.ForceMpvStore(androidContext(), get()) }
    // Per-channel "watched without a picture" memory, for the mobile audio-only mode.
    single { de.stubebox.tv.core.player.AudioOnlyStore(androidContext(), get()) }
    // Per-item zoom/volume the player remembers (playbackPrefsDao, settings).
    single { de.stubebox.tv.core.player.PlaybackPrefsStore(dao = get(), settings = get(), quirks = get()) }
    single { de.stubebox.tv.core.player.ExternalPlayerLauncher(androidContext()) }
    // store, sourceDao, epgRepository
    single { de.stubebox.tv.core.epg.EpgMigration(get(), get(), get()) }
    single { M3uParser() }
    single { XtreamClient(get()) }
    // Stalker portal: protocol client on the shared OkHttpClient + in-memory sessions.
    single { de.stubebox.tv.core.stalker.StalkerClient(get()) }
    single { de.stubebox.tv.core.stalker.StalkerAuthManager(get()) }
    single { de.stubebox.tv.core.stalker.StreamUrlResolver(get(), get()) }
    // The portal's own guide — the only EPG a Stalker portal that publishes no XMLTV has.
    single { de.stubebox.tv.core.stalker.StalkerEpgLoader(get(), get()) }
    // http, xtreamClient, stalkerAuth — the Test button behind each saved playlist row.
    single { de.stubebox.tv.core.repository.SourceTester(get(), get(), get()) }
    // Measuring how many streams a provider really allows, for the ones that never say.
    single { de.stubebox.tv.core.live.ConnectionProbe(get()) }
    single { de.stubebox.tv.core.live.ProbeChannelSource(get(), get(), get(), get(), get()) }
    single { de.stubebox.tv.core.live.ConnectionLimits(get(), get(), get(), get()) }
    // TMDB metadata enrichment: one provider, three tiers resolved from SettingsRepository.
    // Opaque per-install id sent to the default Worker only, so one abusive install can be capped
    // without blocking the IP address a whole household/carrier NAT shares.
    single { de.stubebox.tv.core.metadata.StubeboxClientId(androidContext()) }
    // Per-install allowance for the shared default Worker (40/min, 150/hr, 400/day). Own key and
    // self-hosted server are never metered.
    single { de.stubebox.tv.core.metadata.MetadataBudget(androidContext()) }
    single<de.stubebox.tv.core.metadata.MetadataProvider> {
        de.stubebox.tv.core.metadata.TmdbProvider(get(), get(), get(), get())
    }
    // provider, metadataDao, settings, overrideStore — the on-demand resolve + cache orchestrator.
    single { de.stubebox.tv.core.metadata.MetadataRepository(get(), get(), get(), get(), get()) }
    single { de.stubebox.tv.core.metadata.OriginalLanguageLookup(get(), get(), get()) }
    // Gates the TMDB Trending download to once every 5–8 days per playlist and holds the shared
    // candidate list; deliberately DataStore, not Room (derived state, no migration, no backup).
    single { de.stubebox.tv.core.trending.TrendingScheduleStore(androidContext()) }
    single { de.stubebox.tv.core.trending.TrendingRepository(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // Per-content TMDB name overrides: DataStore side-store, no Room schema change.
    single { de.stubebox.tv.core.metadata.MetadataOverrideStore(androidContext()) }
    // OpenSubtitles: Worker-proxied REST client + Keystore-sealed
    // per-profile sessions + the sign-in/out orchestrator with one-shot silent re-login.
    single { de.stubebox.tv.core.subtitles.OpenSubtitlesClient(get(), get(), get()) }
    single { de.stubebox.tv.core.subtitles.OpenSubtitlesAuthStore(androidContext()) }
    single { de.stubebox.tv.core.subtitles.OpenSubtitlesAccountManager(get(), get()) }
    // context, client, accountManager, okHttpClient, subtitleDao — search/download/cache orchestration
    single { de.stubebox.tv.core.subtitles.SubtitleRepository(androidContext(), get(), get(), get(), get()) }
    single { WeatherRepository(get(), get()) }
    // Sports Hub: ESPN straight from the device on the shared client (no Worker, no key). Lazy like
    // everything here — none of it exists, let alone fetches, until a sports screen asks (startup zero-work).
    single<de.stubebox.tv.core.sports.SportsSource> { de.stubebox.tv.core.sports.EspnSportsSource(get()) }
    single { de.stubebox.tv.core.sports.SportsRefreshPolicy() }
    single { de.stubebox.tv.core.sports.SportsGate(get()) }
    single {
        val settings = get<de.stubebox.tv.core.settings.SettingsRepository>()
        de.stubebox.tv.core.sports.SportsRepository(
            source = get(),
            policy = get(),
            gate = get(),
            // The active profile's follow list (every catalogue league with "All sports").
            followed = settings.activeSportsConfig.map { it.followedLeagues() },
            liveRefreshSeconds = settings.activeSportsConfig.map { it.liveRefreshSeconds },
            autoRebuild = settings.activeSportsConfig.map { it.autoRebuild },
            dataDir = androidContext().filesDir,
            activity = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        ).also { repository ->
            // Goal alerts re-arm from every board built — and so start only with the repository, never at launch.
            get<de.stubebox.tv.core.sports.MatchAlertScheduler>()
                .follow(repository.board, settings.activeSportsConfig)
        }
    }
    single {
        val settings = get<de.stubebox.tv.core.settings.SettingsRepository>()
        de.stubebox.tv.core.sports.SportsCalendar(
            source = get(),
            gate = get(),
            board = get<de.stubebox.tv.core.sports.SportsRepository>().board,
            followed = settings.activeSportsConfig.map { it.followedLeagues() },
            refreshDays = settings.activeSportsConfig.map { it.calendarRefreshDays },
            dataDir = androidContext().filesDir,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        )
    }
    single { de.stubebox.tv.core.sports.SportsTeams(get(), get(), get()) }
    // "Where is it live": the database reads (Search's own channel ↔ guide mapping) and the cached ranking.
    single<de.stubebox.tv.core.sports.WatchData> {
        de.stubebox.tv.core.sports.RoomWatchData(get(), get(), get(), get(), get(), get(), get())
    }
    single { de.stubebox.tv.core.sports.WatchReader(get()) }
    single { de.stubebox.tv.core.sports.SportOnChannels(get()) }
    single { de.stubebox.tv.core.sports.SportsActivityTracker() }
    // Goal alerts (phone): state file in filesDir so a cache clear cannot drop a match being watched.
    single { de.stubebox.tv.core.sports.MatchNotifications(androidContext(), get()) }
    single {
        de.stubebox.tv.core.sports.MatchAlertScheduler(androidContext(), get(), java.io.File(androidContext().filesDir, "sports"))
    }
    single { de.stubebox.tv.core.sports.SportsAlertsSetup(androidContext(), get(), get(), get(), get(), get()) }
    // Per-item VOD engine pins made with the player's gear toggle (VOD counterpart of ForceMpvStore).
    single { de.stubebox.tv.core.player.VodEngineStore(androidContext(), get()) }
    // Remote (companion) add-source LAN server — one shared instance for Setup + Settings.
    single { de.stubebox.tv.core.companion.CompanionController(androidContext(), get()) }
    // Local sync between two Stubebox devices on the same Wi-Fi. The payload is a
    // backup container, the listener is the companion server, and the merge is the restore path —
    // what is new here is the client, the pairing and the discovery.
    single { de.stubebox.tv.core.sync.local.PairedDeviceStore(androidContext()) }
    single { de.stubebox.tv.core.sync.local.LocalSyncClient() }
    single { de.stubebox.tv.core.sync.local.LocalSyncDiscovery(androidContext()) }
    single {
        de.stubebox.tv.core.sync.local.LocalSyncManager(
            context = androidContext(),
            companion = get(),
            backups = get(),
            paired = get(),
            client = get(),
            discovery = get(),
            autoSync = get(),
            autoServer = get(),
            settings = get(),
            profileDao = get(),
        )
    }
    // Automatic sync with paired devices: its agreements, the paired-only listener on its own port,
    // the passes, and the moments they are tried. The listener and the runner each announce or look
    // for the automatic-sync service, never the manual one.
    single { de.stubebox.tv.core.sync.local.LocalAutoSyncStore(androidContext(), get()) }
    single { de.stubebox.tv.core.sync.local.AutoSyncLocks() }
    single { de.stubebox.tv.core.sync.local.LocalAutoSyncClient() }
    single { de.stubebox.tv.core.sync.local.AutoSyncTransfer(get(), get()) { androidContext().cacheDir } }
    single {
        de.stubebox.tv.core.sync.local.LocalAutoSyncServer(
            store = get(),
            paired = get(),
            transfer = get(),
            locks = get(),
            discovery = de.stubebox.tv.core.sync.local.LocalSyncDiscovery(androidContext(), de.stubebox.tv.core.sync.local.LocalSyncDiscovery.AUTO_SERVICE_TYPE),
            deviceName = { de.stubebox.tv.core.sync.local.localDeviceName(androidContext()) },
        )
    }
    single {
        de.stubebox.tv.core.sync.local.LocalAutoSyncRunner(
            store = get(),
            paired = get(),
            client = get(),
            transfer = get(),
            locks = get(),
            discovery = de.stubebox.tv.core.sync.local.LocalSyncDiscovery(androidContext(), de.stubebox.tv.core.sync.local.LocalSyncDiscovery.AUTO_SERVICE_TYPE),
        )
    }
    single { de.stubebox.tv.core.sync.work.LocalAutoSyncScheduler(androidContext()) }
    single { de.stubebox.tv.core.sync.work.LocalAutoSyncStarter(androidContext(), get(), get(), get()) }
    single { BulkInsertHelper(get()) }
    single {
        de.stubebox.tv.core.sync.ImportFinalizer(
            channelDao = get(),
            movieDao = get(),
            seriesDao = get(),
            db = get(),
            bulkInsertHelper = get(),
            metadataDao = get(),
            epgSourceStore = get(),
        )
    }
    // context, channelDao, movieDao, seriesDao, profileDao, favoriteDao, historyDao, progressDao,
    // contentOrderDao, customCategoryDao, seriesSortOrderDao, tombstoneDao, db
    single { UserDataResolver(androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // The one place a user deletion is written: it records the tombstone local sync needs and does
    // the delete in the same transaction. db, favoriteDao, historyDao, progressDao, customCategoryDao, userData
    single { de.stubebox.tv.core.backup.UserDataWriter(get(), get(), get(), get(), get(), get()) }
    // sourceDao, syncManager, userDataResolver, channelDao, movieDao, seriesDao, categoryDao
    single { SourceRepository(get(), get(), get(), get(), get(), get(), get(), playbackQuirkDao = get(), playbackPrefsDao = get()) }
    // sourceDao, stalkerAuthManager, catalogSyncScheduler, importFinalizer
    single { de.stubebox.tv.core.repository.SourceEditor(get(), get(), get(), get()) }
    // settings, sourceRepository, channelDao, movieDao, seriesDao
    single { de.stubebox.tv.core.nav.NavVisibility(get(), get(), get(), get(), get()) }
    single {
        SyncManager(
            context = androidContext(),
            sourceDao = get(),
            categoryDao = get(),
            channelDao = get(),
            movieDao = get(),
            seriesDao = get(),
            xtream = get(),
            m3u = get(),
            http = get(),
            bulkInsertHelper = get(),
            stalkerClient = get(),
            stalkerAuth = get(),
            activityTracker = get(),
            customize = get(),
            settings = get(),
            connectionLimits = get(),
            catalogBackfillDao = get(),
            catalogSyncScheduler = get(),
            catalogPriority = get(),
        )
    }
    // App-wide "sync running" signal for the shell status pill (every sync funnels through SyncManager).
    // Which VOD category the user is looking at, so the lazy-catalogue drain serves it first.
    single { de.stubebox.tv.core.sync.CatalogPriority(get(), get()) }
    // settings, sourceDao, catalogBackfillDao — Home's "library still loading" note, in both apps.
    single { de.stubebox.tv.core.sync.LibraryLoading(get(), get(), get()) }
    // The drain comes back soon after playback stops instead of waiting out its retry delay.
    // createdAtStart: it has to be listening before the first playback closes.
    single(createdAtStart = true) {
        val scheduler: CatalogSyncScheduler = get()
        de.stubebox.tv.core.sync.BackfillResume(get(), { sourceId ->
            scheduler.enqueueCatalogBackfill(
                sourceId,
                initialDelayMinutes = de.stubebox.tv.core.sync.BackfillResume.RESUME_DELAY_MINUTES,
                onlyIfWaiting = true,
            )
        })
    }
    single { de.stubebox.tv.core.sync.SyncActivityTracker() }
    // Same idea for EPG: EpgSyncWorker reports started/progress/finished here so the pill also reflects
    // guide/EPG downloads (manual resync from Settings, auto startup refresh, …).
    single { de.stubebox.tv.core.sync.EpgActivityTracker() }
    single { de.stubebox.tv.core.sync.TrendingActivityTracker() }
    // epgDao, httpClient, xtreamClient, channelDao, customize, settings, context, db, bulkInsertHelper
    single {
        EpgRepository(
            epgDao = get(),
            http = get(),
            xtream = get(),
            channelDao = get(),
            customize = get(),
            settings = get(),
            context = androidContext(),
            db = get(),
            bulkInsertHelper = get(),
            sourceDao = get(),
            stalkerEpg = get(),
        )
    }
    single { de.stubebox.tv.core.epg.ArchiveGuideFiller(get()) }
    // channelDao, categoryDao, sourceDao, profileDao, customize, settings
    single { de.stubebox.tv.core.live.ChannelVersions(get(), get(), get(), get(), get(), get()) }
    // seriesDao, sourceDao, xtreamClient, userDataResolver, stalkerClient, stalkerAuthManager
    single { SeriesRepository(get(), get(), get(), get(), get(), get()) }
    // sourceDao, movieDao, seriesDao, progressDao
    single { LauncherRecommendationPlanner(get(), get(), get(), get(), get(), get(), get()) }
    // sourceDao, channelDao, movieDao, seriesDao, progressDao
    single { LauncherLaunchResolver(get(), get(), get(), get(), get(), get(), get(), get()) }
    // context, sourceDao, channelDao, movieDao, seriesDao, progressDao, tvProviderProgramDao, customize, settings, localeStore
    single { TvHomeRepository(androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), LiveLogoPosterArt(androidContext(), get())) }
    // planner, resolver, tvHomeRepository
    single { LauncherIntegrationRepository(get(), get(), get()) }
    // Who currently holds a stream on which playlist. Multiview tiles and recordings spend the same
    // provider connections, so both count against one register.
    single { de.stubebox.tv.core.live.OpenStreamRegistry() }
    // What the user is actually watching. Separate from the register above on purpose — see the
    // class doc: that one is the connection budget, this one is a playback signal.
    single { de.stubebox.tv.core.live.WatchSession() }
    // Same idea as the sync trackers: the download engine reports its running transfer here so the
    // shell's status pill can show it in both apps.
    single { de.stubebox.tv.core.download.DownloadActivityTracker() }
    // downloadDao, okHttpClient, sourceDao, movieDao, seriesDao, streamUrlResolver, activityTracker
    // (the middle four are D-3: Stalker downloads resolve the stored cmd at download-start time),
    // then watchSession, openStreamRegistry, settings — the streams a download must leave free
    single { DownloadEngine(androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // context, downloadDao, settings, engine, watchSession
    single { DownloadManager(androidContext(), get(), get(), get(), get()) }
    // Watching vs downloading on a one-stream playlist. createdAtStart: it has to be listening before
    // the first playback opens, and nothing else would ask for it until an app's dialog does.
    single(createdAtStart = true) { de.stubebox.tv.core.download.DownloadWatchGuard(get(), get(), get(), get(), get(), get()) }
    // The recording half of the same idea: several can run at once, so the tracker keeps a
    // line per running recording rather than a single active one.
    single { de.stubebox.tv.core.recording.RecordingActivityTracker() }
    // recordingDao, okHttpClient, sourceDao, streamUrlResolver, openStreamRegistry, settings,
    // connectivity (the metered check that turns "not over mobile data" into a MISSED row rather
    // than a silent wait), tracker
    single {
        de.stubebox.tv.core.recording.RecordingEngine(androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), get())
    }
    // context, recordingDao — the AlarmManager half. Exact alarms where the user allows them,
    // inexact plus a bigger head start where they do not.
    single { de.stubebox.tv.core.recording.RecordingScheduler(androidContext(), get()) }
    // context, recordingDao, sourceDao, settings, openStreamRegistry, engine, scheduler,
    // channelDao + epgDao (series rules: "every showing of this title on this channel" has to read
    // the guide to find out what the showings are)
    // `createdAtStart` is the fix for a defect found on a real television: some manufacturers'
    // auto-start policies never deliver BOOT_COMPLETED to an app the user has not "allowed", so
    // RecordingBootReceiver simply does not run and a recording scheduled across a reboot is never
    // re-armed. Nothing in the app can make an OEM deliver that broadcast — but opening the app is
    // something the user does anyway, and `RecordingManager.init` already re-arms everything and
    // marks what was missed. Left lazy, that only happened once a screen that injects it was opened,
    // which on such a television could be never. Eager, every launch repairs the timers.
    //
    // Safe for a cold start: the constructor sets a flag and launches one background coroutine; the
    // only database read is `recordings` — a handful of rows — and it happens off the main thread.
    single(createdAtStart = true) {
        de.stubebox.tv.core.recording.RecordingManager(
            androidContext(), get(), get(), get(), get(), get(), get(), get(), get(),
        )
    }
    // Programme reminders: context, reminderDao — then reminderDao, scheduler. Eager for the
    // same reason as RecordingManager: every launch re-arms, whether or not BOOT_COMPLETED arrived.
    single { de.stubebox.tv.core.reminder.ReminderScheduler(androidContext(), get()) }
    single(createdAtStart = true) { de.stubebox.tv.core.reminder.ReminderManager(get(), get()) }
    // profileDao, sourceDao, settings, customizationStore, userDataResolver, epgSourceStore,
    // forceMpvStore, vodEngineStore, db, metadataOverrideStore, metadataDao, openSubtitlesAuthStore,
    // backgroundsDir (same folder ingestBackgroundImage writes to — the .stubebox container carries the wallpaper),
    // subtitlesDir (SubtitleRepository's shared cache — the container carries the subtitle files too)
    single {
        BackupManager(
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            java.io.File(androidContext().filesDir, "backgrounds"),
            java.io.File(androidContext().filesDir, "subtitles"),
            get(),
        )
    }
    // profileDao, sourceDao, settings, launcherIntegration, openSubtitlesAccounts — creating,
    // editing, switching and deleting a profile, shared by both apps' profile gates.
    single { de.stubebox.tv.core.profile.ProfileAvatarStore(androidContext()) }
    single { de.stubebox.tv.core.profile.ProfileProtection(get(), get(), get(), get()) }
    single { de.stubebox.tv.core.profile.ProfileManager(get(), get(), get(), get(), get(), get(), get()) }
    // context, okHttpClient — in-app updates from GitHub Releases
    single { UpdateManager(androidContext(), get(), get()) }
    single { CatalogSyncScheduler(androidContext()) }
    single { EpgSyncScheduler(androidContext()) }
    // customize, epgRepository, epgDao, epgSourceStore, epgSyncScheduler — every guide match, both apps.
    single { de.stubebox.tv.core.epg.GuideMatches(get(), get(), get(), get(), get()) }
    // settings, sourceRepository, profileDao, catalogSyncScheduler, epgSyncScheduler, epgSourceStore,
    // epgDao, importFinalizer, vodResort — the Auto refresh settings, run by both apps on start and resume.
    single { de.stubebox.tv.core.sync.AutoRefresh(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // settings, sourceDao, channelDao, catalogSyncScheduler, importFinalizer
    single { de.stubebox.tv.core.sync.M3uVodResort(get(), get(), get(), get(), get()) }
    // profileDao, sourceDao, sourceRepository, backup, settings, connectivity, importFinalizer,
    // launcherIntegration, catalogSyncScheduler, stalkerAuth, xtreamClient, context — onboarding: add a
    // source, sync it, undo it when it fails. Factory, not single: each wizard run owns its own state machine.
    factory { de.stubebox.tv.core.setup.SourceImporter(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), androidContext()) }
}
