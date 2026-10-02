package io.github.cc666debug.halloweencaster

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

/** Casts with Google's standard speaker player, the same one the web app uses. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setResumeSavedSession(true)
            .setStopReceiverApplicationWhenEndingSession(true)
            // A media session and notification for the speaker: Android's volume panel shows the speaker's slider,
            // and there are controls on the lock screen. Tapping the notification opens the app.
            .setCastMediaOptions(
                CastMediaOptions.Builder()
                    .setMediaSessionEnabled(true)
                    .setNotificationOptions(
                        NotificationOptions.Builder().setTargetActivityClassName(MainActivity::class.java.name).build()
                    )
                    .build()
            )
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
