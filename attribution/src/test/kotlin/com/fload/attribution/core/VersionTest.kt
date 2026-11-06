package com.fload.attribution.core

import com.fload.attribution.Fload
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The version every install reports is the release's: VERSION at the repository
 * root, which CI holds a tag to (.github/workflows/version.yml).
 */
class VersionTest {
    private fun versionFile(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "VERSION").isFile) dir = dir.parentFile
        return File(checkNotNull(dir) { "no VERSION above ${File("").absolutePath}" }, "VERSION").readText().trim()
    }

    @Test
    fun sdkVersionIsTheReleaseVersion() {
        assertEquals(versionFile(), SdkInfo.VERSION)
        assertEquals(versionFile(), Fload.SDK_VERSION)
    }

    @Test
    fun theUserAgentCarriesTheReleaseVersion() {
        assertEquals("fload-attribution-android/${versionFile()}", Wire.headers("fload_pk_test")["User-Agent"])
    }
}
