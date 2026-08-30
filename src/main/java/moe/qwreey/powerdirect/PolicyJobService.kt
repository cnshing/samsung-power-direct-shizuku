package moe.qwreey.powerdirect

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 정책을 실제로 돌리는 곳.
 *
 * 브로드캐스트 리시버가 직접 하지 않는 이유가 둘이다.
 *
 * 하나, 포그라운드 서비스를 쓸 수 없다. Android 12+ 는 백그라운드에서의 FGS 시작을 막고
 * `ACTION_POWER_CONNECTED` 는 면제 목록에 없다.
 *
 * 둘, `goAsync()` 의 예산은 10초쯤인데 한 번 적용에 그만큼 걸릴 수 있다 — 무선 디버깅을
 * 켜고, adbd 가 자리잡기를 기다리고, mDNS 로 포트를 찾고, 붙고, 끄고. 그 시간을 아끼겠다고
 * 무선 디버깅을 켠 채로 두는 것은 여러 앱이 함께 쓰는 값을 독차지하는 셈이라 옳지 않다.
 *
 * 잡은 그런 제약이 없다. 여유 있게 일하고 끝나면 알린다.
 */
class PolicyJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val code = params?.extras?.getString(EXTRA_PAIRING_CODE)
        val trigger = params?.extras?.getString(EXTRA_TRIGGER) ?: "job"
        work = scope.launch {
            try {
                if (code != null) PolicyRunner.pair(applicationContext, code)
                else PolicyRunner.run(applicationContext, trigger)
            } catch (e: Throwable) {
                Log.e(TAG, "policy run failed", e)
            } finally {
                jobFinished(params, false)
            }
        }
        return true // 아직 일이 남았다
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        work?.cancel()
        return true // 다시 불러달라
    }

    companion object {
        private const val TAG = "PolicyJobService"
        private const val JOB_ID = 1001
        private const val PAIRING_JOB_ID = 1002
        private const val EXTRA_PAIRING_CODE = "pairing_code"
        private const val EXTRA_TRIGGER = "trigger"

        /** 지금 한 번 돌린다. */
        fun schedule(context: Context, trigger: String = "job") {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, PolicyJobService::class.java))
                .setOverrideDeadline(0) // 최대한 빨리
                .setExtras(android.os.PersistableBundle().apply { putString(EXTRA_TRIGGER, trigger) })
                .build()
            scheduler.schedule(info)
        }

        /** 알림에서 받은 코드로 페어링한 뒤, 이어서 정책을 한 번 돌린다. */
        fun schedulePairing(context: Context, code: String) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val extras = android.os.PersistableBundle().apply {
                putString(EXTRA_PAIRING_CODE, code)
            }
            val info = JobInfo.Builder(PAIRING_JOB_ID, ComponentName(context, PolicyJobService::class.java))
                .setOverrideDeadline(0)
                .setExtras(extras)
                .build()
            scheduler.schedule(info)
        }
    }
}
