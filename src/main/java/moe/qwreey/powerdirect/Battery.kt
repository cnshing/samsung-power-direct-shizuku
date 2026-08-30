package moe.qwreey.powerdirect

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 배터리 관측.
 *
 * 전류를 앱에서 읽을 수 있다는 게 중요하다. `adb shell` 로는
 * `/sys/class/power_supply/battery/current_now` 가 One UI 8 에서 막혀 있는데,
 * `BatteryManager` 는 권한 없이 같은 값을 준다.
 */
data class BatteryState(
    val levelPercent: Int,
    /**
     * **배터리** 전류(mA). 양수 = 배터리로 들어옴, 음수 = 배터리에서 나감.
     *
     * 이것은 시스템 소비 전력이 아니다. 배터리를 드나드는 양만 잰다.
     * 바이패스가 걸리면 충전기가 시스템을 직접 먹이므로 이 값은 0 근처가 되고,
     * 그때 시스템이 실제로 얼마를 쓰는지는 **여기서 보이지 않는다.**
     * 시스템 소모를 알려면 충전기를 뽑아야 한다 — 그때의 방전 전류가 곧 시스템 소모다.
     */
    val currentMilliAmps: Int,
    val charging: Boolean,
    val plugged: Boolean,
    val temperatureCelsius: Float,
    val voltageMilliVolts: Int,
) {
    /**
     * 지금 무슨 일이 일어나고 있는지 한 줄로.
     *
     * **`pass_through` 를 반드시 같이 본다.** 전류만으로 판단하면 안 된다 —
     * 충전은 잔량이 오를수록 테이퍼링돼서 정상 충전 중에도 전류가 수백 mA 까지 떨어지고,
     * 그러면 바이패스가 걸린 것과 구분되지 않는다. 실제로 71% 부근에서 700 mA 로 충전 중일 때
     * "바이패스 중" 으로 잘못 표시된 것을 관측했다. 값을 읽을 수 있는데 추측할 이유가 없다.
     */
    fun summary(passThrough: Int?): String {
        val magnitude = kotlin.math.abs(currentMilliAmps)
        return when {
            !plugged && currentMilliAmps < 0 -> "Discharging — this is what the system draws"
            !plugged -> "Idle"

            passThrough == 1 && magnitude < SETTLED_MILLIAMPS ->
                "Bypassing — the charger is feeding the system directly"

            passThrough == 1 && levelPercent < FIRMWARE_FLOOR_PERCENT ->
                "Bypass is set, but below $FIRMWARE_FLOOR_PERCENT% the firmware ignores it"

            passThrough == 1 ->
                "Bypass is set but the battery is still charging — it can take a few minutes to settle"

            currentMilliAmps < 0 ->
                "Plugged in but draining — the charger cannot keep up with the load"

            else -> "Charging"
        }
    }

    private companion object {
        /** 이 아래면 배터리가 사실상 관여하지 않는 것으로 본다. 실측 잔류는 ±150 mA 였다. */
        const val SETTLED_MILLIAMPS = 300

        /** 이 아래에서는 pass_through 가 무시되고 정상 충전된다(실측). */
        const val FIRMWARE_FLOOR_PERCENT = 20
    }
}

object Battery {
    fun read(context: Context): BatteryState {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val sticky: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val plug = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        return BatteryState(
            levelPercent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            // AOSP 문서는 마이크로암페어라고 하지만 이 기기(Tab S9+ / One UI 8)는
            // 밀리암페어를 준다. dumpsys battery 의 `current now` 와 대조해 확인했다:
            // 충전 중 앱 4956/4821/4924 vs dumpsys 4796/4834.
            currentMilliAmps = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING,
            plugged = plug != 0,
            temperatureCelsius = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f,
            voltageMilliVolts = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
        )
    }
}
