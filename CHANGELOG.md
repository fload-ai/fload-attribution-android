# Changelog

## 1.1.0

- `FloadOptions(wrapper = FloadWrapper(name, version))`: a cross-platform
  wrapper (Flutter, React Native, Capacitor, Cordova, Unity, .NET, KMP)
  names itself, and every request carries it as `sdk.wrapper`. Apps calling
  the SDK directly change nothing (FLO-3957). `FloadWrapper` is kept under
  its own name for JNI callers. 1.0's constructors are unchanged, so a
  library compiled against 1.0 keeps linking. A name or version over 64
  characters is left out rather than risk the request.
- Identify requests also carry `sdk`, so an install Fload first hears of
  through an identify still knows its SDK. Older Fload servers ignore it.

## 1.0.2

- Installs report `sdk.version` `1.0.2`. 1.0.0 and 1.0.1 reported `0.1.0`.
  The version now comes from `VERSION`: a test holds `SdkInfo.VERSION` to
  it, and CI holds a tag to it.
- Consumer ProGuard/R8 rules keep `Fload`, `FloadOptions` and `FloadLogLevel`
  under their own names, for callers that reach them by name over JNI (.NET
  bindings, Unity's `AndroidJavaClass`). No API change.

## 1.0.1

- Same code as 1.0.0, tagged again for JitPack.

## 1.0.0

- First release.
