package moe.qwreey.powerdirect

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.qwreey.powerdirect.adb.AdbBootstrap

/**
 * 앱 쪽에서 하는 일 전부 — 설정을 에이전트에게 넘기고, 에이전트가 살아 있는지 본다.
 *
 * **정책 판단은 여기서 하지 않는다.** [Agent] 가 한다. 앱은 값을 쓸 권한이 없고,
 * 백그라운드에서 제때 깨어난다는 보장도 없다(App Standby 가 알람을 미룬다).
 * 여기 남은 유일한 특권 작업은 "에이전트가 죽었으면 ADB 로 다시 띄우기" 하나뿐이다.
 */
object PolicyRunner {
    private val lock = Mutex()

    data class Outcome(
        val agentAlive: Boolean,
        val launched: Boolean,
        val beat: Agent.Beat?,
        val battery: BatteryState?,
        val error: String? = null,
    ) {
        override fun toString() = buildString {
            when {
                error != null -> append("agent launch failed: $error")
                launched -> append("agent launched")
                agentAlive -> append("agent alive")
                else -> append("agent state unknown")
            }
            beat?.let {
                append(" · ${it.level ?: "?"}% ")
                append(if (it.plugged) "plugged" else "unplugged")
                append(" want=${it.want} pass_through=${it.passThrough}")
                append(" (${it.ageSeconds}s ago)")
            }
            battery?.let { append(" · ${it.currentMilliAmps}mA") }
        }
    }

    suspend fun run(context: Context, trigger: String = "manual"): Outcome = lock.withLock {
        val prefs = Prefs(context)

        // 설정은 항상 먼저 넘긴다. 에이전트가 다음 주기에 집어간다.
        Agent.writeConfig(context, prefs)

        var launched = false
        var error: String? = null

        // **여기서 살아 있는지 먼저 확인하지 않는다.**
        //
        // 그 판단은 ensureRunning 안에 한 번만 있어야 한다. 호출하는 쪽마다 싼 검사
        // (심장박동 나이) 로 미리 걸러내면, 그 검사가 틀렸을 때 정확한 검사(ping 왕복) 로
        // 가는 길이 막혀 영원히 교정되지 않는다. 실제로 이 게이트를 세 곳에 두었다가
        // 세 번 다 같은 증상을 만들었다.
        //
        // ensureRunning 은 살아 있으면 아무것도 하지 않고 곧장 돌아온다. ADB 는 그 안에서만,
        // 정말 필요할 때만 쓰인다.
        val bootstrap = AdbBootstrap(context)
        Agent.ensureRunning(context, bootstrap).fold(
            onSuccess = { message ->
                launched = message != "already running"
                PairingNotification.cancel(context)
            },
            onFailure = { e ->
                error = e.message ?: e::class.simpleName
                if ((e as? AdbBootstrap.Error)?.failure == AdbBootstrap.Failure.NotPaired) {
                    PairingNotification.post(context)
                }
            },
        )

        val outcome = Outcome(
            agentAlive = Agent.isAlive(context),
            launched = launched,
            beat = Agent.beat(context),
            battery = runCatching { Battery.read(context) }.getOrNull(),
            error = error,
        )
        Log.i(TAG, outcome.toString())
        RunLog.append(context, "[$trigger] $outcome")

        notify(context, outcome)
        schedule(context, if (outcome.error != null) RETRY_MILLIS else WATCHDOG_MILLIS)
        outcome
    }

    /** 알림에서 받은 코드로 페어링하고, 성공하면 이어서 에이전트를 띄운다. */
    suspend fun pair(context: Context, code: String) {
        AdbBootstrap(context).pair(code).fold(
            onSuccess = {
                Log.i(TAG, "paired")
                RunLog.append(context, "[pair] ok")
                PairingNotification.cancel(context)
            },
            onFailure = {
                Log.w(TAG, "pairing failed: ${it.message}")
                RunLog.append(context, "[pair] failed: ${it.message}")
                // 코드가 틀렸거나 대화상자가 이미 닫혔다. 알림은 그대로 두어 다시 시도하게 한다.
                PairingNotification.post(context)
                return
            },
        )
        run(context, trigger = "pair")
    }

    /** 에이전트를 세우고 값을 되돌린다. 사용자가 기능을 끌 때. */
    suspend fun shutDown(context: Context) {
        val prefs = Prefs(context)
        prefs.mode = Mode.Off
        Agent.writeConfig(context, prefs)
        // 에이전트가 살아 있으면 다음 주기에 0 을 쓰고 나간다. 죽어 있으면 이미 할 일이 없다.
        Agent.stop(context)
        cancel(context)
        clearNotification(context)
        RunLog.append(context, "[stop] agent asked to exit")
    }

    // ── 감시 알람 ────────────────────────────────────────────────────────────
    //
    // 정책이 아니라 **에이전트 생존 확인**이 목적이다. 그래서 정확할 필요가 없고,
    // App Standby 가 이걸 미뤄도 상관없다. 에이전트는 그동안 스스로 돌고 있다.

    fun schedule(context: Context, delayMillis: Long = WATCHDOG_MILLIS) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMillis,
            alarmIntent(context),
        )
        Log.d(TAG, "watchdog in ${delayMillis / 1000}s")
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(context))
    }

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, PolicyReceiver::class.java).setAction(PolicyReceiver.ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 충전 세션이 끝났다. 우리가 켠 무선 디버깅이면 되돌린다.
     *
     * 에이전트는 계속 산다 — ADB 와 무관하게 도는 것이 이 구조의 요점이다.
     */
    fun endSession(context: Context) {
        val prefs = Prefs(context)
        if (!prefs.weEnabledAdb || prefs.keepAdbEnabled) return
        AdbBootstrap(context).setWirelessDebugging(false)
        prefs.weEnabledAdb = false
        Log.i(TAG, "session over, wireless debugging restored")
    }

    // ── 알림 ────────────────────────────────────────────────────────────────

    private fun notify(context: Context, outcome: Outcome) {
        val nm = NotificationManagerCompat.from(context)
        val beat = outcome.beat
        if (beat == null || !beat.plugged || beat.passThrough != "1") {
            nm.cancel(NOTIFICATION_ID)
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bypass status", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, PolicyReceiver::class.java).setAction(PolicyReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val current = outcome.battery?.currentMilliAmps
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Bypassing the battery")
            .setContentText(
                "${beat.level ?: "?"}%" + if (current != null) " · $current mA" else "",
            )
            .setOngoing(false)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Charge normally", stop)
            .build()

        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    fun clearNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private const val TAG = "PolicyRunner"
    private const val CHANNEL_ID = "bypass"
    private const val NOTIFICATION_ID = 1

    /** 에이전트 생존 확인 주기. 정확할 필요가 없다. */
    private const val WATCHDOG_MILLIS = 20 * 60_000L

    /** 띄우기에 실패했을 때는 좀 더 빨리 다시 본다. */
    private const val RETRY_MILLIS = 3 * 60_000L
}
