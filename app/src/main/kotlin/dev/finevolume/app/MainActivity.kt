package dev.finevolume.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dagger.hilt.android.AndroidEntryPoint
import dev.finevolume.app.adb.WirelessAdbStage
import dev.finevolume.app.adb.WirelessAdbStatus
import dev.finevolume.app.domain.model.BackendType
import dev.finevolume.app.domain.model.VolumeCapabilities
import dev.finevolume.app.ui.theme.FineVolumeTheme
import dev.finevolume.app.ui.viewmodel.MainViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.startWirelessAdbWatchdog()
        ContextCompat.startForegroundService(this, Intent(this, dev.finevolume.app.service.VolumeControlForegroundService::class.java))
        setContent {
            FineVolumeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val settings by viewModel.settings.collectAsState()
                    val capabilities by viewModel.activeBackendCapabilities.collectAsState()
                    val wirelessAdbStatus by viewModel.wirelessAdbStatus.collectAsState()

                    MainScreen(
                        settings = settings,
                        capabilities = capabilities,
                        wirelessAdbStatus = wirelessAdbStatus,
                        onToggleEnabled = viewModel::updateIsEnabled,
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onOpenDeveloperOptions = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        },
                        onPairWirelessAdb = viewModel::pairWirelessAdb,
                        onReconnectWirelessAdb = viewModel::reconnectWirelessAdb,
                        onForgetWirelessAdb = viewModel::forgetWirelessAdbPairing,
                        onResolutionChange = viewModel::updateMediaResolution,
                        onIncrementChange = viewModel::updateMediaIncrement,
                        onLongPressIntervalChange = viewModel::updateLongPressRepeatInterval,
                        onToggleHaptic = viewModel::updateHapticEnabled
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settings: dev.finevolume.app.data.AppSettings,
    capabilities: VolumeCapabilities,
    wirelessAdbStatus: WirelessAdbStatus,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onPairWirelessAdb: (String, String) -> Unit,
    onReconnectWirelessAdb: () -> Unit,
    onForgetWirelessAdb: () -> Unit,
    onResolutionChange: (Float) -> Unit,
    onIncrementChange: (Float) -> Unit,
    onLongPressIntervalChange: (Float) -> Unit,
    onToggleHaptic: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FineVolume", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppToggleCard(isEnabled = settings.isEnabled, onToggle = onToggleEnabled)
            BankingCompatibilityCard()

            WirelessAdbStatusCard(
                status = wirelessAdbStatus,
                capabilities = capabilities,
                onOpenDeveloperOptions = onOpenDeveloperOptions,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onPair = onPairWirelessAdb,
                onReconnect = onReconnectWirelessAdb,
                onForget = onForgetWirelessAdb
            )

            ConfigurationCard(
                title = "Volume Resolution",
                value = settings.mediaResolution.toFloat(),
                range = 15f..500f,
                effectiveValue = capabilities.effectiveResolution.coerceAtMost(settings.mediaResolution),
                description = "How many levels between 0 and Max.",
                onValueChange = onResolutionChange
            )

            ConfigurationCard(
                title = "Standard Increment",
                value = settings.mediaIncrement.toFloat(),
                range = 1f..50f,
                effectiveValue = null,
                description = "Steps applied per volume button press (and per tick while holding).",
                onValueChange = onIncrementChange
            )

            ConfigurationCard(
                title = "Long-Press Speed",
                value = settings.longPressRepeatIntervalMs.toFloat(),
                range = 20f..500f,
                effectiveValue = null,
                description = "Interval between volume steps while holding a button (ms). Lower = faster.",
                onValueChange = onLongPressIntervalChange,
                displayTransform = { ms -> ms.toInt().toString() + " ms" }
            )

            HapticFeedbackCard(
                hapticEnabled = settings.hapticLevel > 0,
                onToggle = onToggleHaptic
            )
        }
    }
}

@Composable
fun WirelessAdbStatusCard(
    status: WirelessAdbStatus,
    capabilities: VolumeCapabilities,
    onOpenDeveloperOptions: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onPair: (String, String) -> Unit,
    onReconnect: () -> Unit,
    onForget: () -> Unit
) {
    var pairingPort by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var pairingCode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val ready = status.isReady
    val statusColor = when (status.stage) {
        WirelessAdbStage.READY -> Color(0xFF2E7D32)
        WirelessAdbStage.PAIRING, WirelessAdbStage.CONNECTING, WirelessAdbStage.STARTING_HELPER -> MaterialTheme.colorScheme.primary
        WirelessAdbStage.ERROR -> MaterialTheme.colorScheme.error
        else -> Color(0xFFB26A00)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("FineVolume Wireless Debugging", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (ready) "Self-hosted shell engine connected" else "Independent ADB engine",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(status.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "Active backend: " + capabilities.backendType.name +
                    if (ready) "  •  Shell UID " + status.helperUid + "  •  " + status.activeMediaSessions + " media sessions"
                    else "  •  Native Android controls active",
                style = MaterialTheme.typography.labelMedium,
                color = statusColor
            )
            status.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Text(
                "First-time setup: open Developer Options, enable Wireless Debugging, then choose Pair device with pairing code. Keep the pairing dialog visible and enter its pairing port and six-digit code below. FineVolume stores its own ADB key privately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = pairingPort,
                onValueChange = { pairingPort = it.filter(Char::isDigit).take(5) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Wireless Debugging pairing port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = pairingCode,
                onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Six-digit pairing code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Button(
                onClick = { onPair(pairingPort, pairingCode) },
                enabled = !ready && pairingPort.toIntOrNull() in 1..65535 && pairingCode.length == 6 &&
                    status.stage != WirelessAdbStage.PAIRING,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (status.stage == WirelessAdbStage.PAIRING) "Pairing…" else "Pair FineVolume")
            }

            OutlinedButton(
                onClick = onOpenDeveloperOptions,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Open Developer Options")
            }

            if (status.stage != WirelessAdbStage.NOT_PAIRED) {
                OutlinedButton(
                    onClick = onReconnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Reconnect / Restart Audio Helper")
                }
                TextButton(onClick = onForget, modifier = Modifier.align(Alignment.End)) {
                    Text("Forget FineVolume pairing")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

            Button(
                onClick = onOpenAccessibilitySettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Enable Accessibility Service")
            }
        }
    }
}

@Composable
fun ConfigurationCard(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    effectiveValue: Int?,
    description: String,
    onValueChange: (Float) -> Unit,
    displayTransform: ((Float) -> String)? = null
) {
    var sliderValue by androidx.compose.runtime.remember(value) {
        androidx.compose.runtime.mutableFloatStateOf(
            value.coerceIn(range.start, range.endInclusive).roundToInt().toFloat()
        )
    }
    var showEditor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var editText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    val updateStep: (Int) -> Unit = { delta ->
        val next = (sliderValue.toInt() + delta)
            .coerceIn(range.start.toInt(), range.endInclusive.toInt())
            .toFloat()
        if (next != sliderValue) {
            sliderValue = next
            onValueChange(next)
        }
    }
    val latestUpdateStep by rememberUpdatedState(updateStep)
    val parsedEditValue = editText.toIntOrNull()
    val validEditValue = parsedEditValue?.takeIf {
        it >= range.start.toInt() && it <= range.endInclusive.toInt()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = displayTransform?.invoke(sliderValue) ?: sliderValue.toInt().toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            editText = sliderValue.toInt().toString()
                            showEditor = true
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RepeatableStepButton(
                    icon = { Icon(Icons.Rounded.Remove, contentDescription = null) },
                    description = "Decrease $title",
                    onStep = { latestUpdateStep(-1) }
                )
                Slider(
                    modifier = Modifier.weight(1f),
                    value = sliderValue,
                    onValueChange = {
                        sliderValue = it.roundToInt().toFloat().coerceIn(range.start, range.endInclusive)
                    },
                    onValueChangeFinished = { onValueChange(sliderValue) },
                    valueRange = range,
                    steps = 0
                )
                RepeatableStepButton(
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    description = "Increase $title",
                    onStep = { latestUpdateStep(1) }
                )
            }

            AnimatedVisibility(visible = effectiveValue != null && effectiveValue < sliderValue.toInt()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp)
                ) {
                    Text(
                        "Native Android volume provides $effectiveValue hardware steps. This mode works without Shizuku or root; public Android APIs do not expose per-app sub-step gain.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text("Set $title") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it.filter(Char::isDigit).take(4) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("Value") },
                        supportingText = { Text("Allowed range: ${range.start.toInt()} to ${range.endInclusive.toInt()}") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = validEditValue != null,
                    onClick = {
                        val next = validEditValue!!.toFloat()
                        sliderValue = next
                        onValueChange(next)
                        showEditor = false
                    }
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun RepeatableStepButton(
    icon: @Composable () -> Unit,
    description: String,
    onStep: () -> Unit
) {
    val latestOnStep by rememberUpdatedState(onStep)
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                kotlinx.coroutines.coroutineScope {
                    val repeatScope = this
                    detectTapGestures(
                        onPress = {
                            latestOnStep()
                            val repeatJob = repeatScope.launch {
                                delay(350L)
                                while (isActive) {
                                    latestOnStep()
                                    delay(55L)
                                }
                            }
                            tryAwaitRelease()
                            repeatJob.cancel()
                        }
                    )
                }
            }
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick(label = description) {
                    latestOnStep()
                    true
                }
            },
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

@Composable
fun AppToggleCard(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isEnabled) "FineVolume Active" else "FineVolume Paused",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isEnabled) {
                        "Active — Intercepting volume keys and displaying overlay."
                    } else {
                        "Paused — Default Android volume in use. Volume keys and overlays are not intercepted (safe for banking apps)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isEnabled) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
fun HapticFeedbackCard(
    hapticEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Haptic Feedback",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (hapticEnabled) "Enabled — Tactile feedback on volume level changes" else "Disabled — Silent volume adjustment",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = hapticEnabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
fun BankingCompatibilityCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "App Compatibility & Security",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "If banking or security apps prompt you to disable accessibility or stop working due to FineVolume, you can use the Pause toggle above, or use:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF1A73E8).copy(alpha = 0.12f),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/T31n/Geto")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "GETO",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp,
                        color = Color(0xFF1A73E8)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = "Open Geto",
                        tint = Color(0xFF1A73E8),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
