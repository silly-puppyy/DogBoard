# DogBoard

An open-source Android keyboard focused on the feel of typing: an accurate touch model,
context-aware autocorrect, next-word prediction, swipe typing, a rich clipboard and
on-device voice input.

DogBoard is a fork of [FlorisBoard](https://github.com/florisboard/florisboard)
by Patrick Goldinger and the FlorisBoard contributors, and like it is licensed under the
[Apache License 2.0](LICENSE). The upstream README is kept as
[README.florisboard.md](README.florisboard.md). Files changed from upstream are tracked in
this repository's git history.

## Status

Early development. The first goals are the typing engine (touch model, language model,
autocorrect and case handling), followed by swipe typing, predictions, clipboard images
and voice input.

## Building

Requirements: JDK 17, Android SDK (compileSdk 37), NDK 29, CMake 4.1, and a Rust toolchain
installed via `rustup`.

```sh
./gradlew :app:assembleDebug
```

## Trademarks

"FlorisBoard" and its logo belong to their owners. DogBoard is an independent project and
is not affiliated with or endorsed by FlorisBoard.
