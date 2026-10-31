package dev.finevolume.app.data

enum class AccelerationMode {
    LINEAR, EXPONENTIAL, ADAPTIVE
}

data class AppSettings(
    val mediaResolution: Int = 120,
    val mediaIncrement: Int = 1,
    val longPressRepeatIntervalMs: Int = 60,
    val bitPerfectProtection: Boolean = true,
    val hapticLevel: Int = 0,
    val accelerationMode: AccelerationMode = AccelerationMode.EXPONENTIAL,
    val wirelessAdbEnabled: Boolean = true,
    val isEnabled: Boolean = true
)
