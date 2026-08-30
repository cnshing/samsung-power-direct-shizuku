package moe.qwreey.powerdirect

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 퀵세팅 타일. 탭하면 자동 ↔ 끔.
 *
 * **타일의 on/off 는 사용자가 켜 둔 것을 뜻하고, 부제는 지금 실제로 일어나는 일을 뜻한다.**
 * 둘을 하나로 합칠 수는 없다. 자동 모드에서 잔량이 밴드 아래면 켜 둔 채로 정상 충전하는 것이
 * 정상인데, 그때 타일을 꺼진 것으로 그리면 한 번 더 눌러 진짜로 끄게 된다. 안드로이드의
 * 절전 모드 타일도 같은 이유로 같은 선택을 한다.
 *
 * 롱탭은 매니페스트의 `QS_TILE_PREFERENCES` 필터를 타고 앱으로 간다.
 */
class BypassTileService : TileService() {
    private var scope: CoroutineScope? = null

    /**
     * 그늘이 열려 있는 동안만 다시 읽는다.
     *
     * 전류는 초 단위로 움직이는 값이라 한 번 그리고 마는 것으로는 부족하다. 그늘이 닫히면
     * 안드로이드가 [onStopListening] 을 불러주므로 이 루프는 화면에 보이는 동안만 돈다 —
     * 백그라운드 폴링이 아니다.
     */
    override fun onStartListening() {
        super.onStartListening()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { this.scope = it }
        scope.launch {
            while (true) {
                render()
                delay(REFRESH_MILLIS)
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        val state = SurfaceState.read(this)

        // 권한이 없으면 토글할 것이 없다. `pm grant` 를 데스크탑에서 한 번 해야 하는데,
        // 그것은 잠금화면 뒤에서 조용히 알릴 수 있는 종류의 일이 아니다. 앱이 설명한다.
        if (!state.setUp) {
            openApp()
            return
        }

        SurfaceState.toggle(this, trigger = "tile")

        // 즉시 한 번 그린다. 다만 여기서 보이는 것은 우리가 방금 쓴 설정이지 결과가 아니다 —
        // pass_through 는 에이전트가 다음 주기(최대 10초)에 쓴다. 그 결과는 위의 루프가
        // 따라잡고, 잡이 끝나면 refresh() 가 한 번 더 깨운다.
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val state = SurfaceState.read(this)

        tile.state = when {
            !state.setUp -> Tile.STATE_UNAVAILABLE
            state.enabled -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.label = getString(R.string.app_name)
        tile.contentDescription = getString(R.string.tile_description)

        // 부제는 짧아야 한다 — 타일 폭을 넘으면 잘린다. 상태 한 단어 + 잔량이 한계다.
        // 설정 자체가 안 된 상태에서는 잔량이 아무것도 설명해 주지 않으므로 그때만 뺀다.
        val level = state.battery?.levelPercent
        tile.subtitle = when {
            state.status == SurfaceState.Status.NotSetUp || level == null -> state.label(this)
            else -> "${state.label(this)} · $level%"
        }

        tile.updateTile()
    }

    /**
     * `startActivityAndCollapse(Intent)` 는 API 34 에서 막혔고 `PendingIntent` 를 받는 쪽으로
     * 바뀌었다. 이 앱의 하한은 30 이라 양쪽이 다 필요하다.
     */
    @Suppress("DEPRECATION")
    private fun openApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(MainActivity.pendingIntent(this))
        } else {
            startActivityAndCollapse(MainActivity.intent(this))
        }
    }

    companion object {
        /** 그늘이 열려 있는 동안의 갱신 주기. */
        private const val REFRESH_MILLIS = 2_000L

        /**
         * 타일이 낡았다고 알린다. 안드로이드가 [onStartListening] 을 한 번 불러준다.
         *
         * 타일이 놓여 있지 않거나 그늘이 닫혀 있으면 아무 일도 일어나지 않는다 — 그래서
         * 호출하는 쪽이 조건을 따질 필요가 없다.
         */
        fun refresh(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, BypassTileService::class.java),
                )
            }
        }
    }
}

