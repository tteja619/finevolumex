<p align="center">
  <img src="docs/logo.png" alt="FineVolume Logo" width="180"/>
</p>

<h1 align="center">FineVolume</h1>

<p align="center">
  <strong>One UI-Inspired Volume Panel with Fine Control and Self-Hosted Wireless ADB</strong>
</p>

---

## Overview

FineVolume provides a custom volume panel and fine-step media adjustment. Its optional high-resolution player-control engine is now self-hosted: FineVolume contains its own ADB client, creates and stores its own ADB identity, pairs directly with Android Wireless Debugging, and starts its own shell-UID audio helper.

**Shizuku is not used. Root is not required.** The first pairing is approved by the user in Android Developer Options. FineVolume does not enable Wireless Debugging or grant itself shell privileges.

When the Wireless ADB helper is unavailable, FineVolume automatically falls back to Android's public `AudioManager` media-volume control. That native fallback works without pairing, Shizuku, or root, but is limited to the device's native shared media-volume steps.

The expanded volume panel is an app-owned, Samsung One UI-inspired overlay. It does not replace Samsung SystemUI.

## Main features

### Fine controls
- 120-step UI adjustment, adjustable resolution, configurable increment and press-and-hold speed.
- Plus/minus controls and numeric entry in settings.
- Separate stream and per-app gain settings while the Wireless ADB helper is connected.
- Playback-session monitoring inside the helper, with gain reapplication when the player creates or resumes sessions.
- When the helper is unavailable, shared native media volume continues to work through AudioManager.

### One UI-inspired volume panel
- Expanded stream and active-app controls.
- Names and percentages are separated from the slider tracks for readability.
- Mute controls, system streams and Live Caption integration.
- Custom accessibility overlay with hardware-key interception.

### Haptics and interruptions
- Uses Android view-level haptic feedback instead of sending raw vibrator pulses.
- App haptics default to off.
- Volume keys are passed back to Android during calls and recognized system-alert playback.
- Fine-volume cached positions are invalidated after power and audio-route interruptions.

## Self-hosted Wireless ADB architecture

FineVolume implements the local ADB client flow inside the app:

1. **Own identity:** FineVolume creates a private RSA identity and stores it in its app-private no-backup directory. The key is not shared with Shizuku or another ADB app.
2. **Initial pairing:** The user opens Developer Options → Wireless Debugging → Pair device with pairing code. FineVolume uses the displayed pairing port and six-digit code to complete ADB's TLS pairing protocol with the local device.
3. **Reconnect:** Once paired, FineVolume discovers the local Wireless Debugging TLS connect service and authenticates with its own saved identity.
4. **Shell helper:** FineVolume opens an ADB shell-v2 stream and launches its own `app_process` helper. The helper reports its process UID, and FineVolume accepts the advanced engine only when the UID is the expected Android ADB shell UID 2000.
5. **Persistent control channel:** The app maintains a newline-delimited JSON control stream to that helper. A foreground service probes the helper and retries after transport loss while Wireless Debugging is available.
6. **Fallback:** If pairing is absent, Wireless Debugging is off, ADB disconnects, or the helper fails its health check, the backend switches to the public AudioManager implementation.

The helper uses hidden Android audio APIs from the shell process to inspect playback configurations and apply player-level software gain. This behaviour can vary by Android release, OEM audio framework, output route and player implementation. It is not a public Android per-app volume API and is not bit-perfect.

## First-time setup on Android 11+

1. Open **Settings → Developer options**.
2. Enable **Wireless debugging**.
3. Select **Pair device with pairing code**.
4. Keep the pairing dialog visible. Enter its pairing port and six-digit code in FineVolume and tap **Pair FineVolume**.
5. Accept the Android pairing request if prompted.
6. FineVolume saves its own ADB key and attempts to start the shell helper. The in-app status should show **Shell UID 2000** when the helper is ready.
7. Enable FineVolume's Accessibility Service to intercept the hardware volume keys.

The pairing port/code are temporary and are not saved. The ADB identity is retained privately by FineVolume. When Wireless Debugging is disabled, the advanced helper cannot connect. Enable Wireless Debugging again and use **Reconnect / Restart Audio Helper** if automatic reconnect does not occur.

Android may stop background processes or turn Wireless Debugging off, including after a reboot. The foreground service attempts recovery while it is permitted to run; no ordinary app can guarantee permanent shell access or silently re-enable Wireless Debugging.

## Permissions and network use

- **Accessibility Service:** used for hardware volume-key interception and the custom overlay.
- **Modify Audio Settings:** required for native stream-volume adjustment.
- **Foreground service:** maintains the locally paired ADB shell connection while volume control is active.
- **Internet/network socket permission:** used for local ADB Wireless Debugging TLS and Android service discovery. FineVolume does not use a remote backend or upload audio data.
- **Do Not Disturb access:** required for ringer-mode controls.

## Audio quality

Player-level attenuation is digital software gain. It is not a bit-perfect or lossless audio path. FineVolume does not claim to bypass Android's audio mixer or a device's master-volume limit.

## Third-party components

- `libadb-android` by MuntashirAkon, dual-licensed GPL-3.0-or-later or Apache-2.0. FineVolume uses it under Apache-2.0 for ADB pairing, TLS connection, shell streams and service discovery.
- Conscrypt, Apache-2.0, provides TLS support used by the ADB client.
- HiddenApiBypass is used only inside the ADB shell helper process, not in FineVolume's normal app process.
- Jetpack Compose, AndroidX, Kotlin Coroutines and Hilt are used for the application UI and lifecycle.

See the corresponding upstream projects for their complete notices and terms.

## Build

```bash
./gradlew testDebugUnitTest assembleDebug
```

Debug APK output:

`app/build/outputs/apk/debug/app-debug.apk`
