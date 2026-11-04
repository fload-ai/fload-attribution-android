package com.fload.attribution

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.fload.attribution.core.DeviceFacts
import com.fload.attribution.core.SdkLog
import com.fload.attribution.core.Text
import java.util.Locale

/** The app and device facts the install carries. */
internal object AndroidDevice {
    fun facts(context: Context): DeviceFacts {
        val info = packageInfo(context)
        return DeviceFacts(
            appId = Text.appId(context.packageName),
            appVersion = Text.shortText(info?.versionName),
            appBuild = Text.shortText(info?.let(::versionCode)?.toString()),
            osVersion = Text.shortText(Build.VERSION.RELEASE),
            deviceModel = Text.deviceModel(Build.MANUFACTURER, Build.MODEL),
            locale = Text.shortText(Locale.getDefault().toLanguageTag()),
        )
    }

    private fun packageInfo(context: Context): PackageInfo? = try {
        val manager = context.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, 0)
        }
    } catch (error: Exception) {
        null
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
}

/** Logcat, tag `FloadAttribution`, filtered by the configured level. */
internal class AndroidLog(private val level: FloadLogLevel) : SdkLog {
    override fun error(message: String) = write(FloadLogLevel.ERROR, message)
    override fun warning(message: String) = write(FloadLogLevel.WARNING, message)
    override fun info(message: String) = write(FloadLogLevel.INFO, message)
    override fun debug(message: String) = write(FloadLogLevel.DEBUG, message)

    private fun write(at: FloadLogLevel, message: String) {
        if (level == FloadLogLevel.NONE || at > level) return
        when (at) {
            FloadLogLevel.ERROR -> Log.e(TAG, message)
            FloadLogLevel.WARNING -> Log.w(TAG, message)
            FloadLogLevel.INFO -> Log.i(TAG, message)
            else -> Log.d(TAG, message)
        }
    }

    private companion object {
        const val TAG = "FloadAttribution"
    }
}

/** Flushes the queue when the app comes to the foreground and when the network comes back. */
internal class Triggers private constructor() {
    companion object {
        fun register(app: Context, flush: () -> Unit): Triggers {
            (app as? Application)?.registerActivityLifecycleCallbacks(ForegroundCallbacks(flush))
            try {
                val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = flush()
                })
            } catch (error: Exception) {
                // No ACCESS_NETWORK_STATE (stripped by the app's manifest): foreground flushes still run.
            }
            return Triggers()
        }
    }
}

/** Calls [onForeground] when the first activity starts after none was started. */
private class ForegroundCallbacks(private val onForeground: () -> Unit) : Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        if (started == 0) onForeground()
        started += 1
    }

    override fun onActivityStopped(activity: Activity) {
        started = maxOf(0, started - 1)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
