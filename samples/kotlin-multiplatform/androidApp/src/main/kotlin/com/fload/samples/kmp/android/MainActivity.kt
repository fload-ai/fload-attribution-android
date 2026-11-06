package com.fload.samples.kmp.android

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.fload.attribution.Fload

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = (application as SampleApplication).session
        // Test hook: adb shell am start ... --es fload_sample_user_id <id>
        intent?.getStringExtra("fload_sample_user_id")?.let(session::signedIn)
        setContentView(TextView(this).apply {
            setPadding(48, 96, 48, 48)
            text = "Fload KMP sample\nSDK ${Fload.SDK_VERSION}\nUser: ${session.userId ?: "(none)"}"
        })
    }
}
