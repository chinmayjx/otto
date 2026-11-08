package io.github.otto

import android.app.Application
import android.webkit.WebView

/** The browser's process (":browser") gives its WebViews their own data directory, as Android requires of a second WebView process. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (getProcessName().endsWith(":browser")) WebView.setDataDirectorySuffix("browser")
    }
}
