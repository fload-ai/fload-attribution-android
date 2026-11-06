package com.fload.samples.kmp

/**
 * What shared code needs from Fload's attribution SDK: one call, to link the
 * install to your app user id (for example RevenueCat's appUserID) once you know it.
 *
 * Each app implements it with its native SDK: [FloadInstallAttribution] on Android
 * (Kotlin SDK), a few lines of Swift on iOS (Swift SDK). `Fload.configure` stays in
 * each app's own startup code, where the native SDKs want it.
 */
interface InstallAttribution {
    fun setUserId(userId: String)
}

/** Stands in for your shared session or purchases code. */
class AppSession(private val attribution: InstallAttribution) {
    var userId: String? = null
        private set

    /** Call it with RevenueCat's app user id after configure and after every login. */
    fun signedIn(appUserId: String) {
        userId = appUserId
        attribution.setUserId(appUserId)
    }
}
