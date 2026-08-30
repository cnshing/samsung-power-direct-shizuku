package moe.qwreey.powerdirect

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 최근 실행 기록. 앱이 안 떠 있을 때 일어난 일을 사용자가 볼 수 있어야 한다.
 *
 * 정책은 대부분 백그라운드에서 돈다 — 알람, 충전기 연결, 부팅. 그런데 그 결과가
 * ViewModel 메모리에만 있으면 앱을 여는 순간 전부 사라져 있고, 사용자에게는
 * "뭔가 되고는 있는 것 같은데 확인할 방법이 없는" 앱이 된다. logcat 을 붙이라고 할 수는 없다.
 *
 * SharedPreferences 에 한 줄씩 넣는다. 개수가 적고 쓰기가 드물어 이걸로 충분하다.
 */
object RunLog {
    private const val PREFS = "runlog"
    private const val KEY = "lines"
    private const val LIMIT = 40

    /** 유닛 구분자. 로그 본문에 나타날 일이 없다. */
    private const val SEPARATOR = "\u001F"

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun append(context: Context, line: String) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kept = (read(context) + "${stamp.format(Date())}  $line").takeLast(LIMIT)
        sp.edit().putString(KEY, kept.joinToString(SEPARATOR)).apply()
    }

    fun read(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "")
        return if (raw.isNullOrEmpty()) emptyList() else raw.split(SEPARATOR)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
