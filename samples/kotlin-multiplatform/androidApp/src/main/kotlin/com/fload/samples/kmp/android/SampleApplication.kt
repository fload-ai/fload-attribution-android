package com.fload.samples.kmp.android

import android.app.Application
import com.fload.attribution.Fload
import com.fload.attribution.FloadLogLevel
import com.fload.attribution.FloadOptions
import com.fload.samples.kmp.AppSession
import com.fload.samples.kmp.FloadInstallAttribution

class SampleApplication : Application() {
    lateinit var session: AppSession
        private set

    override fun onCreate() {
        super.onCreate()
        // Configure in the app, as for any Android app. The sample sends to the local
        // ingest stub on the host (10.0.2.2), never to Fload; drop the options in your app.
        Fload.configure(this, "fload_pk_test_local", FloadOptions("http://10.0.2.2:3901", FloadLogLevel.DEBUG))
        session = AppSession(FloadInstallAttribution())
    }
}
