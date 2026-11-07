# TS3X Client

An Android voice client for **TeamSpeak 3 and TeamSpeak 6** servers, built with Jetpack Compose.

**Release: 0.10.2 (542)** · **Android 8.0+ (API 26)** · `com.morsen.ts3xclient`

TS3X Client is an independent project and is not an official TeamSpeak product. TeamSpeak names and trademarks belong to their respective owners.

## Features

- Connect to TS3 and TS6 servers through a single interface, with saved server profiles, passwords and identities.
- Enter `host:port` directly in the Host field. Supports DNS, SRV and TSDNS resolution; a port included in the address takes precedence over the Port field.
- Browse channels and participants with avatars, country flags, and server and channel group icons. Participants are also displayed in joinable spacer channels.
- Voice communication, microphone mute, output mute, Push-to-Talk, a voice transmission indicator and individual participant volume controls.
- Background voice sessions with a persistent notification and audio controls.
- Server monitoring, background checks and notifications. When monitoring is enabled, the app checks background restrictions and offers the relevant battery settings.
- Channel chat and private messaging within the connected server, image previews and attachment information.
- Channel descriptions and banners: tap once to view details, or twice to join the channel.
- Upload, crop and remove your avatar; view server avatars and available myTeamSpeak account avatars of other participants.
- AFK status with a custom message, connection information, poke, group management, kick and ban actions, subject to server permissions.
- Support for `ts3x://server/add?...` invitations and supported `tmspk.gg` links.
- Image and metadata caching, diagnostic data export and crash reports.
- English by default, with manual language selection in Settings.

## Getting Started

1. Install a signed APK from a trusted source, or build the app yourself.
2. On first launch, grant microphone and notification permissions to enable voice communication and background notifications.
3. Add a server using its hostname or `host:port`, your nickname and a password if required.
4. Connect and select a channel. Double-tap a channel to join it.
5. For background voice sessions and monitoring, allow unrestricted background operation in your device's battery settings. Setting names vary by device.

## Compatibility and Limitations

User, avatar and group management depend on server permissions. TS6 support covers connections to voice servers and compatible client commands; the app does not implement the full official client ecosystem.

myTeamSpeak sign-in, global chats, communities and downloading cloud attachments using only a `file_id` are not implemented. Attachment information may be displayed without a download option. Account avatars use available URLs from server metadata and do not require account sign-in.

Server and external image availability depend on routing, DNS, network restrictions and server configuration. Some connection statistics may be unavailable if the server or transport does not provide them.

## Building

Requirements: **JDK 17**, Android SDK **API 35**, Android SDK Build Tools **34.0.0**, and access to the dependency repositories. The project includes **Gradle Wrapper 8.9**. Android Gradle Plugin and Kotlin versions are pinned in `build.gradle.kts`.

Open the project root in Android Studio, configure the Android SDK and run Gradle Sync. The local SDK path is stored in `local.properties`, which is excluded from the repository and source archives. For command-line builds, you can set `ANDROID_HOME` instead.

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :protocol:test :ts3j:test :app:lintDebug
.\gradlew.bat :app:assembleRelease
```

Linux / macOS:

```bash
./gradlew :app:assembleDebug
./gradlew :protocol:test :ts3j:test :app:lintDebug
./gradlew :app:assembleRelease
```

The debug APK is generated in `app/build/outputs/apk/debug/`. The release APK is generated in `app/build/outputs/apk/release/` and remains **unsigned** unless you configure signing. For distribution, use **Build → Generate Signed App Bundle / APK** in Android Studio with your own signing key. Keep the key for future updates; do not commit keys or passwords to Git.

## Testing

Unit tests are located in `protocol/src/test` and `ts3j/src/test`. Tests that require a live server are skipped by default. Local network tests use loopback and do not contact public servers.

For additional integration testing, use `tools/run_protocol_integration.py` with a separately installed official TS3 or TS6 server. Read the license of the corresponding server before running it. Use `--help` to view the available options.

The GitHub Actions workflow runs unit tests, Android lint and an unsigned release APK build on pushes to `main` or `dev` and on pull requests. Publishing GitHub Releases and signing APKs are separate tasks performed by the project owner.

## Project Structure

| Directory | Purpose |
| --- | --- |
| `app/` | Android application, Compose UI, audio, services and settings |
| `protocol/` | Shared protocol logic, address resolution, files, avatars and tests |
| `ts3j/` | Bundled TeamSpeak transport with local fixes |
| `tools/` | Local integration testing tool |
| `.github/workflows/` | GitHub Actions checks and builds |

## Diagnostics and Data

Server profiles, identities and the selected avatar are stored on the device. Voice communication uses the microphone, and an active voice session runs through a foreground service. Android backup is disabled in the application manifest.

Review logs and reports before sharing them: server addresses, usernames, messages and other information may be private. An invitation containing a password includes connection credentials; Base64 encoding is not encryption. Share such links only with the intended participants.

## License

The original TS3X Client code is licensed under the **Apache License 2.0**. See [LICENSE](LICENSE) for the full terms and [NOTICE](NOTICE) for attribution and third-party notices.

The license permits use, modification and distribution under its terms, including preservation of applicable notices and identification of changes. The software is provided without warranties. The full LICENSE text governs its use.

The bundled [ts3j](https://github.com/Manevolent/ts3j) module is based on revision `db57d60c989e399626aa16d921390f5033e6cdeb`, includes modifications for TS3X Client, and retains its upstream license in `ts3j/LICENSE`. Provenance and local changes are documented in `ts3j/UPSTREAM.properties`. Third-party licenses and notices remain applicable and are not replaced by the application's license. Dependencies obtained through Gradle remain subject to their respective owners' license terms.

## Contributing

When reporting a bug, include the application and Android versions, the server version, steps to reproduce the issue, and a log with private information removed. Prepare changes in separate branches and submit them through pull requests. Application versions are changed explicitly in `app/build.gradle.kts`; releases are tagged after merging into `main`.
