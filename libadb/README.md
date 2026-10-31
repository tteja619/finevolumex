# FineVolume vendored libadb module

This directory vendors the Android ADB transport module from:

- Upstream repository: https://github.com/MuntashirAkon/libadb-android
- Fix branch: https://github.com/SanniniciStyle/libadb-android/tree/fix/stream-close-eof
- Source commit: `f0cbeec53046a2d8f7c3762b4a52c5ef30ccc62c`
- Relevant upstream issue: https://github.com/MuntashirAkon/libadb-android/issues/34
- Relevant upstream fix: https://github.com/MuntashirAkon/libadb-android/pull/35

## Why it is vendored

The released `libadb-android:3.1.1` has a peer-close race in `AdbStream`: a normal ADB `CLSE` can surface as `IOException("Stream closed.")` after a short shell command has already completed, or leave a reader waiting after the peer has closed. The upstream pull request changes peer-close handling so queued data is delivered and subsequent reads return EOF, and adds regression tests.

The upstream PR is not yet merged or available as a published dependency. FineVolume therefore vendors the small `:libadb` module at that exact source commit so builds are reproducible and do not depend on an unpublished JitPack coordinate.

The `AdbStreamCloseTest` regression test from that fix is included in this module.

## Licensing

Upstream source file headers and the upstream `COPYING` and `LICENSES` files are retained in this directory. Individual source files may have their own notices; keep those headers intact when modifying this code.

## Local modifications

FineVolume keeps this vendored module close to upstream. The app-specific ADB identity storage, TLS pairing, Wireless Debugging discovery, and shell audio helper live in the FineVolume application module, not in this library.
