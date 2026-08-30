package moe.qwreey.powerdirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput

/**
 * 정책이 깨어나는 모든 경로.
 *
 * 여기서는 아무 일도 하지 않는다 — 판단하고 [PolicyJobService] 에 넘긴다.
 * 리시버 안에서 ADB 를 태우면 `goAsync()` 예산(10초쯤)을 넘길 수 있다.
 */
class PolicyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        Log.i(TAG, "received $action")

        when (action) {
            // 충전기가 없으면 이 플래그는 아무 의미가 없다. 알람을 끄고 조용히 있는다.
            // 충전기가 빠져도 에이전트는 계속 산다 — ADB 와 무관하게 도는 것이 요점이다.
            // 우리가 켠 무선 디버깅만 되돌리고, 감시 알람은 그대로 둔다.
            Intent.ACTION_POWER_DISCONNECTED -> {
                PolicyRunner.clearNotification(app)
                PolicyRunner.endSession(app)
                PolicyRunner.schedule(app)
                SurfaceState.refreshAll(app)
                RunLog.append(app, "[unplugged] wireless debugging restored")
                return
            }

            // 알림의 "Charge normally". 모드만 바꾸고 에이전트가 다음 주기에 반영하게 둔다.
            ACTION_STOP -> {
                val prefs = Prefs(app)
                prefs.mode = Mode.Off
                prefs.bumpGeneration()
                Agent.writeConfig(app, prefs)
            }

            ACTION_PAIR -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(PairingNotification.KEY_CODE)
                    ?.toString()
                    ?.filter(Char::isDigit)
                if (code.isNullOrEmpty()) return
                PolicyJobService.schedulePairing(app, code)
                return
            }
        }

        PolicyJobService.schedule(app, trigger = action.substringAfterLast('.').lowercase())
    }

    companion object {
        private const val TAG = "PolicyReceiver"

        /** 알람이 스스로를 깨우는 액션. */
        const val ACTION_CHECK = "moe.qwreey.powerdirect.CHECK"

        /** 알림의 "Charge normally" 버튼. */
        const val ACTION_STOP = "moe.qwreey.powerdirect.STOP"

        /** 페어링 알림의 직접 답장. */
        const val ACTION_PAIR = "moe.qwreey.powerdirect.PAIR"
    }
}
