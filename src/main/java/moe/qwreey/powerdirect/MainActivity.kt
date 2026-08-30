package moe.qwreey.powerdirect

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.qwreey.powerdirect.adb.AdbBootstrap

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    companion object {
        /**
         * 사용자를 여기로 보내야 하는 모든 곳 — 타일, 위젯, 알림 — 이 쓴다.
         *
         * `FLAG_ACTIVITY_NEW_TASK` 가 붙어 있어야 한다. 부르는 쪽이 액티비티가 아니라
         * 타일 서비스나 브로드캐스트 리시버라 시작할 태스크가 없다.
         */
        fun intent(context: Context): Intent =
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class MainViewModel(app: Application) : ViewModel() {
    private val context = app.applicationContext
    private val bootstrap = AdbBootstrap(context)
    private val prefs = Prefs(context)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    data class UiState(
        val hasPermission: Boolean = false,
        val wirelessDebugging: Boolean = false,
        val passThrough: Int? = null,
        val battery: BatteryState? = null,
        val mode: Mode = Mode.Auto,
        val low: Int = 70,
        val high: Int = 80,
        val keepAdb: Boolean = false,
        val agent: Agent.Beat? = null,
        val busy: Boolean = false,
        val log: List<String> = emptyList(),
    )

    init {
        refresh()
        // 앱을 열면 항상 확인하고, 죽어 있으면 되살린다.
        //
        // 여기서 isAlive(타임스탬프) 로 미리 거르지 않는다. 그 싼 검사가 틀리면 —
        // 죽은 에이전트를 최근 심장박동 때문에 살아 있다고 보면 — 정확한 검사(ping 왕복)로
        // 가는 길 자체가 막혀서 영원히 교정되지 않는다. 실제로 그렇게 됐다.
        // runPolicy 안의 ensureRunning 이 ping 으로 확인하고, 살아 있으면 아무것도 하지 않는다.
        if (prefs.mode != Mode.Off && bootstrap.canWriteSecureSettings) {
            runPolicy(trigger = "opened")
        }
        viewModelScope.launch {
            while (true) {
                _state.value = _state.value.copy(
                    battery = Battery.read(context),
                    passThrough = PassThrough.read(context),
                    agent = Agent.beat(context),
                )
                delay(3_000)
            }
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(
            hasPermission = bootstrap.canWriteSecureSettings,
            wirelessDebugging = bootstrap.isWirelessDebuggingEnabled,
            passThrough = PassThrough.read(context),
            battery = Battery.read(context),
            mode = prefs.mode,
            low = prefs.low,
            high = prefs.high,
            keepAdb = prefs.keepAdbEnabled,
            agent = Agent.beat(context),
            log = RunLog.read(context),
        )
    }

    // 화면 로그와 백그라운드 로그를 하나로 둔다. 둘을 나누면 사용자는
    // 앱이 안 떠 있을 때 무슨 일이 있었는지 볼 방법이 없다.
    private fun log(line: String) {
        RunLog.append(context, line)
        _state.value = _state.value.copy(log = RunLog.read(context))
    }

    fun clearLog() {
        RunLog.clear(context)
        _state.value = _state.value.copy(log = emptyList())
    }

    fun setMode(mode: Mode) {
        prefs.mode = mode
        prefs.bumpGeneration()
        // 에이전트가 권위다. 설정만 넘기면 다음 주기에 알아서 반영한다.
        Agent.writeConfig(context, prefs)
        SurfaceState.refreshAll(context)
        refresh()
    }

    fun setBand(low: Int, high: Int) {
        // 설정 파일도 같이 갱신한다 — 아래에서 refresh() 전에 prefs 가 먼저 바뀐다.
        // 밴드가 뒤집히거나 붙어버리면 임계값 근처에서 계속 뒤집힌다. 최소 5%p 를 띄운다.
        prefs.low = low.coerceAtMost(high - 5)
        prefs.high = high.coerceAtLeast(prefs.low + 5)
        prefs.bumpGeneration()
        Agent.writeConfig(context, prefs)
        SurfaceState.refreshAll(context)
        refresh()
    }

    fun setKeepAdb(value: Boolean) {
        prefs.keepAdbEnabled = value
        refresh()
    }

    /**
     * 정책을 지금 한 번 돌린다.
     *
     * @param trigger 로그에 남길 이유. 라벨이 거짓말하면 로그를 읽고 잘못 판단하게 된다 —
     *   앱이 열리며 자동으로 돈 것을 "manual" 로 찍어두면 사용자가 버튼을 눌렀다고 읽힌다.
     */
    fun runPolicy(trigger: String = "manual") = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        // run() 이 스스로 RunLog 에 적으므로 여기서 결과를 또 적지 않는다.
        runCatching { PolicyRunner.run(context, trigger = trigger) }
            .onFailure { log("[$trigger] failed: ${it.message}") }
        _state.value = _state.value.copy(busy = false)
        refresh()
    }

    /** 에이전트를 세우고 값을 되돌린다. */
    fun stopAgent() = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        PolicyRunner.shutDown(context)
        _state.value = _state.value.copy(busy = false)
        refresh()
    }

    fun showPairingNotification() {
        PairingNotification.post(context)
        log("→ pairing notification posted")
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                return MainViewModel(app) as T
            }
        }
    }
}

@Composable
private fun Screen(vm: MainViewModel = viewModel(factory = MainViewModel.Factory)) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Power Direct", style = MaterialTheme.typography.headlineMedium)
        Text(
            "While plugged in, let the charger feed the system directly instead of " +
                "cycling the battery.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── 상태 ────────────────────────────────────────────────────────────
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bypass", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        when (s.passThrough) {
                            1 -> "on"
                            0 -> "off"
                            else -> "not supported on this firmware"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (s.passThrough == 1) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Line("Shell access", if (s.hasPermission) "granted" else "not set up")
                Line("Wireless debugging", if (s.wirelessDebugging) "on" else "off")
                if (!s.hasPermission) {
                    Text(
                        "Run this once from a computer:\n" +
                            "adb shell pm grant moe.qwreey.powerdirect " +
                            "android.permission.WRITE_SECURE_SETTINGS",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // ── 배터리 ──────────────────────────────────────────────────────────
        s.battery?.let { b ->
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Battery", style = MaterialTheme.typography.titleMedium)
                    Line("Level", "${b.levelPercent}%")
                    Line("Battery current", "${if (b.currentMilliAmps > 0) "+" else ""}${b.currentMilliAmps} mA")
                    Text(
                        b.summary(s.passThrough),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Line("Temperature", "%.1f °C".format(b.temperatureCelsius))
                    Line("Charger", if (b.plugged) "connected" else "none")
                    Text(
                        "This is current into or out of the battery, not what the system draws. " +
                            "While bypassing, the charger feeds the system directly and that draw " +
                            "does not show up here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (b.levelPercent < 20) {
                        Text(
                            "Below 20% the firmware ignores this flag and charges normally.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // ── 에이전트 ────────────────────────────────────────────────────────
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Agent", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (s.agent?.alive == true) "running" else "not running",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (s.agent?.alive == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
                s.agent?.let { a ->
                    Line("Last seen", "${a.ageSeconds}s ago")
                    Line("It sees", "${a.level ?: "?"}% ${if (a.plugged) "plugged" else "unplugged"}")
                    Line("It wants", if (a.want == "keep") "no change" else a.want)
                }
                Text(
                    "A small shell process applies the setting. The app cannot write it itself. " +
                        "ADB is used once, only to start this — after that it runs on its own, so " +
                        "battery optimisation and Doze do not affect it. It stops on reboot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── 모드 ────────────────────────────────────────────────────────────
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Mode", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Mode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = s.mode == mode,
                            onClick = { vm.setMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size),
                            enabled = !s.busy,
                        ) { Text(mode.name) }
                    }
                }
                val clash = s.agent?.protectOn == true &&
                    s.agent?.protectThreshold?.let { s.high >= it } == true
                if (clash) {
                    Text(
                        "Samsung's Battery protect is on and stops charging at " +
                            "${s.agent?.protectThreshold}%. With \"bypass at\" set that high or " +
                            "higher, Samsung acts first and holds the battery there on a trickle — " +
                            "the battery never leaves the current path. Set it lower.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val level = s.battery?.levelPercent
                if (s.mode == Mode.Auto && level != null && level >= s.low && level < s.high) {
                    Text(
                        "$level% is inside the band, so the current state is held — that is what " +
                            "stops it flipping around a threshold. Moving either slider re-decides " +
                            "from scratch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    when (s.mode) {
                        Mode.Auto -> "Charge up to ${s.high}%, then bypass. Charge again below ${s.low}%."
                        Mode.Always -> "Bypass whenever plugged in, at any level."
                        Mode.Off -> "Charge normally."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (s.mode == Mode.Auto) {
                    var low by remember(s.low) { mutableFloatStateOf(s.low.toFloat()) }
                    var high by remember(s.high) { mutableFloatStateOf(s.high.toFloat()) }
                    Text("Bypass at ${high.toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = high,
                        onValueChange = { high = it },
                        onValueChangeFinished = { vm.setBand(low.toInt(), high.toInt()) },
                        valueRange = 30f..100f,
                    )
                    Text("Charge again below ${low.toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = low,
                        onValueChange = { low = it },
                        onValueChangeFinished = { vm.setBand(low.toInt(), high.toInt()) },
                        valueRange = Prefs.FLOOR.toFloat()..95f,
                    )
                }
            }
        }

        // ── 동작 ────────────────────────────────────────────────────────────
        if (s.mode == Mode.Off) {
            Text(
                "Mode is Off, so the agent will just keep charging normally. " +
                    "Pick Auto or Always to bypass.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 돌고 있을 때 눌러도 해롭지 않다 — 설정을 다시 넘기고 살아 있는지 확인할 뿐,
            // 다시 띄우지는 않는다. 그러니 막을 게 아니라 이름이 상태를 따라가야 한다.
            Button(onClick = { vm.runPolicy() }, enabled = !s.busy) {
                Text(if (s.agent?.alive == true) "Sync & verify" else "Start agent")
            }
            OutlinedButton(onClick = { vm.showPairingNotification() }, enabled = !s.busy) {
                Text("Pair")
            }
            TextButton(onClick = { vm.stopAgent() }, enabled = !s.busy) { Text("Stop") }
        }

        // ── 고급 ────────────────────────────────────────────────────────────
        Card {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep wireless debugging on after unplugging", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "It stays on while charging either way. Turning it off is what breaks " +
                                "other apps that borrow shell the same way, so this app leaves it " +
                                "alone until you unplug. Enable this to never turn it off — kinder " +
                                "to those apps, but adbd keeps listening for hosts you have paired.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.keepAdb, onCheckedChange = { vm.setKeepAdb(it) })
                }
            }
        }

        // ── 로그 ────────────────────────────────────────────────────────────
        if (s.log.isNotEmpty()) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Activity", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.clearLog() }) { Text("Clear") }
                        TextButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("power-direct", s.log.joinToString("\n")))
                        }) { Text("Copy") }
                    }
                    Text(
                        "Includes runs that happened while the app was closed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Column {
                            s.log.reversed().forEach {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
