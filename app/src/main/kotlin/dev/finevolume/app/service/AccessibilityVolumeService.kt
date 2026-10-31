package dev.finevolume.app.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import dev.finevolume.app.data.SettingsRepository
import dev.finevolume.app.domain.*
import dev.finevolume.app.domain.model.AudioStream
import kotlinx.coroutines.*
import javax.inject.Inject
import dev.finevolume.app.ui.overlay.VolumeOverlayController

/**
 * Intercepts hardware volume keys for fine media adjustment.
 *
 * During calls and transient system alerts, keys are passed back to Android so
 * the OEM can adjust the correct stream or silence the alert.
 */
@AndroidEntryPoint
class AccessibilityVolumeService : AccessibilityService() {

    @Inject lateinit var buttonBehaviorEngine: ButtonBehaviorEngine
    @Inject lateinit var volumeController: VolumeController
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var volumeOverlayController: VolumeOverlayController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dragChannel = kotlinx.coroutines.channels.Channel<Float>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var repeatJob: Job? = null
    private var consumingVolumeKey = false
    private var systemAudioReceiverRegistered = false

    private val systemAudioChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            repeatJob?.cancel()
            repeatJob = null
            scope.launch {
                volumeController.invalidatePrecisePosition(AudioStream.MUSIC)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            volumeOverlayController.attachContext(this)
            registerSystemAudioChangeReceiver()

            // Conflated drag processor prioritizes the latest slider position.
            scope.launch {
                for (newVolume in dragChannel) {
                    try {
                        volumeController.setAbsolute(AudioStream.MUSIC, newVolume)
                    } catch (_: Exception) {}
                }
            }

            volumeOverlayController.onVolumeChange = { newVolume ->
                dragChannel.trySend(newVolume)
            }

            volumeOverlayController.onSettingsClick = {
                try {
                    val intent = android.content.Intent(this@AccessibilityVolumeService, dev.finevolume.app.MainActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val fallback = android.content.Intent(android.provider.Settings.ACTION_SOUND_SETTINGS).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(fallback)
                    } catch (_: Exception) {}
                }
            }

            volumeOverlayController.onMuteClick = {
                runCatching {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_TOGGLE_MUTE,
                        AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
                    )
                }
            }
        } catch (_: Exception) {}
    }

    private fun registerSystemAudioChangeReceiver() {
        if (systemAudioReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(systemAudioChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(systemAudioChangeReceiver, filter)
            }
            systemAudioReceiverRegistered = true
        } catch (_: Exception) {
            systemAudioReceiverRegistered = false
        }
    }

    /**
     * Android's public playback-state API lets us avoid swallowing a key while
     * ringtone, notification, alarm, call, assistant, or system-interface audio
     * is active. AudioManager.mode independently covers active telephony/VoIP.
     */
    private fun shouldDelegateVolumeKeysToSystem(): Boolean {
        return runCatching {
            if (audioManager.mode != AudioManager.MODE_NORMAL) return true
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

            val systemPriorityUsages = setOf(
                AudioAttributes.USAGE_NOTIFICATION,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
                AudioAttributes.USAGE_NOTIFICATION_EVENT,
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
                AudioAttributes.USAGE_ALARM,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
                AudioAttributes.USAGE_VOICE_COMMUNICATION,
                AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
                AudioAttributes.USAGE_ASSISTANT
            )

            audioManager.activePlaybackConfigurations.any { config ->
                config.audioAttributes?.usage in systemPriorityUsages
            }
        }.getOrDefault(false)
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return false
        val keyCode = event.keyCode

        if (keyCode == KeyEvent.KEYCODE_BACK && volumeOverlayController.isExpanded) {
            if (event.action == KeyEvent.ACTION_UP) volumeOverlayController.dismissExpanded()
            return true
        }

        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        val settings = settingsRepository.cachedSettings
        if (!settings.isEnabled) {
            repeatJob?.cancel()
            repeatJob = null
            consumingVolumeKey = false
            return false
        }

        try {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    // Preserve a well-formed down/up sequence once a key is consumed.
                    if (event.repeatCount > 0) return consumingVolumeKey

                    // Calls and system alerts retain Android/Samsung stream selection.
                    if (shouldDelegateVolumeKeysToSystem()) {
                        repeatJob?.cancel()
                        repeatJob = null
                        consumingVolumeKey = false
                        return false
                    }

                    consumingVolumeKey = true
                    val action = buttonBehaviorEngine.handleKeyEvent(event, settings)
                    executeAction(action)

                    repeatJob?.cancel()
                    val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) Direction.UP else Direction.DOWN
                    val steps = settings.mediaIncrement.coerceAtLeast(1)
                    val repeatInterval = settings.longPressRepeatIntervalMs.coerceIn(20, 500).toLong()

                    repeatJob = scope.launch {
                        delay(350L)
                        while (isActive) {
                            // If an alert or call begins while the key is held, stop
                            // repeating. The matching key-up remains consumed.
                            if (shouldDelegateVolumeKeysToSystem()) break
                            try {
                                when (direction) {
                                    Direction.UP -> volumeController.stepUp(AudioStream.MUSIC, steps)
                                    Direction.DOWN -> volumeController.stepDown(AudioStream.MUSIC, steps)
                                }
                            } catch (_: Exception) {}
                            delay(repeatInterval)
                        }
                    }
                    return true
                }

                KeyEvent.ACTION_UP -> {
                    val consumeUp = consumingVolumeKey
                    consumingVolumeKey = false
                    repeatJob?.cancel()
                    repeatJob = null
                    if (!consumeUp) return false
                    buttonBehaviorEngine.handleKeyEvent(event, settings)
                    return true
                }

                else -> return consumingVolumeKey
            }
        } catch (_: Exception) {
            repeatJob?.cancel()
            repeatJob = null
            val wasConsuming = consumingVolumeKey
            consumingVolumeKey = false
            return wasConsuming
        }
    }

    private fun executeAction(action: KeyAction) {
        when (action) {
            is KeyAction.StepVolume -> scope.launch {
                try {
                    when (action.direction) {
                        Direction.UP -> volumeController.stepUp(AudioStream.MUSIC, action.steps)
                        Direction.DOWN -> volumeController.stepDown(AudioStream.MUSIC, action.steps)
                    }
                } catch (_: Exception) {}
            }
            is KeyAction.Consume, is KeyAction.PassThrough -> Unit
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        repeatJob?.cancel()
        repeatJob = null
        consumingVolumeKey = false
        scope.launch {
            volumeController.invalidatePrecisePosition(AudioStream.MUSIC)
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        repeatJob?.cancel()
        repeatJob = null
        consumingVolumeKey = false
        scope.coroutineContext[Job]?.cancelChildren()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        repeatJob?.cancel()
        repeatJob = null
        consumingVolumeKey = false
        if (systemAudioReceiverRegistered) {
            runCatching { unregisterReceiver(systemAudioChangeReceiver) }
            systemAudioReceiverRegistered = false
        }
        dragChannel.close()
        scope.cancel()
        volumeOverlayController.detachContext()
        super.onDestroy()
    }
}
