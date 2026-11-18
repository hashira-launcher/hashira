# hashira 柱

**English** · [Español](README.es.md) · [日本語](README.ja.md)

A minimalist Android launcher made for GrapheneOS. Kotlin and Jetpack Compose, no analytics, no Google services. Version 0.0.2.

## Features

- Home screen with a column of favorite apps, full A–Z list with search and frequent apps
- Icon packs (ADW, Nova and Apex `appfilter.xml`), hidden apps protected by fingerprint or PIN
- Private Space and work profile support
- Optional weather forecast (Open-Meteo), notification dots and double-tap to lock the screen
- Interface in English, Spanish and Japanese

Requires Android 13 or newer (minSdk 33).

## Requirements

- Linux x86_64 (the examples below are for Arch Linux)
- JDK 17 or newer
- Android SDK with platform 37 and build-tools 36.0.0
- git

## Set up (Arch Linux)

```sh
sudo pacman -S --needed jdk17-openjdk git unzip android-tools
```

If several JDKs are installed, pick one (17 or newer) with `archlinux-java status` and `sudo archlinux-java set <name>`.

Install the Android SDK command-line tools. Download the "Command line tools only" Linux zip from <https://developer.android.com/studio#command-tools>, then:

```sh
mkdir -p ~/Android/Sdk/cmdline-tools
unzip commandlinetools-linux-*_latest.zip -d ~/Android/Sdk/cmdline-tools
mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest

export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

Add the two `export` lines to your shell profile (`~/.bashrc` or `~/.zshrc`) to keep them. Missing SDK components are also downloaded by Gradle once the licenses are accepted.

## Build

```sh
git clone https://github.com/hashira-launcher/hashira.git
cd hashira
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Debug builds are noticeably slower than release builds, so use a release build for daily use.

## Release build

Release builds are signed with your own key. The key is never stored in the repository.

### 1. Create a keystore (once)

Keep it outside the repository and back it up. If you lose it, you cannot publish updates that install over the old version.

```sh
mkdir -p ~/.keystores
keytool -genkeypair -v -keystore ~/.keystores/hashira-release.jks \
  -alias hashira -keyalg RSA -keysize 4096 -validity 10000
```

### 2. Tell the build where it is

Option A, a `keystore.properties` file (it is git-ignored):

```sh
cp keystore.properties.example keystore.properties
chmod 600 keystore.properties
# edit it: absolute storeFile path, passwords, alias
```

With the default PKCS12 keystore, `keyPassword` must be the same as `storePassword`.

Option B, environment variables (they take priority over the file):

```sh
export HASHIRA_KEYSTORE_FILE=/home/you/.keystores/hashira-release.jks
export HASHIRA_KEYSTORE_PASSWORD=...
export HASHIRA_KEY_ALIAS=hashira
export HASHIRA_KEY_PASSWORD=...
```

### 3. Run the release script

```sh
./release.sh
```

It checks your setup, builds the signed APK, verifies the signature, writes a checksum and creates a local git tag. The version comes from `hashira.versionName` in `gradle.properties`.

```
dist/hashira-0.0.2.apk
dist/hashira-0.0.2.apk.sha256
```

Use `./release.sh --no-tag` to skip the git tag. Push the tag with `git push origin v0.0.2` and attach the two files to a GitHub release.

Without a signing configuration, `./gradlew assembleRelease` still works but produces an unsigned APK that Android will not install.

### Verify a downloaded release

```sh
sha256sum -c hashira-0.0.2.apk.sha256
apksigner verify --print-certs hashira-0.0.2.apk
```

Compare the certificate fingerprint with the one published by the maintainers.

## Install

```sh
adb install -r dist/hashira-0.0.2.apk
```

Or copy the APK to the phone and open it from a file manager. Then choose hashira as the home app: Settings → Apps → Default apps → Home app.

Updates must be signed with the same key as the installed version.

## Privacy and permissions

| Permission | Used for |
| --- | --- |
| `INTERNET` | Weather forecast from Open-Meteo, only if you enable it. You can revoke it in Android settings and the launcher keeps working. |
| `ACCESS_COARSE_LOCATION` | Approximate location for the weather, requested only if you choose "Phone location". |
| `ACCESS_HIDDEN_PROFILES` | Showing Private Space (Android 15+), only when hashira is the default launcher. |
| `USE_BIOMETRIC` | Fingerprint or PIN to open the hidden apps list. |

Two optional components need extra approval from you in Android settings: a device admin that can only lock the screen (double-tap to lock; Android blocks uninstalling while it is active) and notification access for notification dots (package names only, never content).

## Project layout

```
app/src/main/java/com/hashira/launcher/   Kotlin sources (ui, data, icons, weather)
app/src/main/res/                         resources and translations (en, es, ja)
gradle/libs.versions.toml                 dependency and plugin versions
gradle.properties                         app version (hashira.versionName / versionCode)
release.sh                                signed release build
```

## License

MIT, see [LICENSE](LICENSE). The launcher icon is the glyph 柱 from Noto Sans CJK JP Bold (SIL Open Font License 1.1), converted to a vector path.
