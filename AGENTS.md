# QR File Share Developer Rules & Operations

This project is a localized high-performance file sharing utility constructed with modern Jetpack Compose for Android. 

## 1. Dynamic System Updates
- Target repository details are fetched securely from the GitHub API: `https://api.github.com/repos/{owner}/{repo}/releases/latest`.
- Standard version strings are compared.
- Supported Android installer requires `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`.
- Local installation initiates using `FileProvider` authorities: `${applicationId}.fileprovider`.
- Demoruns or sandbox updates are triggered using: `simulateNewVersionAvailable()`.

## 2. Real-Time Network Healing
- Monitors `ConnectivityManager` callbacks.
- Integrates a secondary automatic healing coroutine loop that periodically (every 5 seconds) verifies localized routing interfaces, re-detecting dynamic Wi-Fi or cellular IP addresses and instantly updating mDNS settings.

## 3. Production Exclusions
- Any developer notes, helper tools, or instruction files (specifically `/AGENTS.md` or `**/agents.md`) are explicitly excluded from production release builds via the Gradle packaging block:
  ```kotlin
  packaging {
    resources {
      excludes += "**/AGENTS.md"
      excludes += "**/agents.md"
    }
  }
  ```
