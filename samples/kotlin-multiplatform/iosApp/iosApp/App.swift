import FloadAttribution
import Shared
import SwiftUI

/// iOS: the shared module's `InstallAttribution`, implemented in Swift with the Swift SDK.
/// (Kotlin/Native calls Objective-C, not Swift, so the Swift side brings the SDK in.)
final class SwiftInstallAttribution: InstallAttribution {
    func setUserId(userId: String) {
        Fload.setUserId(userId)
    }
}

@main
struct SampleApp: App {
    private let session: AppSession

    init() {
        // Configure in the app, as for any iOS app. The sample sends to the local
        // ingest stub, never to Fload; drop the options in your app.
        Fload.configure(
            key: "fload_pk_test_local",
            options: FloadOptions(endpoint: URL(string: "http://localhost:3901")!, logLevel: .debug)
        )
        session = AppSession(attribution: SwiftInstallAttribution())
        // Test hook: simctl launch with SIMCTL_CHILD_FLOAD_SAMPLE_USER_ID=<id>.
        if let userId = ProcessInfo.processInfo.environment["FLOAD_SAMPLE_USER_ID"] {
            session.signedIn(appUserId: userId)
        }
    }

    var body: some Scene {
        WindowGroup {
            VStack(spacing: 12) {
                Text("Fload KMP sample").font(.title)
                Text("User: \(session.userId ?? "(none)")")
            }
        }
    }
}
