# NetTools for Android

A small native Android network toolkit. No VPS, account, API subscription, cloud database, analytics, or ad SDK is required.

## Install without Google Play

1. Open [Releases](https://github.com/benjaminbencsik/NetTools/releases).
2. Open the newest release and expand **Assets**.
3. Download **NetTools-preview.apk** for testing, or **NetTools.apk** from a signed production release. Do not download the source-code ZIP to install the app.
4. Open the APK on an Android phone running **Android 8.0 or newer**. If prompted, allow installation from the browser or file manager you used.

Public release APKs can be downloaded without a GitHub account. Actions artifacts are also available to signed-in repository readers as a backup download route.

## Tools

| Tool | Behavior |
| --- | --- |
| Ping | Four ICMP packets; shows round-trip time and loss. Uses Android's ping command, which some devices or networks restrict. |
| DNS lookup | A/AAAA address resolution through the device's configured resolver. No MX/TXT queries in this version. |
| TCP ports | Checks one hostname/IP, up to 128 ports, with bounded concurrency/timeouts. Reports open, refused, timeout or unreachable. |
| HTTP headers | HEAD request, status code, timing, headers and up to five redirects. Does not download the response body. |
| TLS certificate | Port 443 certificate, hostname/trust validation, protocol, cipher, issuer and expiry. |
| Subnet calculator | IPv4/CIDR network, masks, broadcast and usable range; supports /0, /31 and /32. Works offline. |
| Network details | Active transport, local IPs, DNS servers, routes and private DNS status where Android exposes it. |

Results can be copied or shared. The last 25 checks are saved privately on the phone and can be cleared. No background monitoring, root access, LAN-wide discovery, public-IP service, or automatic telemetry is included. Only check hosts you own or are authorized to test.

## Build on GitHub

Every push to `main` runs deterministic core tests, Android compilation, lint, signature verification and APK checksums. A successful build publishes a **preview release with a directly downloadable APK**. Pull requests build/test without publishing a release.

For a manual build: **Actions → Build Android APK → Run workflow → preview**. Download from Releases or from the completed run's `NetTools-APK` artifact.

The preview uses application ID `com.benbencsik.nettools.preview` and a debug signing certificate. The workflow caches that certificate, but caches are not permanent. If it is evicted, future preview updates may require uninstalling the old preview and lose local data. Previews are for testing. The production application has a separate ID and signing identity; do not sell or distribute a debug build as the production app.

## Permanent production signing

Before a public production launch, create a private Android signing keystore and keep a secure backup. **Never commit it or its passwords to this repository.** Use the same key for future releases so Android accepts updates and preserves data.

```bash
keytool -genkeypair -v -keystore nettools-release.jks -alias nettools \
  -keyalg RSA -keysize 3072 -validity 10000
```

In **Settings → Secrets and variables → Actions**, add these repository secrets:

| Secret | Value |
| --- | --- |
| `NETTOOLS_KEYSTORE_BASE64` | Base64-encoded contents of the private `.jks` file. On Linux/macOS: `base64 < nettools-release.jks` (remove line breaks if needed). |
| `NETTOOLS_STORE_PASSWORD` | Keystore password |
| `NETTOOLS_KEY_ALIAS` | `nettools`, if using the command above |
| `NETTOOLS_KEY_PASSWORD` | Private-key password; normally the same as the keystore password for the default PKCS12 format |

Then run **Actions → Build Android APK → Run workflow**, select `release`, and enter a new version such as `0.1.0`. The workflow publishes a signed `NetTools.apk` and `SHA256SUMS.txt` in Releases. Production signing intentionally fails if any secret is missing. Each workflow run supplies an increasing Android version code.

## Local development

Open the repository in Android Studio, install SDK platform/build tools 35, and use JDK 17. This project uses Android Gradle Plugin 8.9.2 and Gradle 8.11.1.

```bash
bash scripts/test-core.sh
./gradlew :app:assembleDebug :app:lintDebug
```

The Gradle wrapper is included. No third-party Android runtime dependencies are needed. Core tests cover subnet edge cases, input validation, DNS on loopback, and actual open/refused TCP sockets. Device testing is still needed for manufacturer-specific ICMP behavior, network routing and screen layouts.

## Privacy

The app requests internet and network-state permissions. It connects directly to user-entered diagnostic targets, and DNS lookups go to the device's configured resolver. It does not request location, contacts, storage, camera, microphone, or Wi-Fi scanning permissions. HTTP is allowed so a user can diagnose an HTTP-only endpoint; HTTPS/TLS trust and hostname validation are retained. Sharing a result sends the selected text to the destination the user chooses in Android's share sheet.

There are no ads, payments, premium entitlements, or monetization features in this first version. A future paid edition can add local workflows and exports without requiring a VPS; billing/distribution choices should be decided separately before launch.
