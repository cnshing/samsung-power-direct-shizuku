package moe.qwreey.powerdirect

import android.content.Context
import moe.qwreey.powerdirect.adb.AdbBootstrap

/**
 * 퀵세팅 타일과 홈 위젯이 함께 쓰는 상태 한 덩어리.
 *
 * 둘이 같은 것을 보여주고 같은 일을 하므로 판단을 한 곳에 모은다. 나눠 두면
 * "타일은 바이패스 중이라는데 위젯은 충전 중" 같은 것이 생기고, 그때 어느 쪽이 맞는지
 * 알아낼 방법이 없다.
 *
 * **읽기 전용이고 표시 전용이다.** [Agent.isAlive] 를 쓰는 것도 그래서다 — 심장박동 나이는
 * 화면에 뿌리기에는 충분하지만 되살릴지 말지를 정하기에는 관대한 쪽으로 틀린다.
 * 그 판단은 [Agent.ensureRunning] 안에만 있어야 한다 (`docs/AGENT.md` 함정 3·4).
 */
data class SurfaceState(
    /** shell 권한이 있는가. 없으면 이 앱은 아무것도 하지 못한다. */
    val setUp: Boolean,
    val mode: Mode,
    /** 심장박동 나이로 본 에이전트 생존. **표시 전용.** */
    val agentAlive: Boolean,
    /** 읽기는 앱 권한으로 된다. 쓰기만 shell 이 필요하다. */
    val passThrough: Int?,
    val battery: BatteryState?,
) {
    /** 사용자가 켜 둔 상태인가 — 타일의 on/off 가 따라가는 값. */
    val enabled: Boolean get() = mode != Mode.Off

    enum class Status {
        /** `pm grant` 를 아직 하지 않았다. */
        NotSetUp,

        /** 모드가 끔. */
        Off,

        /** 켜 두었는데 정책을 수행할 프로세스가 없다. */
        AgentStopped,

        /** 충전기가 없다. 이 플래그는 그때 아무 의미가 없다. */
        Unplugged,

        /** 배터리가 전류 경로에서 빠져 있다. */
        Bypassing,

        /** 정상 충전 중. */
        Charging,
    }

    val status: Status
        get() = when {
            !setUp -> Status.NotSetUp
            !enabled -> Status.Off
            !agentAlive -> Status.AgentStopped
            battery?.plugged != true -> Status.Unplugged
            // 전류로 짐작하지 않는다. 충전은 잔량이 오를수록 테이퍼링돼서 정상 충전 중에도
            // 수백 mA 까지 떨어지고, 그러면 바이패스와 구분되지 않는다 (Battery.summary 참조).
            passThrough == 1 -> Status.Bypassing
            else -> Status.Charging
        }

    fun label(context: Context): String = context.getString(
        when (status) {
            Status.NotSetUp -> R.string.status_not_set_up
            Status.Off -> R.string.status_off
            Status.AgentStopped -> R.string.status_agent_stopped
            Status.Unplugged -> R.string.status_unplugged
            Status.Bypassing -> R.string.status_bypassing
            Status.Charging -> R.string.status_charging
        },
    )

    companion object {
        fun read(context: Context): SurfaceState {
            val prefs = Prefs(context)
            return SurfaceState(
                setUp = AdbBootstrap(context).canWriteSecureSettings,
                mode = prefs.mode,
                agentAlive = Agent.isAlive(context),
                passThrough = PassThrough.read(context),
                // 배터리 읽기는 sticky 브로드캐스트 조회라 실패할 이유가 없지만,
                // 표시 하나 때문에 타일 서비스가 죽는 것은 곤란하다.
                battery = runCatching { Battery.read(context) }.getOrNull(),
            )
        }

        /**
         * 자동 ↔ 끔. 타일 탭과 위젯 탭이 둘 다 이것을 부른다.
         *
         * 하는 일은 [MainViewModel.setMode] 와 정확히 같다 — 설정을 적고 세대를 올리고
         * 에이전트에게 넘긴다. **에이전트를 직접 건드리지 않는다.** 값을 쓸 권한이 우리에게
         * 없기도 하고, 권위가 한 곳에 있어야 하기도 하다.
         *
         * 그러고 나서 잡을 하나 던진다. 에이전트가 살아 있으면 [PolicyRunner] 는 설정을 다시
         * 넘기고 끝나지만, 죽어 있으면 여기가 되살아나는 유일한 길이다. **끄는 쪽에도
         * 필요하다** — 에이전트가 죽은 채로 `pass_through` 가 1 로 남아 있으면 그것을 0 으로
         * 돌려놓을 수 있는 것도 에이전트뿐이다. 알림의 "Charge normally" 도 같은 이유로
         * 같은 일을 한다 ([PolicyReceiver]).
         *
         * @return 바뀐 뒤의 모드
         */
        fun toggle(context: Context, trigger: String): Mode {
            val prefs = Prefs(context)
            val next = if (prefs.mode == Mode.Off) Mode.Auto else Mode.Off
            prefs.mode = next
            prefs.bumpGeneration()
            Agent.writeConfig(context, prefs)
            PolicyJobService.schedule(context, trigger = trigger)
            RunLog.append(context, "[$trigger] mode → ${next.name}")
            return next
        }

        /** 켜져 있는 표면 전부를 다시 그린다. 상태가 바뀐 쪽에서 부른다. */
        fun refreshAll(context: Context) {
            BypassTileService.refresh(context)
            StatusWidget.refresh(context)
        }
    }
}
