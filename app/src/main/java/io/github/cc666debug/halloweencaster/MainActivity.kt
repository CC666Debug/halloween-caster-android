package io.github.cc666debug.halloweencaster

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
        web.webChromeClient = WebChromeClient()
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        web.loadUrl(APP_URL)
    }

    // ── Phone volume keys ───────────────────────────

    // While casting, the volume keys turn the speaker up and down (like YouTube Music), 5% a press.
    // Quick repeated presses build on the level we just asked for, not on the speaker's slower report.
    private var keyVolume = 0.0
    private var keyVolumeAt = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val s = session
        if ((up || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && s != null && s.isConnected) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val now = SystemClock.uptimeMillis()
                val base = if (now - keyVolumeAt < 1500) keyVolume else s.volume
                keyVolume = (Math.round(base * 20) + if (up) 1 else -1).coerceIn(0, 20) / 20.0
                keyVolumeAt = now
                try {
                    if (s.isMute && up) s.isMute = false
                    s.volume = keyVolume
                } catch (e: Exception) {}
            }
            return true   // the phone's own volume stays where it is
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
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
            .put("volume", s.volume)
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
        session = s
        s.remoteMediaClient?.registerCallback(mediaCallback)
        s.addCastListener(volumeListener)
    }

    private fun detach() {
        session?.remoteMediaClient?.unregisterCallback(mediaCallback)
        session?.removeCastListener(volumeListener)
        session = null
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(s: CastSession) = sendSession("SESSION_STARTING", null)
        override fun onSessionStarted(s: CastSession, sessionId: String) {
            attach(s)
            sendSession("SESSION_STARTED", s)
            sendResult(pickId, true); pickId = 0
            sendStatus()
        }
        override fun onSessionStartFailed(s: CastSession, error: Int) {
            detach()
            sendSession("SESSION_START_FAILED", null, "session_error ($error)")
            sendResult(pickId, false, "session_error ($error)"); pickId = 0
        }
        override fun onSessionEnding(s: CastSession) = sendSession("SESSION_ENDING", s)
        override fun onSessionEnded(s: CastSession, error: Int) {
            detach()
            sendSession("SESSION_ENDED", null)
        }
        override fun onSessionResuming(s: CastSession, sessionId: String) {}
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {
            attach(s)
            sendSession("SESSION_RESUMED", s)
            sendStatus()
        }
        override fun onSessionResumeFailed(s: CastSession, error: Int) {
            detach()
            sendSession("SESSION_ENDED", null)
        }
        override fun onSessionSuspended(s: CastSession, reason: Int) {}
    }

    // ── Commands from the page ──────────────────────

    private inner class Bridge {
        private fun json(args: String) = try { JSONObject(args) } catch (e: Exception) { JSONObject() }

        /** The page is loaded: tell it where things stand, and rejoin a speaker that's still going. */
        @JavascriptInterface fun ready() = main.post {
            sendCastState()
            castContext.sessionManager.currentCastSession?.let {
                attach(it)
                sendSession("SESSION_RESUMED", it)
                sendStatus()
            }
        }

        /** The cast button: Android's own speaker list, or the speaker's controls when connected. */
        @JavascriptInterface fun pickSpeaker(id: Int, args: String) = main.post {
            val selector = castContext.mergedSelector
            if (selector == null) { sendResult(id, false, "no_speakers"); return@post }
            if (session != null) {
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
            try { session?.volume = json(args).optDouble("level", 0.5); sendResult(id, true) }
            catch (e: Exception) { sendResult(id, false, e.message) }
        }

        @JavascriptInterface fun setMuted(id: Int, args: String) = main.post {
            try { session?.isMute = json(args).optBoolean("muted"); sendResult(id, true) }
            catch (e: Exception) { sendResult(id, false, e.message) }
        }

        @JavascriptInterface fun endSession(id: Int, args: String) = main.post {
            castContext.sessionManager.endCurrentSession(json(args).optBoolean("stopCasting", true))
            sendResult(id, true)
        }
    }

    companion object {
        const val APP_URL = "https://cc666debug.github.io/halloween-caster/"
    }
}
