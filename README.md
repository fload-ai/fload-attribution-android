# Fload Attribution for Android

Counts your app's installs in Fload and attributes them with Google Play's
Install Referrer. No advertising ID, no extra permission prompt.
minSdk 24. One runtime dependency: Google's Play Install Referrer library
(`com.android.installreferrer:installreferrer:2.2`), plus the Kotlin standard
library.

## Install

The library is built by [JitPack](https://jitpack.io) from this repository's
tags. Add the JitPack repository in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then the dependency in your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.fload-ai:fload-attribution-android:1.0.0")
}
```

## Use

```kotlin
import com.fload.attribution.Fload

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Fload.configure(this, key = "fload_pk_…")
    }
}

// Optional: link your user id (e.g. RevenueCat's appUserID) to the install.
Fload.setUserId(Purchases.sharedInstance.appUserID)
```

From Java: `Fload.configure(this, "fload_pk_…");`.

The key is your app's public write key from Fload. It can only report
installs for that one app, so it is safe to ship in the APK.

## What it does

- On the first launch with the SDK it makes a random install reference and
  reports the install once: package name, version name and code, Android
  version, device maker and model, locale, first-launch time, and what Play's
  Install Referrer API returns (the referrer string, the click and
  install-begin times, the install version and the Instant flag). Fload
  parses the referrer on the server.
- The referrer is read once and kept, because it does not change. If Play's
  service is unavailable, it asks again after 4, 8 and 16 s, then sends the
  install as `unavailable`. With no Play Store it sends `not_supported`.
- `setUserId` sends your user id with the install, or afterwards in a
  separate request. Each id is sent once.
- Everything waits in a small file in `noBackupFilesDir`, which is never
  backed up, until Fload accepts it. The file survives relaunches and network
  loss. Retries use exponential backoff with jitter (2 s doubling to 6 h),
  follow `Retry-After` on 429, and give up after 30 days. A retry repeats the
  same ids, so Fload counts the install once.
- It sends when configured, when the app comes to the foreground and when
  the network comes back, all on the SDK's own background thread.
- A key Fload does not know (HTTP 401) keeps the queue: the SDK logs
  "Fload: ingest key refused" once a launch, tries the key again every 24
  hours, and sends at once when the app is configured with a different key.
- A revoked key (HTTP 410) stops the SDK for good until the app ships a new key.

It adds the `INTERNET` and `ACCESS_NETWORK_STATE` permissions. It logs to
Logcat (tag `FloadAttribution`) at warning and above by default. It never
logs the key, the referrer or the user id.

## Notes

- **Existing users:** the first launch of an app version with the SDK counts
  as an install, for existing users too.
- **Reinstalls** and devices restored from a backup are new installs.
- Installs from outside Play (sideloads, other stores) have no referrer.

## Google Play Data safety

What this SDK collects, for your Data safety form. All of it is sent to
Fload, encrypted in transit, not shared with third parties and not used for
tracking or advertising. It is collected for analytics (install attribution).
Users can't opt out inside the SDK; your app decides whether to call
`configure`.

- **Device or other IDs:** a random install ID that the SDK creates. It is
  not the advertising ID or the Android ID, and it resets on reinstall.
- **User IDs:** your app's user ID, only when you call `setUserId`.
- **App info and performance, other (install attribution):** the Play
  Install Referrer (the campaign the install came from, and click and
  install times), app version, Android version, device model and locale.

## Testing against your own API

```kotlin
Fload.configure(this, "fload_pk_…", FloadOptions(endpoint = "http://10.0.2.2:3001", logLevel = FloadLogLevel.DEBUG))
```

A plain `http` endpoint also needs a network security config that allows
cleartext to that host.

Tests: `./gradlew :attribution:testDebugUnitTest` (plain JVM tests). The
end-to-end test runs only when `FLOAD_E2E_ENDPOINT`, which must be localhost,
and `FLOAD_E2E_KEY` are set.

## License

License: MIT. See [LICENSE](LICENSE).
