# Noise

A single-file noise generator (`noise.html`) wrapped as an Android app with
[Capacitor](https://capacitorjs.com/). `noise.html` is the source of truth for the UI;
the build copies it to `www/index.html` and packages it in a native WebView project.

## Layout

| Path | Purpose |
| --- | --- |
| `noise.html` | The app |
| `capacitor.config.json` | App id/name. **Change `appId` before publishing.** |
| `package.json` | Capacitor deps and build scripts |
| `android/` | Generated native project (created by `npm run add:android`, then committed) |
| `.github/workflows/android.yml` | CI: builds a debug APK on every push/PR |
| `.vscode/` | Editor settings, tasks, recommended extensions |
| `assets/icon-foreground.svg`, `scripts/make-icons.sh` | App icon source and the script that regenerates the launcher icons and splash screen (needs inkscape + imagemagick) |
| `scripts/setup-android-sdk-linux.sh` | Installs the Android SDK command-line tools on Linux |

## Build environment

No Android Studio required. Pick your OS; the build steps below are identical afterwards.

### Linux (Debian/Ubuntu shown; adapt package names for other distros)

```bash
# Node 20+ (via nvm, NodeSource, or your distro if recent enough), JDK 21, tools
sudo apt install openjdk-21-jdk curl unzip

# Android SDK command-line tools + platform/build-tools (installs to ~/Android/Sdk)
./scripts/setup-android-sdk-linux.sh
```

Add the `export` lines the script prints to `~/.bashrc` (or `~/.zshrc`). VS Code's
integrated terminal already sets these for `~/Android/Sdk`.

To install on a physical phone you also need USB access to the device:

```bash
sudo apt install android-sdk-platform-tools-common   # udev rules; replug the phone afterwards
```

### macOS (Homebrew)

```bash
# Node 20+ and JDK 21 (Capacitor 7). Use JDK 17 if you pin Capacitor 6.
brew install node
brew install --cask temurin@21

# Android SDK command-line tools
brew install --cask android-commandlinetools
```

Add to `~/.zshrc`:

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"
```

Install the SDK pieces and accept licenses:

```bash
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Gradle is not installed separately; the Android project ships a wrapper (`android/gradlew`).
Check the JDK and SDK versions against the Capacitor version in `package.json`, as they
change between major releases.

## Build and run

```bash
npm install
npm run add:android      # first time only; creates android/
npm run build:debug      # copies noise.html to www/, syncs, runs gradle assembleDebug
```

The APK is at `android/app/build/outputs/apk/debug/app-debug.apk`.

Install on a phone (enable Developer options and USB debugging first):

```bash
adb devices
npm run install:debug
```

In VS Code, the same steps are available as tasks (Terminal → Run Task, or
`Cmd+Shift+B` for the debug build).

## CI

`.github/workflows/android.yml` builds a debug APK on pushes to `main`, pull requests, and
manual runs. Download it from the run's **Artifacts** section. If `android/` has not been
committed yet, CI generates it.

### Troubleshooting: HTTP 429 from repo.maven.apache.org

Some networks get rate-limited by Maven Central. Google hosts a mirror; point Gradle at it with
an init script (e.g. `~/.gradle/init.d/mirror.gradle`) that rewrites any repository URL containing
`repo.maven.apache.org` to `https://maven-central.storage-download.googleapis.com/maven2/`.

### Web version (GitHub Pages)

`.github/workflows/pages.yml` publishes `noise.html` as the site's `index.html` on pushes to
`main`. One-time setup: repository **Settings → Pages → Source: GitHub Actions**. The site is
then at `https://<user>.github.io/<repo>/`. (On a normal browser the page uses the Web Audio
engine; the native looping player is only used inside the Android app.)

### Signed release builds (not set up yet)

Play Store uploads need a signed `.aab` (`npm run build:release`). To add this: create a
keystore, add a signing config to `android/app/build.gradle`, and store the
keystore (base64) and passwords as GitHub Actions secrets. Never commit `*.jks` or
`*.keystore` files (they are git-ignored).

## Known limitation

Web Audio inside a WebView is generally suspended when the screen locks. Keeping noise
playing with the screen off requires a native foreground service that loops a
pre-rendered buffer; that is not implemented yet.
