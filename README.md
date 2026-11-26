# Olipingo app

The Android app around **Olipingo**, a personal walking, rehab and meal coach that lives as a
claude.ai artifact (Olivia the olive, dietitian; Pingo the penguin, sports coach). The artifact is the
brain; this app shows it full screen, keeps the claude.ai sign-in, opens the camera for meal photos,
opens other sites (Foulée, WhatsApp) in their own app, and updates itself.

This repo is public on purpose: it holds only the app code and its releases, so the installed app can
find updates without a password. No personal data lives here; the artifact and its data stay private
on claude.ai, and the project notes stay in the private Olipingo repo.

## Install and update

- First install: open the latest release (Releases), download the `.apk`, allow the installation.
  Android may ask once to allow your browser to install apps, and Play Protect may ask to scan an app
  from an unknown developer: choose to scan or install anyway, once.
- Updates: the app checks this repo's latest release at start (and when you come back after 30 min)
  and offers to install it. The first time, Android asks to allow Olipingo to install updates.

## Signing: why the phone does not complain at each update

- Every release is a **release build signed with one private key**, from the very first version. The
  public debug key is never used: Play Protect flags debug-signed apps (lesson from Echo-app 0.4).
- The key is not in this repo. The workflow reads it from the repo secrets `OLIPINGO_KEYSTORE_B64` (the
  PKCS12 file in base64) and `OLIPINGO_KEYSTORE_PASSWORD`, alias `olipingo`, and refuses to build
  without them. Keep a backup of the `.p12`: without it, a new version can only be installed after
  uninstalling the app (and signing in again).
- Before installing an update, the app checks the downloaded APK: same package, higher version code,
  and the **same signing certificate** as the installed app. Anything else is refused.
- The update is opened in **Android's own installer screen** (one tap to confirm), as in Echo-app.
  0.1.0 and 0.1.1 installed updates themselves through `PackageInstaller` without asking: Play Protect
  watches for exactly that in apps that download and install APKs, and flagged Olipingo. Do not go back
  to silent installs.
- Permissions are kept to two: network, and installing its own updates. Photos go through the phone's
  camera app, so the app never asks for the camera. No backup of app data (`allowBackup=false`), no
  clear-text traffic.

## How it is built

`.github/workflows/release.yml` builds and checks the signed APK on every push. On `main`, when
`versionName` in `app/build.gradle` has no release yet, it publishes `v<versionName>` with the APK and
the matching section of `CHANGELOG.md`. To release: bump `versionCode` and `versionName`, add a
`CHANGELOG.md` section, push to `main`.

Launcher icon: `node tools/icons/render.js` renders the PNG layers from `tools/icons/icon.html`
(generated from the mascot sheet in the private Olipingo repo).

## Bridge

Only pages served as claude.ai artifacts (`https://*.frame.claudeusercontent.com`) see
`window.OlipingoApp`. The page posts JSON `{id, cmd, ...}`; the app answers `{id, ok, ...}`.
Commands: `hello` (app version), `copy` (`text` to the clipboard), `share` (`text` through the
Android share sheet), `openUrl` (`https` only, opened outside the app).
