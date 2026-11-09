# Fload attribution in a Kotlin Multiplatform app

Kotlin Multiplatform (and Compose Multiplatform) apps use Fload's two native
SDKs directly, each from its own platform's code. There is no KMP library:

- **Android:** the Kotlin SDK, a normal Gradle dependency of `androidMain`
  (or of the Android app module).
- **iOS:** the Swift SDK, added to the iOS app target with Swift Package
  Manager. Kotlin/Native can only call Objective-C APIs, and the Swift SDK is
  Swift, so the iOS side brings it in.
- **Shared code** talks to both through a one-method interface, because the
  only call it needs is the user id link once it knows the user.

`Fload.configure` stays in each app's startup code: `Application.onCreate` on
Android, the `App` initializer or `AppDelegate` on iOS. That code is
platform code in every KMP project anyway.

## Steps

1. **Android.** In the module that holds your Android code (`androidMain`
   of the shared module, or the Android app):

   ```kotlin
   implementation("com.fload:attribution:1.1.0")
   ```

   from Maven Central. (Or JitPack:
   `com.github.fload-ai:fload-attribution-android:1.1.0`, with
   `maven("https://jitpack.io")` in your repositories.)

2. **iOS.** In Xcode, File > Add Package Dependencies,
   `https://github.com/fload-ai/fload-attribution-ios.git`, from `1.0.2`, and
   add `FloadAttribution` to the iOS app target.

3. **Shared code** (`commonMain`):

   ```kotlin
   interface InstallAttribution {
       fun setUserId(userId: String)
   }
   ```

   Hand it to whatever knows the user (your session, or the code around
   RevenueCat's `purchases-kmp`). Call `setUserId` with RevenueCat's app
   user id after RevenueCat is configured and after each login.

4. **Android implementation** (`androidMain`):

   ```kotlin
   class FloadInstallAttribution : InstallAttribution {
       override fun setUserId(userId: String) = Fload.setUserId(userId)
   }
   ```

   and in `Application.onCreate`: `Fload.configure(this, "fload_pk_…")`.

5. **iOS implementation** (Swift, in the iOS app). A Kotlin interface is an
   Objective-C protocol to Swift:

   ```swift
   import FloadAttribution
   import Shared

   final class SwiftInstallAttribution: InstallAttribution {
       func setUserId(userId: String) { Fload.setUserId(userId) }
   }
   ```

   and in the `App` initializer: `Fload.configure(key: "fload_pk_…")`, then
   pass `SwiftInstallAttribution()` to the shared code.

The SDKs' own READMEs cover the rest: what is sent, the App Store privacy
answers and the Play Data safety form.

## This sample

```
shared/      commonMain: InstallAttribution, AppSession; androidMain: FloadInstallAttribution
androidApp/  Application.onCreate configures the Kotlin SDK
iosApp/      SwiftUI app: configures the Swift SDK and implements InstallAttribution in Swift
```

It builds against this repository's SDK source (`includeBuild("../..")`),
and it sends to a local ingest stub, never to Fload: `http://10.0.2.2:3901`
from the Android emulator, `http://localhost:3901` from the iOS simulator.
A stub that checks every request against Fload's wire contract is in
[fload-attribution-dotnet](https://github.com/fload-ai/fload-attribution-dotnet/tree/main/tools/ingest-stub):
`python3 stub.py --port 3901 --key fload_pk_test_local`.

From the repository root:

```sh
# Android
./gradlew -p samples/kotlin-multiplatform :androidApp:assembleDebug
adb install -r samples/kotlin-multiplatform/androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n com.fload.samples.kmp/com.fload.samples.kmp.android.MainActivity --es fload_sample_user_id user_1

# iOS (the Xcode build runs the Gradle task that builds the Shared framework)
xcodebuild -project samples/kotlin-multiplatform/iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17' -derivedDataPath build/dd build
xcrun simctl install booted build/dd/Build/Products/Debug-iphonesimulator/iosApp.app
SIMCTL_CHILD_FLOAD_SAMPLE_USER_ID=user_1 xcrun simctl launch booted com.fload.samples.kmp
```

Each run sends one `installs` request and one `identify` request.
`iosApp.xcodeproj` is generated from `iosApp/project.yml` with
[XcodeGen](https://github.com/yonaskolb/XcodeGen). Regenerate it after
changing the spec.

## Why not a KMP library

A KMP library with `expect`/`actual` over both SDKs would need its own
Objective-C bridge to reach the Swift SDK from Kotlin/Native, its own
cinterop setup and a published Kotlin/Native artifact. iOS apps would still
have to link the Swift package, since a Kotlin framework can't carry a
Swift package for them, and CocoaPods trunk goes read-only on 2 December
2026. All of that wraps three calls, two of which (`configure` at launch)
belong in platform code already. A one-method interface in your own shared
code is less to install and less to go wrong.
