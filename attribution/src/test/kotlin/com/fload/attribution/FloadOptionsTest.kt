package com.fload.attribution

import com.fload.attribution.core.WrapperInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloadOptionsTest {
    @Test
    fun aWrapperNamesItselfThroughTheOptions() {
        val options = FloadOptions(wrapper = FloadWrapper(name = "capacitor", version = "1.1.0"))
        assertEquals(WrapperInfo("capacitor", "1.1.0"), options.wireWrapper())
        assertNull(FloadOptions().wireWrapper())
        assertNull(FloadOptions(wrapper = FloadWrapper(name = "", version = "1.1.0")).wireWrapper())
    }
}
