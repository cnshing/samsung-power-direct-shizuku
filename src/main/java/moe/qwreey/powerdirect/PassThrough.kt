package moe.qwreey.powerdirect

import android.content.Context
import android.provider.Settings
import moe.qwreey.powerdirect.adb.AdbBootstrap

/**
 * 삼성 우회충전 스위치.
 *
 * `Settings.System.pass_through` 다. 게임 부스터의 "게임 중 USB PD 충전 일시정지" 토글이
 * 뒤집는 바로 그 값이고, `BatteryService` 가 이걸 감시하다가
 * `/sys/class/power_supply/battery/pass_through` 로 그대로 미러링한다.
 *
 * **읽기는 앱 권한으로 되지만 쓰기는 안 된다.** AOSP `SettingsProvider` 가 공개 목록에 없는
 * `Settings.System` 키의 앱 쓰기를 막는다 — `WRITE_SETTINGS` 를 줘도 소용없다. 권한 문제가
 * 아니라서 우회할 수 없고, 그래서 shell UID 로 명령을 실행해야 한다. `docs/SPEC.md` 참조.
 */
object PassThrough {
    const val KEY = "pass_through"

    /** 현재 값. 키가 없으면 null (이 펌웨어에 기능이 없다는 뜻). */
    fun read(context: Context): Int? =
        try {
            Settings.System.getInt(context.contentResolver, KEY)
        } catch (e: Settings.SettingNotFoundException) {
            null
        }

    /**
     * shell UID 로 값을 쓴다. 앱 UID 로는 불가능하다.
     *
     * @param keepAdbEnabled 무선 디버깅을 끄지 않고 둔다. 기본은 끄는 쪽 —
     *   여러 앱이 함께 쓰는 값이라 우리가 켰으면 돌려놓는 것이 옳다. 그래서 한 번 적용에
     *   5초 넘게 걸릴 수 있고, 그 때문에 이 일은 브로드캐스트 리시버가 아니라
     *   [PolicyJobService] 에서 돈다.
     */
    suspend fun write(
        bootstrap: AdbBootstrap,
        value: Int,
        keepAdbEnabled: Boolean = false,
    ): Result<String> =
        bootstrap.execute("settings put system $KEY $value", keepEnabled = keepAdbEnabled)
}
