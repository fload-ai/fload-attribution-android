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

    @Test
    fun theConstructorsOf1_0StayForCallersBuiltAgainstThem() {
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val cls = FloadOptions::class.java
        cls.getConstructor()
        cls.getConstructor(String::class.java)
        cls.getConstructor(String::class.java, FloadLogLevel::class.java)
        // What Kotlin callers compiled against 1.0.x call for `FloadOptions(logLevel = …)`.
        cls.getConstructor(String::class.java, FloadLogLevel::class.java, Int::class.javaPrimitiveType, marker)
    }

    @Test
    fun aWrapperLongerThanTheContractAllowsIsLeftOut() {
        assertNull(FloadOptions(wrapper = FloadWrapper(name = "flutter", version = "1.0.0-" + "x".repeat(60))).wireWrapper())
        assertNull(FloadOptions(wrapper = FloadWrapper(name = "x".repeat(65), version = "1.0.0")).wireWrapper())
        assertEquals(WrapperInfo("flutter", "1".repeat(64)), FloadOptions(wrapper = FloadWrapper("flutter", "1".repeat(64))).wireWrapper())
    }
}
