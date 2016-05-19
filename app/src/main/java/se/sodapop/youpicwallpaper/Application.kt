package se.sodapop.youpicwallpaper

import android.app.Application
import timber.log.Timber

class YouPicWallpaper : Application() {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}