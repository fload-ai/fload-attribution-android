# Changelog

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
