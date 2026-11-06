package com.fload.samples.kmp

import com.fload.attribution.Fload

/** Android: the Kotlin SDK, straight from androidMain. */
class FloadInstallAttribution : InstallAttribution {
    override fun setUserId(userId: String) = Fload.setUserId(userId)
}
