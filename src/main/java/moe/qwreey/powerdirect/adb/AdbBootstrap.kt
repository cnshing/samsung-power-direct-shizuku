package moe.qwreey.powerdirect.adb

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * Borrows the shell user from this device's own adbd.
 *
 * This is the piece that makes the app self-sufficient: `/dev/uhid` needs shell
 * privileges, an app can never have them, and the one door Android leaves open
 * is ADB itself. So the app pairs with the device it is running on, exactly as
 * a desktop would, and uses that to run a single bootstrap command.
 *
 * The privileged connection is deliberately short-lived. It starts the daemon
 * and goes away; the daemon's own supervisor takes it from there, so nothing
 * afterwards depends on ADB still being up. That also means wireless debugging
 * can be turned back off the moment this returns.
 */
class AdbBootstrap(private val context: Context) {
    enum class Failure {
        /** WRITE_SECURE_SETTINGS is not granted, so we cannot turn ADB on. */
        NoPermission,

        /** Wireless debugging is off and nothing is listening. */
        NoAdbPort,

        /** adbd is there but does not know our key. Pairing is the fix. */
        NotPaired,

        /** The six digits did not match. */
        WrongPairingCode,

        /** Anything else, with the message worth showing. */
        Failed,
    }

    class Error(val failure: Failure, message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private val key by lazy {
        AdbKey(
            AdbKey.PreferenceStorage(context.getSharedPreferences("adb", Context.MODE_PRIVATE)),
            KEY_NAME,
        )
    }

    private val lock = Mutex()

    val canWriteSecureSettings: Boolean
        get() = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == PackageManager.PERMISSION_GRANTED

    val isWirelessDebuggingEnabled: Boolean
        get() = Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1

    /**
     * Run one command as the shell user.
     *
     * Turns wireless debugging on if it has the permission to, finds the port
     * adbd advertises over mDNS, runs the command, and puts wireless debugging
     * back the way it found it.
     *
     * @param keepEnabled leave wireless debugging on afterwards, even if this
     *   call is what turned it on. Convenient -- a desktop can then reach the
     *   device over ADB without anyone touching it -- at the cost of leaving
     *   the ADB TLS server listening for any host that has already paired.
     */
    suspend fun execute(
        command: String,
        keepEnabled: Boolean = false,
    ): Result<String> = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val enabledByUs = enableWirelessDebugging()
                // 설정 플래그가 서고 나서도 adbd 는 아직 안 듣고 있다.
                if (enabledByUs) delay(ADBD_SETTLE_MILLIS)
                try {
                    val output = connectAndRun(command)
                    keepPairingsAlive()
                    output
                } finally {
                    if (enabledByUs && !keepEnabled) putGlobal(ADB_WIFI_ENABLED, 0)
                }
            }
        }
    }

    /**
     * Find adbd and run the command, rediscovering the port if the connection
     * is refused.
     *
     * A refused connection is normal here rather than exceptional: the port
     * mDNS advertises can be a moment out of date, and on a device where
     * something else also drives wireless debugging -- another app doing this
     * same trick -- the server can be turned off between discovery and connect.
     * Both are worth another look rather than an error.
     */
    private suspend fun connectAndRun(command: String): String {
        var lastFailure: Exception? = null
        var everSawPort = false

        repeat(CONNECT_ATTEMPTS) { attempt ->
            // 무선 디버깅은 여러 앱이 함께 쓰는 자원이다. 같은 수를 쓰는 다른 앱
            // (KeyMapper, lan-mouse-android …) 이 자기 일을 끝내면서 이걸 꺼버리면
            // 포트가 사라지고 우리 시도가 중간에 죽는다. 시작할 때 한 번 켜는 것으로는
            // 부족하고, 매 시도마다 살아 있는지 다시 확인해야 한다.
            if (!isWirelessDebuggingEnabled && canWriteSecureSettings) {
                Log.i(TAG, "wireless debugging went away mid-flight; turning it back on")
                putGlobal(ADB_WIFI_ENABLED, 1)
                delay(ADBD_SETTLE_MILLIS)
            }

            // 포트는 매번 다시 찾는다. 껐다 켜지면 번호가 바뀐다.
            val port = AdbMdns(context, AdbMdns.Service.Connect).discoverPort()

            if (port == null) {
                // 포트가 없다는 것은 아무도 듣고 있지 않다는 뜻이지
                // 우리 키를 모른다는 뜻이 아니다. 이 둘을 섞으면 멀쩡히 페어링된
                // 사용자에게 다시 페어링하라고 하게 된다.
                lastFailure = Error(Failure.NoAdbPort, "no adbd listening on this device")
            } else {
                everSawPort = true
                try {
                    return AdbClient(LOOPBACK, port, key).use {
                        it.connect()
                        it.shell(command)
                    }
                } catch (e: SSLException) {
                    // TLS 까지 갔는데 거절당했다. adbd 가 모르는 키를 만났을 때의 모습이고,
                    // 페어링이 필요하다고 말할 수 있는 유일한 근거다.
                    throw Error(Failure.NotPaired, "adbd does not know this app's key", e)
                } catch (e: IOException) {
                    Log.w(TAG, "attempt ${attempt + 1} failed: ${e.message}")
                    lastFailure = e
                }
            }

            delay(backoffMillis(attempt))
        }

        throw Error(
            if (everSawPort) Failure.Failed else Failure.NoAdbPort,
            lastFailure?.message ?: "could not reach adbd",
            lastFailure,
        )
    }

    /**
     * 재시도 간격을 점점 늘린다.
     *
     * 고정 간격으로 두면 같은 자원을 두고 다투는 다른 앱과 박자가 맞아버려서
     * 서로의 시도를 계속 밟는다. 늘어나는 간격에 약간의 흔들림을 섞으면 풀린다.
     */
    private fun backoffMillis(attempt: Int): Long {
        val base = RETRY_DELAY_MILLIS * (attempt + 1)
        val jitter = (Math.random() * RETRY_JITTER_MILLIS).toLong()
        return (base + jitter).coerceAtMost(RETRY_CAP_MILLIS)
    }

    /**
     * Complete the pairing Android is offering right now.
     *
     * Only works while the "Pair device with pairing code" dialog is open: the
     * pairing port exists for as long as that dialog does and not a moment
     * longer.
     */
    suspend fun pair(code: String): Result<Unit> = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val port = AdbMdns(context, AdbMdns.Service.Pair).discoverPort()
                    ?: throw Error(
                        Failure.NoAdbPort,
                        "no pairing service found; is the pairing dialog still open?",
                    )

                try {
                    AdbPairingClient(LOOPBACK, port, code, key).use { it.pair() }
                } catch (e: AdbPairingCodeException) {
                    throw Error(Failure.WrongPairingCode, "that pairing code was not accepted", e)
                } catch (e: Exception) {
                    throw Error(Failure.Failed, e.message ?: "pairing failed", e)
                }
            }
        }
    }

    /**
     * Turn wireless debugging on or off directly.
     *
     * Android turns it off again on its own -- at a reboot, and when the device
     * leaves the network it was enabled on -- so this cannot make it permanent.
     * The nearest thing to permanent is turning it on at every boot, which is
     * what [execute]'s `keepEnabled` amounts to.
     */
    fun setWirelessDebugging(enabled: Boolean): Boolean {
        if (!canWriteSecureSettings) return false

        if (enabled) {
            putGlobal(DEVELOPMENT_SETTINGS_ENABLED, 1)
            putGlobal(Settings.Global.ADB_ENABLED, 1)
        }
        putGlobal(ADB_WIFI_ENABLED, if (enabled) 1 else 0)
        return true
    }

    /** Open the wireless debugging screen, where the pairing code lives. */
    fun openWirelessDebuggingSettings() {
        val settings = "com.android.settings"

        // The quick-settings detail panel is the screen with the pairing
        // button on it. Some vendors do not have it, hence the fallback.
        val tile = Intent(TileService.ACTION_QS_TILE_PREFERENCES).apply {
            setPackage(settings)
            putExtra(
                Intent.EXTRA_COMPONENT_NAME,
                ComponentName(
                    settings,
                    "com.android.settings.development.qstile.DevelopmentTiles\$WirelessDebugging",
                ),
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching { context.startActivity(tile) }.recoverCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * @return true if this call turned wireless debugging on, meaning it should
     *   be turned off again afterwards.
     */
    private suspend fun enableWirelessDebugging(): Boolean {
        if (isWirelessDebuggingEnabled) return false
        if (!canWriteSecureSettings) {
            throw Error(Failure.NoPermission, "WRITE_SECURE_SETTINGS is not granted")
        }

        // Order matters. Developer options first, then ADB, then the wireless
        // half of it: enabling developer options immediately before starting a
        // shell command has been seen to get the shell killed under it.
        putGlobal(DEVELOPMENT_SETTINGS_ENABLED, 1)
        putGlobal(Settings.Global.ADB_ENABLED, 1)
        putGlobal(ADB_WIFI_ENABLED, 1)

        repeat(WAIT_ROUNDS) {
            if (isWirelessDebuggingEnabled) return true
            delay(WAIT_INTERVAL_MILLIS)
        }
        throw Error(Failure.Failed, "wireless debugging did not come up")
    }

    /**
     * Android expires ADB authorisations after a week by default, which would
     * mean pairing again every time. Zero means never.
     */
    private fun keepPairingsAlive() {
        if (!canWriteSecureSettings) return
        putGlobal(ADB_ALLOWED_CONNECTION_TIME, 0)
    }

    private fun putGlobal(name: String, value: Int) {
        runCatching { Settings.Global.putInt(context.contentResolver, name, value) }
            .onFailure { Log.w(TAG, "cannot write $name", it) }
    }

    private companion object {
        const val TAG = "AdbBootstrap"
        const val LOOPBACK = "127.0.0.1"

        /** How adbd will label our key in its authorized list. */
        const val KEY_NAME = "samsung-power-direct"

        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        const val DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled"
        const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

        const val WAIT_ROUNDS = 25
        const val WAIT_INTERVAL_MILLIS = 200L

        /** Tries at finding and reaching adbd within one bootstrap. */
        // lan-mouse 원본은 3회 × 2초였다. 여기서 늘린 이유:
        // adb_wifi_enabled 가 1이 되어도 adbd 의 TLS 서버가 실제로 listen 하기까지
        // 시간이 더 걸리고, mDNS 가 잠깐 낡은 포트를 광고한다. 실기기에서 첫 시도가
        // ECONNREFUSED 로 죽는 것이 재현됐다.
        const val CONNECT_ATTEMPTS = 6
        const val RETRY_DELAY_MILLIS = 1_000L
        const val RETRY_JITTER_MILLIS = 700L
        const val RETRY_CAP_MILLIS = 6_000L

        /** 무선 디버깅을 방금 켰다면 adbd 가 자리잡을 시간을 준다. */
        const val ADBD_SETTLE_MILLIS = 2_000L
    }
}
