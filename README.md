# DogBoard

An open-source Android keyboard focused on the feel of typing: an accurate touch model,
context-aware autocorrect, next-word prediction, swipe typing, a rich clipboard and
on-device voice input.

DogBoard is a fork of [FlorisBoard](https://github.com/florisboard/florisboard)
by Patrick Goldinger and the FlorisBoard contributors, and like it is licensed under the
[Apache License 2.0](LICENSE). The upstream README is kept as
[README.florisboard.md](README.florisboard.md). Files changed from upstream are tracked in
this repository's git history.

## Install

Download `DogBoard-<version>.apk` from the [latest release](https://github.com/silly-puppyy/DogBoard/releases/latest)
(Android 10 or newer, ARM phones). DogBoard checks for updates itself (About › Check for updates now).

To get updates through an app store instead, use [Obtainium](https://obtainium.imranr.dev/): tap
**Add app**, paste `https://github.com/silly-puppyy/DogBoard`, and tap **Add**. Obtainium then installs
each new release from this page.

Every release is signed with the same key. Certificate SHA-256:
`ED:8D:A3:6A:7C:38:0D:C2:10:3F:E2:01:A3:99:1A:01:97:AC:7F:0D:49:E7:9C:FA:B5:D3:F9:74:BF:40:63:B1`

## Status

In use day to day. Typing engine (touch model, autocorrect, predictions), swipe typing, clipboard,
themes and downloadable languages are in place; more languages and swipe models are being added.

## Building

Requirements: JDK 17, Android SDK (compileSdk 37), NDK 29, CMake 4.1, and a Rust toolchain
installed via `rustup`.

```sh
./gradlew :app:assembleDebug
```

## Trademarks

"FlorisBoard" and its logo belong to their owners. DogBoard is an independent project and
is not affiliated with or endorsed by FlorisBoard.
