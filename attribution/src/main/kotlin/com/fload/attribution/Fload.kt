package com.fload.attribution

import android.content.Context
import com.fload.attribution.core.SdkInfo

/**
 * Fload install attribution.
 *
 * Call [configure] once, in `Application.onCreate`. On the app's first launch
 * the SDK reports the install to Fload with the Play Install Referrer, and
 * keeps it queued on disk until Fload has it, across launches and network loss.
 *
 * ```kotlin
 * Fload.configure(this, key = "fload_pk_…")
 * Fload.setUserId(Purchases.sharedInstance.appUserID)   // optional
 * ```
 */
public object Fload {
    /** The SDK's version, sent with every install. */
    public const val SDK_VERSION: String = SdkInfo.VERSION

    /**
     * Starts the SDK. Returns at once; disk and network work runs on the SDK's own thread.
     *
     * @param context any context; the SDK keeps only the application context.
     * @param key the app's public write key (`fload_pk_…`) from Fload. It names the app only.
     * @param options where to send and how much to log. The defaults are right for production.
     */
    @JvmStatic
    @JvmOverloads
    public fun configure(context: Context, key: String, options: FloadOptions = FloadOptions()) {
        Runtime.configure(context, key, options)
    }

    /**
     * Links your app's user id (for example RevenueCat's `appUserID`) to this install.
     * Optional. Safe to call before [configure] and on every launch: the same id is sent once.
     */
    @JvmStatic
    public fun setUserId(userId: String) {
        Runtime.setUserId(userId)
    }

    /**
     * This install's random reference, or null until the SDK has loaded its
     * state (just after [configure]). Useful when asking Fload support about one install.
     */
    @JvmStatic
    public val installReference: String?
        get() = Runtime.installReference
}

/** How the SDK sends and logs. */
public class FloadOptions @JvmOverloads constructor(
    /** Fload's ingest endpoint. Change it only to test against your own Fload API. */
    public val endpoint: String = "https://ingest.fload.com",
    /** What the SDK writes to Logcat (tag `FloadAttribution`). */
    public val logLevel: FloadLogLevel = FloadLogLevel.WARNING,
)

/** The SDK's log level. It never logs the write key, the referrer or a user id. */
public enum class FloadLogLevel {
    NONE,
    ERROR,
    WARNING,
    INFO,
    DEBUG,
}
