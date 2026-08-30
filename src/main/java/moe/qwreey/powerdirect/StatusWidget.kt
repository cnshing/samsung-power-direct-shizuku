package moe.qwreey.powerdirect

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/**
 * 홈 위젯. 잔량·배터리 전류·모드·에이전트 생존을 보여주고, 탭하면 자동 ↔ 끔.
 *
 * 타일과 정확히 같은 일을 한다 ([SurfaceState.toggle]). 다른 것은 늘 보인다는 점뿐이고,
 * 그래서 여기서는 상태를 더 자세히 적는다 — 그늘을 내리기 전에 지금 충전 중인지
 * 바이패스 중인지 알 수 있는 것이 이 위젯의 존재 이유다.
 */
class StatusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        for (id in widgetIds) manager.updateAppWidget(id, render(context, manager, id))
        // 재부팅하면 알람이 전부 지워진다. 부팅 후에도 이 브로드캐스트는 오므로 여기서
        // 다시 걸어두면 따로 부팅을 듣지 않아도 된다. 같은 PendingIntent 라 멱등하다.
        scheduleTicks(context)
    }

    /** 크기가 바뀌면 몇 줄이 들어가는지가 바뀐다. 다시 그려야 한다. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        options: Bundle,
    ) {
        manager.updateAppWidget(widgetId, render(context, manager, widgetId))
    }

    override fun onEnabled(context: Context) = scheduleTicks(context)

    /** 마지막 위젯이 사라졌다. 알람을 남겨둘 이유가 없다. */
    override fun onDisabled(context: Context) {
        alarmManager(context).cancel(tickIntent(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> {
                SurfaceState.toggle(context, trigger = "widget")
                redraw(context)
                BypassTileService.refresh(context)
                // 여기서 그린 것은 우리가 방금 쓴 설정이지 결과가 아니다. pass_through 는
                // 에이전트가 다음 주기에 쓰고, 그것은 아래의 tick 이 따라잡는다.
            }

            // 알람이 깨웠거나 [refresh] 가 불렀다. 어느 쪽도 대상 목록을 들고 오지 않으므로
            // 지금 놓여 있는 것을 여기서 센다 — 알람을 걸어둔 시점의 목록은 이미 낡았다.
            ACTION_TICK -> redraw(context)

            else -> super.onReceive(context, intent)
        }
    }

    private fun redraw(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, StatusWidget::class.java))
        for (id in ids) manager.updateAppWidget(id, render(context, manager, id))
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int): RemoteViews {
        val state = SurfaceState.read(context)
        val battery = state.battery
        val prefs = Prefs(context)

        val options = manager.getAppWidgetOptions(widgetId)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DETAIL_MIN_WIDTH_DP)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, DETAIL_MIN_HEIGHT_DP)
        // 한 칸짜리에는 글씨가 들어가지 않는다. 그때는 아이콘 색과 잔량만 남기고,
        // 나머지는 지운다 — 잘린 글씨보다 없는 글씨가 낫다.
        val detailed = width >= DETAIL_MIN_WIDTH_DP && height >= DETAIL_MIN_HEIGHT_DP

        val current = battery?.currentMilliAmps
        val currentText = when {
            current == null -> ""
            current > 0 -> "+$current mA"
            else -> "$current mA"
        }

        return RemoteViews(context.packageName, R.layout.widget).apply {
            setTextViewText(R.id.widget_level, battery?.let { "${it.levelPercent}%" } ?: "—")
            setTextViewText(R.id.widget_status, state.label(context))
            setTextViewText(R.id.widget_detail, detail(currentText, state, prefs))
            setViewVisibility(R.id.widget_status, if (detailed) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_detail, if (detailed) View.VISIBLE else View.GONE)
            setInt(R.id.widget_icon, "setColorFilter", tint(state))
            setOnClickPendingIntent(R.id.widget_button, clickIntent(context, state.setUp))
            setContentDescription(R.id.widget_button, context.getString(R.string.widget_description))
        }
    }

    /**
     * 아랫줄. 전류와, 상태 한 단어가 말하지 못한 나머지.
     *
     * **상태 줄을 되풀이하지 않는다.** 자동이면 밴드가 ("지금 어디쯤인가" 는 위젯을 두는
     * 이유의 절반이다), 강제면 모드 이름이 새 정보를 더하지만, 끔은 윗줄이 이미 "Off" 다 —
     * 거기에 "Off" 를 한 번 더 적으면 두 줄이 서로 다른 것을 말한다고 읽힌다.
     */
    private fun detail(current: String, state: SurfaceState, prefs: Prefs): String {
        val qualifier = when (state.mode) {
            Mode.Auto -> "${prefs.low}–${prefs.high}%"
            Mode.Always -> Mode.Always.name
            Mode.Off -> ""
        }
        return listOf(current, qualifier).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** 한 칸짜리에서는 이 색이 상태의 전부다. */
    private fun tint(state: SurfaceState): Int = when (state.status) {
        SurfaceState.Status.Bypassing -> COLOUR_BYPASSING
        SurfaceState.Status.Charging -> COLOUR_CHARGING
        SurfaceState.Status.Unplugged, SurfaceState.Status.Off -> COLOUR_IDLE
        SurfaceState.Status.AgentStopped, SurfaceState.Status.NotSetUp -> COLOUR_BROKEN
    }

    /**
     * 권한이 없으면 토글할 것이 없다 — `pm grant` 를 데스크탑에서 한 번 해야 하고,
     * 그것은 홈 화면에서 조용히 알릴 수 있는 종류의 일이 아니다. 앱이 설명한다.
     */
    private fun clickIntent(context: Context, setUp: Boolean): PendingIntent = if (setUp) {
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, StatusWidget::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    } else {
        MainActivity.pendingIntent(context)
    }

    companion object {
        private const val ACTION_TOGGLE = "moe.qwreey.powerdirect.WIDGET_TOGGLE"
        private const val ACTION_TICK = "moe.qwreey.powerdirect.WIDGET_TICK"

        /** 이보다 작으면 글씨를 버리고 아이콘과 잔량만 남긴다. */
        private const val DETAIL_MIN_WIDTH_DP = 110
        private const val DETAIL_MIN_HEIGHT_DP = 80

        private const val COLOUR_BYPASSING = 0xFF7CB342.toInt()
        private const val COLOUR_CHARGING = 0xFF64B5F6.toInt()
        private const val COLOUR_IDLE = 0xFF9E9E9E.toInt()
        private const val COLOUR_BROKEN = 0xFFE57373.toInt()

        /**
         * 위젯을 다시 그리라고 알린다. 놓인 것이 없으면 아무 일도 하지 않는다.
         *
         * `ACTION_APPWIDGET_UPDATE` 가 아니라 우리 액션을 쓴다. 그쪽으로 보내면 [onUpdate] 를
         * 타면서 알람까지 다시 걸리고, 그러면 자주 부를수록 tick 이 계속 뒤로 밀린다.
         * 알람을 거는 것은 시스템이 진짜로 갱신을 요구할 때(설치·부팅·크기 변경)만 할 일이다.
         */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            if (manager.getAppWidgetIds(ComponentName(context, StatusWidget::class.java)).isEmpty()) {
                return
            }
            context.sendBroadcast(Intent(context, StatusWidget::class.java).setAction(ACTION_TICK))
        }

        /**
         * 위젯이 스스로 깨어나는 유일한 방법.
         *
         * `updatePeriodMillis` 는 하한이 30분이라 전류를 보여주는 데에는 쓸 수 없고,
         * `ACTION_BATTERY_CHANGED` 는 매니페스트로 받을 수 없다(런타임 등록만 가능). 그래서
         * 알람을 쓴다 — 다만 **깨우지 않는 알람**(`ELAPSED_REALTIME`)이다.
         *
         * 그 선택이 핵심이다. 위젯은 화면이 켜져 있을 때만 보이고, 깨우지 않는 알람은 기기가
         * 이미 깨어 있을 때만 발화한다. 두 조건이 정확히 겹치므로 보이는 동안에는 갱신되고
         * 잠든 동안에는 아무 비용도 들지 않는다. `docs/AGENT.md` 가 알람을 걷어낸 것은
         * **정책**을 알람에 맡기지 말라는 뜻이지, 화면 갱신까지 금지한 것은 아니다 —
         * 이 알람이 늦거나 건너뛰어도 잘못될 것은 없다.
         */
        private fun scheduleTicks(context: Context) {
            alarmManager(context).setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + TICK_MILLIS,
                TICK_MILLIS,
                tickIntent(context),
            )
        }

        /** 60초 미만은 시스템이 어차피 60초로 올린다. */
        private const val TICK_MILLIS = 60_000L

        private fun alarmManager(context: Context) =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        private fun tickIntent(context: Context) = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, StatusWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
