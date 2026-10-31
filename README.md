<p align="center">
  <img src="docs/logo.png" alt="FineVolume Logo" width="180" style="border-radius: 36px; box-shadow: 0 8px 24px rgba(0,0,0,0.3);"/>
</p>

<h1 align="center">FineVolume</h1>

<p align="center">
  <strong>One UI-Inspired Android Volume Panel with 120-Step Fine Adjustment</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-10.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 10+"/>
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose"/>
  <img src="https://img.shields.io/badge/Design-Material%20You%20%2F%20Pixel-7C4DFF?style=flat-square" alt="Samsung One UI inspired"/>
  <img src="https://img.shields.io/badge/Security-Local%20ADB%20Only-00C853?style=flat-square" alt="Local ADB only"/>
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square" alt="License"/>
</p>

---

## 🌟 Overview

Android's exposed media-volume range varies by device, firmware, route, and audio stack. FineVolume adds a fine-control layer on top of that native range.

**FineVolume** intercepts supported hardware volume-key events through its AccessibilityService and displays a custom, **Samsung One UI-inspired extended volume panel**. The expanded sheet provides system-stream controls and active-app sliders. It is an app-owned overlay, not a replacement for Samsung's SystemUI implementation.

The interface is built with **Jetpack Compose**, with separated labels, readable volume tracks, touch adjustment, and hardware-key repeat.

---

## ✨ Key Features

### 🎚️ 120-Step Granular Precision
- **Sub-step software attenuation**: Combines the native Android media-volume index with embedded ADB shell player-level float gain to make adjustments finer than the device's exposed hardware steps. Actual behaviour depends on the player app, Android build, output route, and audio framework.
- **Perceptual interpolation**: FineVolume applies a cubic attenuation curve within each native step. This is a software gain stage, not a bit-perfect or lossless volume path.

### 🎨 Samsung One UI-Inspired Volume Panels
- **Clear expanded controls**: Stream and app names and percentages sit above their volume tracks, avoiding text overlap at low volume levels.
- **Distinct volume position**: A separate divider marks the current level on a rounded active/inactive track.
- **Edge-Optimized Geometry**: Engineered with vertical clearance so the divider bar **never shrinks, clips, or deforms** at minimum ($0\%$) or maximum ($100\%$) volume.
- **Dynamic Color Support**: Uses Material You dynamic colors in light and dark modes.

### 📱 Dual-State Interactive Overlays

#### 1. Collapsed Right-Edge Overlay
- **Quick Ringer Mode Toggle**: Cycle between **Normal**, **Vibrate**, and **Silent (Mute)** with a single tap.
- **Vertical 120-Step Media Slider**: Drag directly with sub-pixel precision or adjust via hardware keys.
- **Live Caption Quick Toggle**: Instant toggle with system state synchronization.
- **Expand / Tune Button**: Opens the full multi-stream panel with a fluid spring animation.

#### 2. Expanded Multi-Stream Dialog
- **Zero Background Dimming**: 100% transparent backdrop—never grays out your wallpaper or active app.
- **All System Audio Streams**:
  - 🎵 **Media** (with granular stepping)
  - 📞 **Voice Call**
  - 🔔 **Ring**
  - 💬 **Notifications** (with intuitive mute warnings)
  - ⏰ **Alarm**
- **Live Caption Center Pill**: Dedicated system accessibility control.
- **System Sound Shortcut & Done Button**: Quick access to device sound settings, with automatic dismissal directly back to your screen when finished.

### 🎵 Per-App Volume Control
- **Independent App Sliders**: Automatically detects running audio apps (Spotify, YouTube, Chrome, VLC, YouTube Music, Games, etc.) and provides individual volume sliders inside the expanded panel.
- **Embedded ADB player attenuation**: FineVolume includes its own ADB client, pairs directly with Android Wireless Debugging, and starts a small audio helper under the Android shell UID (2000). The helper uses Android's hidden PlayerProxy interface on compatible firmware and players. This is software attenuation, not a public Android per-app volume API and not bit-perfect.
- **Playback-session tracking**: Tracks active audio playback configurations and reapplies saved gains when Android reports session changes. Some players may recreate sessions or manage their own volume, so verify behaviour with each app.

### ⏸️ Pause Mode & Security Compatibility
- **One-Tap Quick Pause**: Temporarily pause FineVolume with one switch in Settings whenever you need to bypass banking or security apps that block third-party accessibility services.

### ⌨️ Physical Volume Button Engine
- **Single Press**: Steps exactly 1 FineVolume increment.
- **Smooth Long Press**: Continuous, rapid volume transitions with automatic repeat loops.
- **Hardware key interception**: An enabled AccessibilityService can consume volume-key events and show FineVolume's own overlay. OEM behaviour and other accessibility services can affect key-event delivery.

---

## 🏗️ Architecture

```
┌────────────────────────────────────────────────────────┐
│                   FineVolume System                    │
└────────────────────────────────────────────────────────┘
                           │
         ┌─────────────────┴─────────────────┐
         ▼                                   ▼
┌────────────────────────┐         ┌────────────────────────┐
│ Accessibility Service  │         │ Embedded ADB Audio Core │
│ - Hardware Key Filter  │         │ - ADB Shell UID Helper │
│ - Hold-to-Repeat Loop  │         │ - Cubic Interpolation  │
└────────────────────────┘         └────────────────────────┘
         │                                   │
         └─────────────────┬─────────────────┘
                           ▼
┌────────────────────────────────────────────────────────┐
│               VolumeOverlayController                  │
│ - WindowManager Overlay (TYPE_ACCESSIBILITY_OVERLAY)   │
│ - Pure Jetpack Compose Lifecycle Management            │
│ - 120-Step Mutex-Guarded State Engine                  │
└────────────────────────────────────────────────────────┘
```

- **`AccessibilityVolumeService`**: Intercepts volume key events before the OEM SystemUI can display the stock panel.
- **`VolumeController`**: Mutex-guarded state holder managing 120 fractional volume levels and broadcast updates.
- **`AdbPlayerBackend`**: Uses FineVolume's own ADB-over-Wireless-Debugging connection to start and communicate with the audio helper under shell UID 2000. It does not use Shizuku or root.
- **`VolumeOverlayController`**: Custom `ComposeView` window layout supporting fast gesture drags, touch outside dismissal, and spring animations.

---

## 🔒 Privacy & Permissions

- 🛡️ **Local-network only**: FineVolume uses Android network permissions to reach this device's local Wireless Debugging endpoint and discover its TLS connection port. It does not upload audio, app usage, or pairing keys to a cloud service.
- 🔋 **Battery Efficient**: Runs as a passive Accessibility Service that only activates when volume keys are pressed or the panel is drawn.

### Required Permissions
1. **Accessibility Service**: Intercepts physical volume button presses.
2. **Wireless Debugging (for fine-grained player attenuation)**: FineVolume pairs directly with the phone using Android's six-digit pairing code. It saves its own ADB identity encrypted with Android Keystore and reconnects using the separate ADB TLS connection port. Shizuku is not used or required.
3. **Do Not Disturb (Notification Policy)**: Allows toggling between Ring, Vibrate, and Silent modes directly from the overlay.

---

## 📦 Installation & Setup

1. **Install APK**:
   - Download the latest **[`FineVolume.apk`](FineVolume.apk)**.
2. **Enable Accessibility Service**:
   - Open **Settings** → **Accessibility** → **FineVolume** → Turn on.
3. **Grant DND Access**:
   - Tap the Ringer button on the overlay or follow the in-app prompt to allow Do Not Disturb access.
4. **Pair FineVolume** *(once)*:
   - Enable Developer Options → Wireless Debugging. Choose **Pair device with pairing code**, enter the pairing port and six-digit code in FineVolume, and tap **Pair FineVolume**. Keep Wireless Debugging enabled while using the embedded ADB audio helper. FineVolume never toggles Wireless Debugging off/on.

---

## 🛠️ Building from Source

```bash
# Clone the repository
git clone https://github.com/broknhrt2562/finevolume.git
cd finevolume

# Build optimized release APK
./gradlew assembleRelease
```

The output APK will be generated at:
`app/build/outputs/apk/release/app-release.apk`

---

## 📄 License

Licensed under the [Apache License, Version 2.0](LICENSE).
