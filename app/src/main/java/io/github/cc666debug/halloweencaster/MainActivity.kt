package io.github.cc666debug.halloweencaster

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.PowerManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.ContentValues
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.ValueCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouterParams
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import org.json.JSONObject

/**
 * Shows the Halloween Caster website, but casts through Android's own Google Cast service
 * (like YouTube Music) instead of Chrome's speaker list. The page talks to this class through
 * window.AndroidCast (see android-cast.js on the website); reports go back through
 * window.AndroidCastEvent.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var castContext: CastContext
    private val main = Handler(Looper.getMainLooper())

    private var session: CastSession? = null
    private var pickId = 0   // the page's open "pick a speaker" request, if any

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    // "Back up": the first time, pick a folder; later backups go straight there (📁 Folder picks another).
    private val prefs by lazy { getSharedPreferences("backup", MODE_PRIVATE) }
    private var pendingBackup: Pair<String, String?>? = null   // text null = only pick the folder (pickBackupFolder)
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val b = pendingBackup ?: return@registerForActivityResult
        pendingBackup = null
        if (uri == null) { sendBackup(false, b.first, "cancel"); return@registerForActivityResult }
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) {}
        prefs.edit().putString("folder", uri.toString()).apply()
        val text = b.second
        if (text == null) sendBackup(true, b.first, "picked") else writeBackup(uri, b.first, text, false)
    }

    /** "Save log": the phone's own Save as screen (pick folder + name); answers through onAndroidSaveAs. */
    private var pendingSave: String? = null
    private val saveDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val text = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        val r = JSONObject()
        if (uri == null) r.put("ok", false).put("where", "cancel")
        else try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw Exception("couldn't open the file")
            var where = ""
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) where = it.getString(0) }
            r.put("ok", true).put("where", where)
        } catch (e: Exception) { r.put("ok", false).put("where", "error") }
        main.post { web.evaluateJavascript("window.onAndroidSaveAs && window.onAndroidSaveAs($r)", null) }
    }

    private fun askForFolder(name: String, text: String?) {
        pendingBackup = name to text
        try { pickFolder.launch(null) } catch (e: Exception) { pendingBackup = null; sendBackup(false, name, "error") }
    }

    private fun sendBackup(ok: Boolean, name: String, where: String) = main.post {
        val r = JSONObject().put("ok", ok).put("name", name).put("where", where)
        web.evaluateJavascript("window.onAndroidBackup && window.onAndroidBackup($r)", null)
    }

    /** Writes the file into the folder, replacing one with the same name (so no "(1)" copies). */
    private fun writeBackup(tree: Uri, name: String, text: String, remembered: Boolean) {
        try {
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val dir = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
            var folderName = "your folder"
            contentResolver.query(dir, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) folderName = it.getString(0)
            }
            var file: Uri? = null
            contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                while (file == null && it.moveToNext())
                    if (it.getString(1) == name) file = DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
            }
            // octet-stream keeps the name exactly as given (no extra extension added)
            val target = file ?: DocumentsContract.createDocument(contentResolver, dir, "application/octet-stream", name)
                ?: throw Exception("couldn't create the file")
            contentResolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray()) } ?: throw Exception("couldn't open the file")
            sendBackup(true, name, folderName)
        } catch (e: Exception) {
            // The remembered folder is gone or no longer allowed: ask for one again.
            if (remembered) { prefs.edit().remove("folder").apply(); askForFolder(name, text) }
            else sendBackup(false, name, "error")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        castContext = CastContext.getSharedInstance(this)
        // Android's output picker and volume panel can show and control the speaker.
        MediaRouter.getInstance(this).setRouterParams(MediaRouterParams.Builder().setOutputSwitcherEnabled(true).build())
        // Android 13+ only shows the speaker's media notification (and its controls) with this permission; asked once.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webChromeClient = object : WebChromeClient() {
            // "Restore" in Favorites: let the page pick a backup file.
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try { pickFile.launch("*/*"); true } catch (e: Exception) { fileCallback = null; false }
            }
        }
        web.webViewClient = object : WebViewClient() {
            // Links to other sites (Halloween Radio, YouTube, Spotify...) open in their own apps.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == Uri.parse(APP_URL).host) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidCast")

        castContext.addCastStateListener { sendCastState() }
        castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        startPhoneWatch()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        web.loadUrl(APP_URL)
    }

    // ── Phone volume keys ───────────────────────────

    // While casting, the volume keys turn the speaker up and down (like YouTube Music), 5% a press.
    // The speaker reports its level a beat late (groups even later), and those late reports used to show up
    // between presses: the number jumped backwards, and the next press built on the old level. Now the level we
    // asked for is what the page shows and what the next press builds on for VOL_HOLD after the last change;
    // then the speaker's own level again.
    private var wantVol = -1.0
    private var wantAt = 0L
    private val VOL_HOLD = 2500L
    private fun holding() = wantVol >= 0 && SystemClock.uptimeMillis() - wantAt < VOL_HOLD
    private fun shownVolume(s: CastSession): Double = if (holding()) wantVol else s.volume
    private val volSettled = Runnable { sendStatus() }
    private fun askVolume(s: CastSession, v: Double, unmute: Boolean) {
        wantVol = v.coerceIn(0.0, 1.0); wantAt = SystemClock.uptimeMillis()
        try {
            if (s.isMute && unmute) s.isMute = false
            s.volume = wantVol
        } catch (e: Exception) {}
        sendStatus()
        main.removeCallbacks(volSettled); main.postDelayed(volSettled, VOL_HOLD + 150)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val s = session
        if ((up || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && s != null && s.isConnected) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                askVolume(s, (Math.round(shownVolume(s) * 20) + if (up) 1 else -1).coerceIn(0, 20) / 20.0, up)
            }
            return true   // the phone's own volume stays where it is
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        main.removeCallbacks(giveUpSuspended); main.removeCallbacks(volSettled)
        stopRejoin(keepAlive = true)
        stopPhoneWatch()
        main.removeCallbacks(tick)
        castContext.sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)
        web.destroy()
        super.onDestroy()
    }

    // ── Reports to the page ─────────────────────────

    private fun send(event: JSONObject) = main.post {
        web.evaluateJavascript("window.AndroidCastEvent && window.AndroidCastEvent($event)", null)
    }

    private fun sendResult(id: Int, ok: Boolean, error: String? = null) {
        if (id == 0) return
        send(JSONObject().put("type", "result").put("id", id).put("ok", ok).put("error", error ?: JSONObject.NULL))
    }

    private fun sendCastState() {
        val name = when (castContext.castState) {
            CastState.NO_DEVICES_AVAILABLE -> "NO_DEVICES_AVAILABLE"
            CastState.CONNECTING -> "CONNECTING"
            CastState.CONNECTED -> "CONNECTED"
            else -> "NOT_CONNECTED"
        }
        send(JSONObject().put("type", "castState").put("castState", name))
    }

    private fun sendSession(state: String, s: CastSession?, errorCode: String? = null) {
        val e = JSONObject().put("type", "session").put("sessionState", state)
        s?.castDevice?.friendlyName?.let { e.put("device", it) }
        errorCode?.let { e.put("errorCode", it) }
        send(e)
    }

    private fun sendStatus() {
        val s = session ?: return
        val status = s.remoteMediaClient?.mediaStatus
        val media = status?.let {
            val playerState = when (it.playerState) {
                MediaStatus.PLAYER_STATE_IDLE -> "IDLE"
                MediaStatus.PLAYER_STATE_PLAYING -> "PLAYING"
                MediaStatus.PLAYER_STATE_PAUSED -> "PAUSED"
                MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> "BUFFERING"
                else -> null
            }
            val idleReason = when (it.idleReason) {
                MediaStatus.IDLE_REASON_FINISHED -> "FINISHED"
                MediaStatus.IDLE_REASON_CANCELED -> "CANCELLED"
                MediaStatus.IDLE_REASON_INTERRUPTED -> "INTERRUPTED"
                MediaStatus.IDLE_REASON_ERROR -> "ERROR"
                else -> null
            }
            JSONObject()
                .put("playerState", playerState ?: JSONObject.NULL)
                .put("idleReason", idleReason ?: JSONObject.NULL)
                .put("contentId", it.mediaInfo?.contentId ?: JSONObject.NULL)
        }
        send(JSONObject().put("type", "status")
            .put("media", media ?: JSONObject.NULL)
            .put("volume", if (s.isConnected) shownVolume(s) else JSONObject.NULL)
            .put("muted", s.isMute))
    }

    // ── Following the speaker ───────────────────────

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = sendStatus()
    }
    private val volumeListener = object : Cast.Listener() {
        override fun onVolumeChanged() = sendStatus()
    }

    private fun attach(s: CastSession) {
        val fresh = session == null
        session = s
        lastDev = s.castDevice ?: lastDev
        if (fresh) {
            if (lastNet.isNotEmpty()) log("phone: on $lastNet")
            main.removeCallbacks(tick); tickEl = 0L; tick.run()
        }
        s.remoteMediaClient?.registerCallback(mediaCallback)
        s.addCastListener(volumeListener)
        CastKeepAliveService.start(this, s.castDevice?.friendlyName)   // keeps the app (and the speaker) alive when swiped away
    }

    /** Lets go of the speaker (or group) in Android's own speaker list too. Ending only the cast left the
     *  group marked as in use by this app, so it vanished from the app's list until the phone was rebooted.
     *  Only for the user's own Stop: letting go stops the music on every speaker in the group, so when the
     *  speakers or Google end the cast (Google Home's volume slider does that), the app leaves them playing. */
    private fun releaseRoute() {
        try {
            val router = MediaRouter.getInstance(this)
            if (!router.selectedRoute.isDefaultOrBluetooth) router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
            if (!router.selectedRoute.isDefaultOrBluetooth) router.selectRoute(router.defaultRoute)
        } catch (e: Exception) {}
    }

    // A dropped speaker (Wi-Fi blip, or a speaker taken out of the group) gets a while to come back by itself;
    // after that the cast is ended properly instead of being left hanging.
    private val giveUpSuspended = Runnable {
        val s = castContext.sessionManager.currentCastSession
        if (s == null || !s.isConnected) {
            val dev = s?.castDevice ?: lastDev
            castContext.sessionManager.endCurrentSession(false)
            detach(keepAlive = dev != null && !ownStop())
            sendSession("SESSION_ENDED", null, "gave up after 15s")
            startRejoin(dev)
        }
    }

    private fun detach(keepAlive: Boolean = false) {
        session?.remoteMediaClient?.unregisterCallback(mediaCallback)
        session?.removeCastListener(volumeListener)
        session = null
        main.removeCallbacks(tick); tickEl = 0L
        if (!keepAlive) CastKeepAliveService.stop(this)
    }

    // ── Rejoining after a drop ──────────────────────
    // 2026-11-16 log: the phone falls off Wi-Fi for a moment (no speakers seen, no internet) and the cast ends with
    // code 2154 / 2055 while the speaker plays on. For REJOIN_FOR after such a drop the app waits for the network to
    // come back, looks for that same speaker for up to a minute, and joins it again; the page then picks up what it
    // plays. Nothing runs between those moments (no polling, no wake lock): it reacts to Android's "network back"
    // report. The player notification stays up meanwhile so Android doesn't freeze the app. Never after your own Stop.
    private var rejoinId: String? = null     // CastDevice id of the speaker (or group) that dropped
    private var rejoinName = ""
    private var rejoining = false            // the next session start is our own rejoin
    private var controllerAt = 0L            // the speaker controls (which have Stop) were opened
    private var netWatch: ConnectivityManager.NetworkCallback? = null
    private val REJOIN_FOR = 10 * 60_000L
    private val SCAN_FOR = 60_000L

    private fun ownStop() = SystemClock.elapsedRealtime() - controllerAt < 120_000

    // A line for the page's log. If the page was frozen and the line arrives late, it says when it really happened.
    private fun log(msg: String) {
        val t = System.currentTimeMillis()
        main.post {
            web.evaluateJavascript("(function(t,m){var d=Date.now()-t;window.diag&&diag(d>3000?m+' (phone time '+new Date(t).toTimeString().slice(0,8)+')':m)})($t," + JSONObject.quote(msg) + ")", null)
        }
    }

    // ── Phone watch (1.18, log only) ────────────────
    // So a saved log shows what the PHONE did around a drop: screen, deep sleep, charger, network, Android blocking the
    // app's network, and how long the app itself was paused. Everything here only reacts to Android's own reports
    // (nothing is kept awake, nothing polls the network); lines are written only while casting or rejoining.
    private var lastDev: CastDevice? = null
    private var lastNet = ""
    private var startLogged = false
    private fun watching() = session != null || rejoinId != null

    private val netLog = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) { main.post { lastNet = ""; if (watching()) log("phone: network lost") } }
        override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
            val kind = when {
                nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                else -> "other network"
            }
            var band = ""
            if (kind == "Wi-Fi" && Build.VERSION.SDK_INT >= 29) (nc.transportInfo as? android.net.wifi.WifiInfo)?.frequency?.let { f ->
                band = if (f >= 5925) " 6 GHz" else if (f >= 4900) " 5 GHz" else if (f > 0) " 2.4 GHz" else ""
            }
            val s = kind + band + if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "" else " (no internet)"
            main.post { if (s != lastNet) { lastNet = s; if (watching()) log("phone: on $s") } }
        }
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            main.post { if (watching()) log(if (blocked) "phone: Android BLOCKED this app's network" else "phone: Android unblocked this app's network") }
        }
    }

    private val phoneLog = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON) rejoinScan()   // after a drop: look for the speaker again on wake
            if (!watching()) return
            val pm = getSystemService(PowerManager::class.java)
            log(when (i.action) {
                Intent.ACTION_SCREEN_ON -> "phone: screen on"
                Intent.ACTION_SCREEN_OFF -> "phone: screen off"
                Intent.ACTION_POWER_CONNECTED -> "phone: charger in"
                Intent.ACTION_POWER_DISCONNECTED -> "phone: charger out"
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "phone: power saving " + if (pm.isPowerSaveMode) "on" else "off"
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "phone: deep sleep (Doze) " + if (pm.isDeviceIdleMode) "started" else "ended"
                else -> if (Build.VERSION.SDK_INT >= 33 && i.action == PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED)
                    "phone: light sleep " + (if (pm.isDeviceLightIdleMode) "started" else "ended") else return
            })
        }
    }

    private fun startPhoneWatch() {
        try { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netLog) } catch (e: Exception) {}
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED); addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) addAction(PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED)
        }
        try { if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneLog, f, RECEIVER_NOT_EXPORTED) else registerReceiver(phoneLog, f) } catch (e: Exception) {}
    }

    private fun stopPhoneWatch() {
        try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netLog) } catch (e: Exception) {}
        try { unregisterReceiver(phoneLog) } catch (e: Exception) {}
    }

    /** Once per app start: can Android put the app to sleep? */
    private fun logStartup() {
        if (startLogged) return
        startLogged = true
        try {
            val pm = getSystemService(PowerManager::class.java)
            val ver = packageManager.getPackageInfo(packageName, 0).versionName
            var s = "phone app $ver · battery " + if (pm.isIgnoringBatteryOptimizations(packageName)) "unrestricted" else "OPTIMIZED (Android may put the app to sleep)"
            if (Build.VERSION.SDK_INT >= 28) {
                if (getSystemService(ActivityManager::class.java).isBackgroundRestricted) s += " · background RESTRICTED"
                val b = getSystemService(UsageStatsManager::class.java).appStandbyBucket
                s += " · standby " + when (b) { 5 -> "exempt"; 10 -> "active"; 20 -> "working set"; 30 -> "frequent"; 40 -> "rare"; 45 -> "RESTRICTED"; else -> "$b" }
            }
            log(s)
        } catch (e: Exception) {}
    }

    // While casting: a minute timer that notices when the app didn't run for a while (it never wakes the phone;
    // when the phone sleeps it simply runs late). "CPU asleep" = the whole phone slept; otherwise only the app was frozen.
    private var tickEl = 0L
    private var tickUp = 0L
    private val tick = object : Runnable {
        override fun run() {
            val el = SystemClock.elapsedRealtime(); val up = SystemClock.uptimeMillis()
            if (tickEl > 0 && el - tickEl > 90_000) {
                val gap = el - tickEl; val slept = gap - (up - tickUp)
                log("app didn't run for ${gap / 1000} s · " + if (slept > 5000) "phone asleep ${slept / 1000} s of it" else "phone awake: the app was frozen")
            }
            tickEl = el; tickUp = up
            if (session != null) main.postDelayed(this, 60_000)
        }
    }

    private fun startRejoin(dev: CastDevice?) {
        if (dev == null || ownStop()) return
        stopRejoin(keepAlive = true)
        rejoinId = dev.deviceId; rejoinName = dev.friendlyName ?: "the speaker"
        log("connection to $rejoinName dropped: will rejoin it when the phone is back on the network (10 min)")
        main.postDelayed(rejoinTimeout, REJOIN_FOR)
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { main.post { rejoinScan() } }
            }
            cm.registerDefaultNetworkCallback(cb); netWatch = cb
        } catch (e: Exception) {}
        rejoinScan()
    }

    private val rejoinTimeout = Runnable {
        if (rejoinId != null) log("didn't find $rejoinName again within 10 min; stopped looking")
        stopRejoin(keepAlive = false)
    }

    private val routeWatch = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) { tryRoute(route) }
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) { tryRoute(route) }
    }
    private val scanStop = Runnable { try { MediaRouter.getInstance(this).removeCallback(routeWatch) } catch (e: Exception) {} }

    private fun rejoinScan() {
        if (rejoinId == null || rejoining || session != null) return
        val selector = castContext.mergedSelector ?: return
        val router = MediaRouter.getInstance(this)
        if (router.routes.any { tryRoute(it) }) return
        router.addCallback(selector, routeWatch, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        main.removeCallbacks(scanStop); main.postDelayed(scanStop, SCAN_FOR)
    }

    private fun tryRoute(route: MediaRouter.RouteInfo): Boolean {
        val id = rejoinId ?: return false
        if (rejoining || session != null || !route.isEnabled) return false
        val dev = CastDevice.getFromBundle(route.extras) ?: return false
        // 1.18: a speaker group can come back under a new id (11-17 01:03: Reverend dropped, came back as another id)
        val byName = dev.deviceId != id && (dev.friendlyName ?: route.name) == rejoinName
        if (dev.deviceId != id && !byName) return false
        rejoining = true
        scanStop.run(); main.removeCallbacks(scanStop)
        log("$rejoinName is back" + (if (byName) " (under a new id)" else "") + ": rejoining")
        try { MediaRouter.getInstance(this).selectRoute(route) } catch (e: Exception) { rejoining = false; return false }
        return true
    }

    private fun stopRejoin(keepAlive: Boolean) {
        val was = rejoinId != null
        rejoinId = null; rejoining = false
        main.removeCallbacks(rejoinTimeout); main.removeCallbacks(scanStop); scanStop.run()
        netWatch?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (e: Exception) {} }
        netWatch = null
        if (was && !keepAlive && session == null) CastKeepAliveService.stop(this)
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(s: CastSession) = sendSession("SESSION_STARTING", null)
        override fun onSessionStarted(s: CastSession, sessionId: String) {
            main.removeCallbacks(giveUpSuspended)
            val wasRejoin = rejoining
            stopRejoin(keepAlive = true)
            attach(s)
            if (wasRejoin) {   // the page treats it like reopening the app: it picks up what the speaker is playing
                log("rejoined " + (s.castDevice?.friendlyName ?: "the speaker") + " after the drop")
                sendSession("SESSION_RESUMED", s); sendStatus(); return
            }
            sendSession("SESSION_STARTED", s)
            sendResult(pickId, true); pickId = 0
            sendStatus()
        }
        override fun onSessionStartFailed(s: CastSession, error: Int) {
            if (rejoining) {   // our own rejoin didn't take: quietly try again while the window lasts
                rejoining = false
                log("rejoin failed (code $error), trying again")
                main.postDelayed({ rejoinScan() }, 15000)
                return
            }
            // 1.18: Android sometimes ends a cast that was already playing with "start failed" instead of "ended"
            // (11-16 23:57 code 2271, 11-17 01:03 code 2251, phone asleep, speaker played on). That's a drop: rejoin.
            if (session != null && !ownStop()) {
                val dev = s.castDevice ?: lastDev
                detach(keepAlive = true)
                sendSession("SESSION_ENDED", null, "start failed, code $error")
                startRejoin(dev)
                return
            }
            detach()
            sendSession("SESSION_START_FAILED", null, "session_error ($error)")
            sendResult(pickId, false, "session_error ($error)"); pickId = 0
        }
        override fun onSessionEnding(s: CastSession) = sendSession("SESSION_ENDING", s)
        override fun onSessionEnded(s: CastSession, error: Int) {
            main.removeCallbacks(giveUpSuspended)
            val drop = error != 0 && !ownStop()
            detach(keepAlive = drop)
            sendSession("SESSION_ENDED", null, "code $error")
            if (drop) startRejoin(s.castDevice ?: lastDev)
        }
        override fun onSessionResuming(s: CastSession, sessionId: String) {}
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {
            main.removeCallbacks(giveUpSuspended)
            attach(s)
            sendSession("SESSION_RESUMED", s)
            sendStatus()
        }
        override fun onSessionResumeFailed(s: CastSession, error: Int) {
            main.removeCallbacks(giveUpSuspended)
            val drop = !ownStop()
            detach(keepAlive = drop)
            sendSession("SESSION_ENDED", null, "resume failed, code $error")
            if (drop) startRejoin(s.castDevice ?: lastDev)
        }
        override fun onSessionSuspended(s: CastSession, reason: Int) {
            sendSession("SESSION_SUSPENDED", s, "reason $reason")   // only for the log; the page waits for the end or the resume
            main.removeCallbacks(giveUpSuspended)
            main.postDelayed(giveUpSuspended, 15000)
        }
    }

    // ── Commands from the page ──────────────────────

    private inner class Bridge {
        private fun json(args: String) = try { JSONObject(args) } catch (e: Exception) { JSONObject() }

        /** "Share" at the bottom of the page: the phone's share menu (a WebView has no navigator.share). */
        @JavascriptInterface fun shareText(title: String, text: String) = main.post {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, title))
        }

        /** "Save log": lets the user pick where the file goes. */
        @JavascriptInterface fun saveAs(name: String, text: String) = main.post {
            pendingSave = text
            try { saveDoc.launch(name) } catch (e: Exception) { pendingSave = null }
        }

        /** "Back up" in Favorites: write the file to the phone's Downloads folder. */
        @JavascriptInterface fun saveFile(name: String, text: String): Boolean {
            if (Build.VERSION.SDK_INT < 29) return false
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null
            } catch (e: Exception) { false }
        }

        /** "Back up": what the device's list file in the remembered folder already holds, so new songs are added to it. */
        @JavascriptInterface fun readBackup(name: String): String {
            val tree = prefs.getString("folder", null)?.let { Uri.parse(it) } ?: return JSONObject().put("ok", false).toString()
            return try {
                val treeId = DocumentsContract.getTreeDocumentId(tree)
                var file: Uri? = null
                contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId),
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                    while (file == null && it.moveToNext())
                        if (it.getString(1) == name) file = DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
                }
                val text = file?.let { f -> contentResolver.openInputStream(f)?.use { it.readBytes().toString(Charsets.UTF_8) } } ?: ""
                JSONObject().put("ok", true).put("text", text).toString()
            } catch (e: Exception) { JSONObject().put("ok", false).toString() }
        }

        /** Picks the backup folder without writing, so the page can read the list file already there first; answers through onAndroidBackup ("picked"). */
        @JavascriptInterface fun pickBackupFolder(name: String) = main.post { askForFolder(name, null) }

        /** "Back up" with a folder: asks for one the first time (or when pickNew), then saves there; answers through onAndroidBackup. */
        @JavascriptInterface fun saveBackup(name: String, text: String, pickNew: Boolean) = main.post {
            val folder = prefs.getString("folder", null)
            if (folder != null && !pickNew) writeBackup(Uri.parse(folder), name, text, true)
            else askForFolder(name, text)
        }

        /** The page is loaded: tell it where things stand, and rejoin a speaker that's still going. */
        @JavascriptInterface fun ready() = main.post {
            sendCastState()
            logStartup()
            // Only rejoin a speaker that's fully connected; one still reconnecting reports in onSessionResumed.
            castContext.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let {
                attach(it)
                sendSession("SESSION_RESUMED", it)
                sendStatus()
            }
        }

        /** The cast button: Android's own speaker list, or the speaker's controls when connected. */
        @JavascriptInterface fun pickSpeaker(id: Int, args: String) = main.post {
            val selector = castContext.mergedSelector
            if (selector == null) { sendResult(id, false, "no_speakers"); return@post }
            stopRejoin(keepAlive = session != null)
            if (session != null) {
                controllerAt = SystemClock.elapsedRealtime()
                MediaRouteControllerDialog(this@MainActivity).apply {
                    setOnDismissListener { sendResult(id, false, "cancel") }
                }.show()
                return@post
            }
            pickId = id
            MediaRouteChooserDialog(this@MainActivity).apply {
                routeSelector = selector
                // The list closes as soon as a speaker is picked; only a close with nothing
                // connecting counts as cancelled.
                setOnDismissListener {
                    main.postDelayed({
                        val st = castContext.castState
                        if (pickId == id && st != CastState.CONNECTING && st != CastState.CONNECTED) {
                            sendResult(id, false, "cancel"); pickId = 0
                        }
                    }, 700)
                }
            }.show()
        }

        @JavascriptInterface fun load(id: Int, args: String) = main.post {
            val a = json(args)
            val client = session?.remoteMediaClient ?: run { sendResult(id, false, "no_session"); return@post }
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                putString(MediaMetadata.KEY_TITLE, a.optString("title"))
                a.optString("subtitle").takeIf { it.isNotEmpty() }?.let { putString(MediaMetadata.KEY_ARTIST, it) }
                a.optString("image").takeIf { it.isNotEmpty() }?.let { addImage(WebImage(Uri.parse(it))) }
            }
            val info = MediaInfo.Builder(a.optString("url"))
                .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                .setContentType(a.optString("contentType", "audio/mpeg"))
                .setMetadata(metadata)
                .build()
            client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
                .setResultCallback { r -> sendResult(id, r.status.isSuccess, r.status.statusMessage ?: "load_failed") }
        }

        @JavascriptInterface fun pause(id: Int, args: String) = main.post {
            val client = session?.remoteMediaClient ?: run { sendResult(id, false, "no_session"); return@post }
            client.pause().setResultCallback { r -> sendResult(id, r.status.isSuccess, r.status.statusMessage) }
        }

        @JavascriptInterface fun play(id: Int, args: String) = main.post {
            val client = session?.remoteMediaClient ?: run { sendResult(id, false, "no_session"); return@post }
            client.play().setResultCallback { r -> sendResult(id, r.status.isSuccess, r.status.statusMessage) }
        }

        @JavascriptInterface fun stop(id: Int, args: String) = main.post {
            val client = session?.remoteMediaClient ?: run { sendResult(id, false, "no_session"); return@post }
            client.stop().setResultCallback { r -> sendResult(id, r.status.isSuccess, r.status.statusMessage) }
        }

        @JavascriptInterface fun setVolume(id: Int, args: String) = main.post {
            try { val s = session; if (s != null && s.isConnected) askVolume(s, json(args).optDouble("level", 0.5), false); sendResult(id, true) }
            catch (e: Exception) { sendResult(id, false, e.message) }
        }

        @JavascriptInterface fun setMuted(id: Int, args: String) = main.post {
            try { session?.isMute = json(args).optBoolean("muted"); sendResult(id, true) }
            catch (e: Exception) { sendResult(id, false, e.message) }
        }

        @JavascriptInterface fun endSession(id: Int, args: String) = main.post {
            val stop = json(args).optBoolean("stopCasting", true)
            castContext.sessionManager.endCurrentSession(stop)
            if (stop) releaseRoute()
            sendResult(id, true)
        }
    }

    companion object {
        const val APP_URL = "https://cc666debug.github.io/halloween-caster/"
    }
}
