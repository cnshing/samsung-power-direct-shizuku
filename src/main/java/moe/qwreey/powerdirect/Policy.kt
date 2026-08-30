package moe.qwreey.powerdirect

import android.content.Context
import android.content.SharedPreferences

enum class Mode {
    /** Hold the battery inside [Prefs.low]..[Prefs.high] while plugged in. */
    Auto,

    /** Bypass whenever plugged in, whatever the level. */
    Always,

    /** Never bypass. Charge normally. */
    Off,
}

/**
 * 사용자 설정. SharedPreferences 로 충분하다 — 값이 셋뿐이고 쓰기가 드물다.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("policy", Context.MODE_PRIVATE)

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", null) ?: "") }.getOrDefault(Mode.Auto)
        set(v) = sp.edit().putString("mode", v.name).apply()

    /** 이 아래로 떨어지면 다시 충전한다. */
    var low: Int
        get() = sp.getInt("low", 65)
        set(v) = sp.edit().putInt("low", v.coerceIn(FLOOR, 95)).apply()

    /**
     * 여기 도달하면 바이패스로 전환한다.
     *
     * 기본값이 75 인 것은 삼성 **배터리 보호** 때문이다. 그쪽 임계값은 80 이고
     * `mMaximumProtectionThreshold` 도 80 이라 사용자가 낮출 수 없다. 우리가 80 에서
     * 걸면 두 기능이 정확히 겹치고, 삼성 쪽이 먼저 작동해 80 에 걸어둔 채 트리클 충전을 한다.
     * 그러면 우리가 하려는 일 — 배터리를 아예 전류 경로에서 빼는 것 — 이 일어나지 않는다.
     * 그 아래에서 먼저 개입해야 진짜 바이패스가 걸린다.
     */
    var high: Int
        get() = sp.getInt("high", 75)
        set(v) = sp.edit().putInt("high", v.coerceIn(FLOOR + 5, 100)).apply()

    /**
     * 충전기를 뽑은 뒤에도 무선 디버깅을 켜둔다. 기본값 꺼짐.
     *
     * **끄는 행위가 곧 남의 연결을 끊는 행위다.** 무선 디버깅을 이런 식으로 쓰는 앱이
     * 여럿이고(KeyMapper, lan-mouse-android, Shizuku …), 그중 하나가 자기 일을 끝내며
     * 이걸 끄면 포트가 사라져서 같은 순간에 붙어 있던 다른 앱의 연결이 죽는다.
     * lan-mouse-android 가 KeyMapper 와 레이싱이 나서 증분 대기를 넣은 이유가 이것이다.
     *
     * 그래서 값을 쓸 때마다 껐다 켜지 않는다. **충전 세션 동안 켜둔 채로 두고,
     * 충전기를 뽑을 때 우리가 켰던 경우에만 되돌린다.** 토글 횟수가 하루 몇 번으로 줄어
     * 남과 부딪힐 창이 그만큼 좁아진다.
     *
     * 이 옵션을 켜면 뽑은 뒤에도 그대로 둔다 — 더 협조적이지만, 이미 페어링된 호스트가
     * 언제든 접속할 수 있는 상태가 유지된다.
     */
    var keepAdbEnabled: Boolean
        get() = sp.getBoolean("keepAdb", false)
        set(v) = sp.edit().putBoolean("keepAdb", v).apply()

    /**
     * 설정 세대. 사용자가 모드나 밴드를 손댈 때마다 올라간다.
     *
     * 히스테리시스는 "마지막으로 넘은 경계" 를 기억하는 것인데, 사용자가 밴드를 옮기면
     * 그 경계 자체가 사라진다. 그런데도 옛 상태가 눌러앉아 있으면 — 예컨대 75% 에서
     * 바이패스 중에 상한을 90 으로 올렸는데 여전히 바이패스면 — 사용자에게는
     * 설정이 먹지 않은 것으로 보인다. 세대가 바뀐 주기에는 에이전트가 히스테리시스를
     * 건너뛰고 새 규칙으로 다시 정한다.
     */
    var generation: Int
        get() = sp.getInt("generation", 0)
        private set(v) = sp.edit().putInt("generation", v).apply()

    /** 사용자가 설정을 바꿨다. */
    fun bumpGeneration() {
        generation = generation + 1
    }

    /**
     * 이번 세션의 무선 디버깅을 우리가 켰는가. 사용자가 이미 켜둔 것을 꺼버리면 안 된다.
     */
    var weEnabledAdb: Boolean
        get() = sp.getBoolean("weEnabledAdb", false)
        set(v) = sp.edit().putBoolean("weEnabledAdb", v).apply()

    companion object {
        /**
         * 20% 미만에서는 펌웨어가 pass_through 를 무시하고 정상 충전한다(실측).
         * 그 아래를 밴드 하한으로 두는 것은 의미가 없다.
         */
        const val FLOOR = 25
    }
}

/**
 * 지금 pass_through 가 무엇이어야 하는가 — **화면에 보여주기 위한 예측일 뿐이다.**
 *
 * 실제 판단과 적용은 [Agent] 의 셸 스크립트가 한다. 같은 규칙이 두 언어로 존재하는 셈이라
 * 달라질 위험이 있으므로, 규칙을 바꿀 때는 반드시 양쪽을 함께 고친다.
 * 권위는 언제나 에이전트 쪽에 있다.
 *
 * @return 원하는 값, 또는 null = 손대지 않는다.
 *
 * null 을 돌려주는 경우가 두 가지다. 충전기가 없으면 이 플래그는 아무 의미가 없으니
 * 건드릴 이유가 없고, 밴드 안쪽(low..high)이면 히스테리시스가 현재 상태를 유지해야 한다.
 * 밴드 안에서 값을 강제하면 임계값 근처에서 계속 뒤집힌다.
 */
fun decide(mode: Mode, low: Int, high: Int, level: Int, plugged: Boolean): Int? {
    if (!plugged) return null
    return when (mode) {
        Mode.Off -> 0
        Mode.Always -> 1
        Mode.Auto -> when {
            level < low -> 0
            level >= high -> 1
            else -> null
        }
    }
}
