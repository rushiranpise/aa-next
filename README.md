<p align="center">
  <img src=".github/icon.png" width="112" alt="AA Next">
</p>

<h1 align="center">AA Next</h1>

<p align="center">
  Tweaks for Android Auto.
</p>

<p align="center">
  <a href="https://github.com/rushiranpise/aa-next/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/rushiranpise/aa-next?label=release&color=0A84FF"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <a href="LICENSE"><img alt="AGPL-3.0" src="https://img.shields.io/badge/license-AGPL--3.0-lightgrey"></a>
</p>

> **AA Next is a fork of [AA Mods](https://github.com/ImNoammm/aa-mods) by ImNoammm.** The hooks, the phone
> screen and most of this app are their work. Keep the AA Mods credit if you reuse this code.

AA Next is an Xposed module — it runs on LSPosed and on its fork Vector — that changes how Android Auto
behaves on your phone and on the car screen. Everything is a switch in its settings app.

AA Mods was built so Android Auto does what you tell it: open on the launcher instead of starting
navigation and music by itself, let you close apps, run on microG and stop sending Google its telemetry.
AA Next keeps that work and continues it.

## Features

<table>
  <tr><td><img src=".github/icons/apps.svg" width="32" alt=""></td><td>Open any app on your phone inside Android Auto.</td></tr>
  <tr><td><img src=".github/icons/local_parking.svg" width="32" alt=""></td><td>Always parked, so typing and full lists stay unlocked while the car moves.</td></tr>
  <tr><td><img src=".github/icons/home.svg" width="32" alt=""></td><td>Start on the app launcher. No navigation, music or autoplay until you open them yourself.</td></tr>
  <tr><td><img src=".github/icons/overview_key.svg" width="32" alt=""></td><td>A recents button to switch between open apps or close them. Closing an app really stops it, music apps included.</td></tr>
  <tr><td><img src=".github/icons/smartphone.svg" width="32" alt=""></td><td>The car screen on the phone itself, for cars without a screen.</td></tr>
  <tr><td><img src=".github/icons/install_mobile.svg" width="32" alt=""></td><td>Sideloaded car apps, ones with Android Auto support that aren't from the Play Store.</td></tr>
  <tr><td><img src=".github/icons/speed.svg" width="32" alt=""></td><td>Lighter on older phones, with a lower frame rate and a cap on the car screen resolution.</td></tr>
  <tr><td><img src=".github/icons/block.svg" width="32" alt=""></td><td>No required Google apps: it runs without Google Maps, the Google app or Speech Services.</td></tr>
  <tr><td><img src=".github/icons/shield.svg" width="32" alt=""></td><td>Android Auto's telemetry switched off.</td></tr>
  <tr><td><img src=".github/icons/admin_panel_settings.svg" width="32" alt=""></td><td>microG instead of Play Services, so Android Auto never talks to Play Services.</td></tr>
  <tr><td><img src=".github/icons/notifications_off.svg" width="32" alt=""></td><td>Muted notifications (calls, media and navigation still show) and no News or Weather tiles.</td></tr>
  <tr><td><img src=".github/icons/flag.svg" width="32" alt=""></td><td>Android Auto's hidden UX Prototype web app, plus a searchable feature-flag browser for everything else.</td></tr>
</table>

## Getting it

You'll need a rooted phone with LSPosed or Vector, and Android Auto 17.6. AA Next hooks Android Auto's
internal code, which changes with every version.

AA Next has its own package name, so Android treats it as a separate app. Disable the old AA Mods module in
your Xposed manager before enabling AA Next; settings don't carry over between the two.

1. Grab the APK from [Releases](https://github.com/rushiranpise/aa-next/releases) and install it.
2. In your Xposed manager (LSPosed or Vector), turn on AA Next and tick Android Auto in its scope. For the
   microG switch on a phone where Android Auto didn't come preinstalled, tick System Framework too and reboot.
3. Open AA Next. The first screen checks root, that AA Next is enabled in your Xposed manager and that it's
   running inside Android Auto, and can restart Android Auto for you.

After that it checks GitHub for a new version every time you open it, and updates from inside the app.

A note on the microG switch: its system-access part runs inside `system_server`, which reads the module's own
settings file. That file is only shared with the module's own app on Vector, so with LSPosed the switch works
for a preinstalled Android Auto and for microG itself, but the extra system access for a non-preinstalled one
can't be turned on from here.

## Building it

You'll need JDK 17 and the Android SDK.

```sh
git clone https://github.com/rushiranpise/aa-next.git
cd aa-next
./gradlew assembleRelease
```

The APK ends up in `app/build/outputs/apk/release/`.

## Credits

- AA Next is based on [AA Mods](https://github.com/ImNoammm/aa-mods) by ImNoammm (AGPL-3.0). The hooks, the
  phone screen and most of the settings app come from that project. Keep that credit if you reuse this code or
  name in another project.
- The car screen on the phone is ported from [Headunit Revived](https://github.com/andreknieriem/open-headunit),
  which builds on Michael Reid's headunit (AGPL-3.0).
- Icons are Google's [Material Symbols](https://github.com/google/material-design-icons) (Apache License 2.0).
- The AA Next app icon was provided by [rushiranpise](https://github.com/rushiranpise).

## License

AGPL-3.0, because the car screen on the phone is built on Headunit Revived. See [LICENSE](LICENSE).
