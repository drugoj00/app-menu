[English](README.md) · [Русский](README.ru.md) · [Українська](README.uk.md)

🌐 All our programs: https://drugoj00.github.io/

<img src="https://drugoj00.github.io/phone-in-window-with-sound/app_menu_icon.png" alt="" width="96" align="right">

# App Menu

A full-screen app menu for Android TV boxes, car head units and phones: remote, touch, mouse and air mouse. Long press an icon for stop, cache, data, permissions, folders and any app version. No ads.

App Menu replaces the stock app list with a big grid made for a remote and for touch. It has what stock launchers lack: a One UI style action card on every icon, folders, installing and removing apps in batches, backups before deleting and installing any version, including an older one.

⭐ If the app is useful, please star this repository: it costs nothing and helps others find it.

## Download

**Version 7.0.** [AppMenu-7.0.apk](https://github.com/drugoj00/app-menu/releases/latest) (Assets) · [Releases](https://github.com/drugoj00/app-menu/releases)

SHA-256: `7d5abb431b2c37b118c71e7a59121758bf723a56417661bf43f976f8ac2401cc`

## What's new in 7.0

- **Home:** top bar with crisp colors: buttons and the search field are opaque, the field border uses the accent color, a bright accent over a picture background; the USB and Settings icons with display turned on are visible on boxes that do not report themselves as TV when the top bar is forced on
- **Appearance:** top bar settings of Home (the look is set by the settings; by default the buttons have an opaque background and the search field has an accent-colored border): status icon outline (Auto by background brightness, Dark, White, None), button background (opaque, translucent, none), button corners (normal, round, small), program name next to the menu button, selection ring glow (none, weak, strong); the items are also in the app Settings with a collapsed explanation
- **Android widgets:** on Android 12 and newer the binding is allowed in several ways through ADB or root; when a widget in a Home cell is replaced, the new look and its settings show at once; “Widget settings” no longer replaces or deletes the widget
- **Batch install:** when refused for a version lower than the installed one or a different signature, a “Fix” button with confirmation: through ADB or root the lower version is installed over the top, otherwise keeping the data, with a different signature the old app is removed with its data and the previous one is put back if the install fails; without ADB and root a “Remove the installed version and install this one?” dialog and a replacement through the system removal and install windows; instead of “file is damaged” any parse refusal shows a neutral message with the system code; a refused downgrade is no longer shown as success
- **Passwords:** an “eye” icon (show or hide) in the password field

**Fixed:**

- The USB and Settings icons are no longer missing on boxes that do not report themselves as TV (top bar forced on)
- A replaced Android widget in a Home cell now shows its new look at once; “Widget settings” no longer replaces or deletes the widget
- A refused downgrade is no longer shown as a successful install; any parse refusal shows a neutral message with the system code instead of “file is damaged”

**Requirements and limits:**

- “Fix” through ADB or root needs no system windows; without them the replacement goes through the system removal and install windows, the app data is lost, and each window must be confirmed (if removal is declined, nothing is installed)
- Android allows a downgrade over the top only on some builds (usually for debuggable apps only); then the data is kept by removing the app with the “keep data” flag

## Features

- **Home screen.** A separate screen of rows: widgets (weather, clock, date, memory, Android widgets), app banners and icons, cards from Android TV channels and from the catalogue of any app (SmartTube, MEGOGO, HD VideoBox, Seena), favourites and history. Banners are made from the icon when an app has none. Optional: turn it on in Settings. Iron mode switches off the stock home screen (ADB or root), one button brings it back.
- **Menu for remote, touch, mouse and air mouse.** A full-screen grid: OK opens, holding OK or the Menu key shows actions, touch and mouse work as usual (left button opens, right button shows actions, wheel scrolls). Columns, icon size down to tiny (5 columns on a phone), labels, sorting (name, new, old, most used, your own), search, hiding, list mode.
- **Several desks.** Optional: pages without scrolling, up to 9, switching in a loop. The desk menu opens on an empty spot and on the header.
- **Action card on every icon.** Like in One UI: stop, clear cache, clear data, permissions, special access (with automatic restore after a reboot), battery, hide, uninstall, icon, folder, versions. Needs ADB of the same device (see below).
- **Memory clean.** A button in the menu and on Home, optionally on the Home button.
- **Icon plates.** The plate under an icon can be turned on or off.
- **App shortcuts.** In a separate panel next to the action card.
- **Folders.** Drag one icon onto another to make a folder, drag icons into it or add them from inside; rename and delete. The folder window is a 5×5 grid.
- **Install from anywhere.** APK, split packages (APKS, APKM, XAPK, ZIP with APK and OBB: the parts that fit the device are chosen automatically), from a folder or an archive (ZIP, 7z, RAR, TAR), from the built-in file manager, from USB drives (through ADB, when the system hides them), from network folders (SMB, with search in the local network), from a phone or a computer over the local Wi‑Fi network (a page in the browser) or directly over Wi‑Fi Direct without a router (the device is visible on the network by name) and from the internet (F-Droid, GitHub, a direct link). Batch install and batch delete.
- **Backups and any version.** Before an app is deleted its APK is saved. Install any saved version, a version from the F-Droid catalogue or your own file, an older one too (the current version is saved first).
- **Launcher mode.** Home shows this menu, a second press of Home opens the native home screen, Back returns from an app to the menu. Turn it on and off in Settings.
- **App updates.** An “Updates” button on an icon card and in the header: versions by source (F-Droid, IzzyOnDroid, Aptoide, GitHub, Codeberg; AppGallery and RuStore optional), “Update” and “Update all”. Before installing, the package, CPU, version and signature are checked, and the previous version is saved as a copy. The scheduled check is off until you turn it on.
- **Look and language.** Dark, black (OLED), light or like the system theme, six accent colours, Russian, Ukrainian and English, own icons and icon packs, backup and restore of settings.
- **Simple ADB connection.** On Android 9 TV boxes with network ADB it connects by itself. On Android 11+ press “Connect automatically”: the app turns on wireless debugging, reads the pairing code and pairs on its own (needs its accessibility service, which acts only after you press the button). Android 10 and older: one command from a computer, then it connects by itself.
- **Free.** No ads, open source.

## Screenshots

![Home screen with a highlighted banner.](screenshots/en/01_dom.png)

![App menu with a highlighted icon.](screenshots/en/02_menu.png)

![Settings: accent color and selection frame color.](screenshots/en/03_settings.png)

![Home screen dialog with “Program settings”.](screenshots/en/04_dialog.png)

## Quick start

1. Download AppMenu-7.0.apk and install it (allow installing from this source if Android asks). The icon “App Menu” appears in the list of apps (on a TV box in the Leanback launcher).
2. Open it: apps are launched with OK or a tap, a long press (or holding OK, or the Menu key) opens the action card. To use it instead of the stock menu turn on “Launcher mode” in Settings.
3. To act on other apps connect ADB: Settings → “Connect ADB”. Android 9 and older TV boxes with network ADB connect by themselves. Android 11+: turn on the “App Menu” service in Accessibility once, press “Connect automatically”. Android 10 and older: run adb tcpip 5555 once from a computer (repeat after a reboot). With root none of this is needed: allow root for the app once and it connects by itself.

## Requirements

Android 6.0 or newer (ARMv7, ARM64, x86). ADB (or root) of the same device is needed only for actions on other apps (stop, cache, data, permissions, special access, silent install and uninstall, OBB on Android 11+, downgrade); the menu, folders, sorting and launching work without it. Tested on a TV box with Android 9, 11 and 12, Samsung S9 (Android 10) , A14 (Android 15) and an Android 7.1.1 emulator.

## Limitations and warnings

- Actions on other apps need ADB debugging of the same device or root. App Menu works locally and sends nothing anywhere; the only network requests are the ones you start yourself (F-Droid and GitHub search, a link you type, an update check); the scheduled check stays off until you turn it on.
- The automatic ADB connection on Android 11+ uses an accessibility service. It reads only the system settings windows and acts only for three minutes after you press “Connect automatically”. It is tested on Samsung One UI; other firmwares may word the settings differently, then use the manual way.
- Receiving files: over a page in a browser in the same network or directly (Wi‑Fi Direct, no router; the device itself must support Wi‑Fi Direct, and on devices with a single Wi‑Fi radio the connection to the router may drop during the transfer). OBB files on Android 11 and newer can only be copied through ADB.
- System apps cannot be uninstalled. Not on Google Play: download the APK here; Android may warn about an unknown source.
- Tested on Android 9, 10, 11, 12 and 15. Android 13 and 14 and other makers’ firmwares are not tested yet; reports are welcome. Known: Samsung Dual Messenger copies show up as separate icons.

## Built with

Kotlin and Jetpack Compose; ADB client libadb-android (MuntashirAkon, GPL-3.0 or Apache-2.0), Conscrypt, Apache Commons Compress, XZ for Java, junrar, jcifs-ng (LGPL-2.1). The app is independent and not related to Google. Licensed under GPL-3.0.

## News and help

- Telegram: [t.me/PhoneInWindowSound](https://t.me/PhoneInWindowSound)

## Support the project

The app is free. If it is useful you can support its development by card, Google Pay or USDT. For USDT use only the TRON network (TRC20): coins sent over another network are lost.

- By card or Google Pay (from 50 UAH): https://secure.wayforpay.com/button/bba92579a4647
- USDT (TRON TRC20): `TXk32CLkwJryTqhmkw6urcM5CacJgPsSpJ`
