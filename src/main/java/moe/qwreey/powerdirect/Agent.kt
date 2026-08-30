package moe.qwreey.powerdirect

import android.content.Context
import android.util.Log
import java.io.File
import moe.qwreey.powerdirect.adb.AdbBootstrap

/**
 * shell UID 로 도는 작은 감시 프로세스. 정책을 실제로 수행하는 주체다.
 *
 * **왜 앱이 아니라 여기인가.** `Settings.System.pass_through` 는 앱 UID 로 쓸 수 없고
 * shell 이어야 한다. 값을 바꿀 때마다 ADB 로 붙으면 매번 무선 디버깅을 켜고, mDNS 로
 * 포트를 찾고, TLS 로 접속해야 한다 — 그 하나하나가 실패 지점이고, 무선 디버깅은 같은 수를
 * 쓰는 다른 앱들(KeyMapper, lan-mouse-android …)과 다투는 자원이다. 그중 하나가 자기 일을
 * 끝내며 그걸 꺼버리면 포트가 바뀌고 우리 연결이 죽는다.
 *
 * 그래서 ADB 는 **부팅 후 한 번만** 쓴다. 이 에이전트를 띄우는 데에만. 그 뒤로는 ADB 가
 * 어떻게 되든 상관없다 — lan-mouse-android 가 데몬을 띄우고 손을 떼는 것과 같은 원칙이다.
 * `pass_through` 는 어차피 재부팅마다 0 으로 초기화되므로 부팅 후 한 번의 ADB 는
 * 피할 수 없는 비용이고, 에이전트는 그 위에 공짜로 얹힌다.
 *
 * **왜 정책까지 에이전트가 하는가.** shell 은 `dumpsys battery` 를 읽을 수 있다. 그러면
 * 잔량 판단까지 여기서 끝나고, **앱은 백그라운드에서 돌 필요가 아예 없어진다.**
 * 알람도, 포그라운드 서비스도, App Standby 와의 씨름도, 사용자에게 배터리 최적화를
 * 꺼달라고 부탁하는 일도 없다. 앱은 설정을 적고 상태를 보여주는 창구일 뿐이다.
 *
 * **통신.** 앱의 external files 디렉터리 — 앱 UID 와 shell UID 가 함께 닿는 유일한 장소다.
 *
 * ```
 *   agent.sh     앱이 쓰고 shell 이 실행한다
 *   agent.conf   앱이 쓴다 — 모드 / low / high, 한 줄씩
 *   agent.beat   에이전트가 쓴다 — 심장박동 겸 마지막 관측
 *   agent.stop   앱이 만든다 — 에이전트가 보고 스스로 종료한다
 *   agent.pid    에이전트가 쓴다 — 재실행 시 이전 인스턴스 정리용
 * ```
 *
 * 스크립트는 띄우기 직전에 앱이 항상 새로 쓴다. 앱을 업데이트해도 옛 사본이 남아 도는 일이
 * 없다 — lan-mouse 가 바이너리 sha256 을 비교해야 했던 문제가 여기서는 생기지 않는다.
 */
object Agent {
    /**
     * 충전 중 폴링 주기(초). 스크립트와 반드시 같아야 한다.
     *
     * 짧게 잡는다. 충전 중에는 벽전원을 쓰고 있으니 아낄 이유가 없고, 주기가 짧을수록
     * 죽은 에이전트를 살아 있다고 잘못 볼 수 있는 창이 좁아진다. 한 주기의 비용은
     * `dumpsys battery` 한 번과 `settings get` 한 번이다.
     */
    const val POLL_SECONDS = 10

    /** 충전기가 없을 때의 주기. 이때는 할 일이 없으니 느긋하게 돈다. */
    const val IDLE_POLL_SECONDS = 120

    // 죽은 것으로 보기까지의 시간. **주기에 맞춰 나눈다.**
    //
    // 하나로 두면 안 된다. 느린 주기(충전기 없음, 120초)에 맞춰 넉넉히 잡으면
    // 충전 중에 죽은 에이전트를 3분 넘게 "살아있음" 으로 본다. 그러면 화면이 거짓말을 할 뿐
    // 아니라 ensureRunning 이 "이미 돌고 있다" 며 되살리지 않는다 — 실제로 그렇게 됐다.
    // beat 에 충전 여부가 들어 있으니 그것으로 고른다.
    private const val STALE_PLUGGED_MILLIS = (POLL_SECONDS * 3L + 5) * 1000
    private const val STALE_IDLE_MILLIS = (IDLE_POLL_SECONDS * 5L / 2) * 1000

    /** 에이전트가 마지막 주기에 관측하고 결정한 것. */
    data class Beat(
        val ageSeconds: Long,
        val level: Int?,
        val plugged: Boolean,
        val want: String,
        val passThrough: String,
        /** 삼성 배터리 보호가 켜져 있는가. 알 수 없으면 null. */
        val protectOn: Boolean? = null,
        /** 그 임계값. 이 기기에서는 80 고정이다. */
        val protectThreshold: Int? = null,
        /** 우리가 마지막으로 던진 토큰의 메아리. [verifyAlive] 가 쓴다. */
        val ping: String? = null,
    ) {
        val alive: Boolean
            get() = ageSeconds * 1000 <
                if (plugged) STALE_PLUGGED_MILLIS else STALE_IDLE_MILLIS
    }

    private fun dir(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir
    private fun file(context: Context, name: String) = File(dir(context), name)

    fun beat(context: Context): Beat? {
        // 읽기가 실패할 수 있다 — 파일이 shell 소유로 만들어졌으면 앱은 EACCES 를 받는다.
        // 상태 표시 하나 때문에 앱이 죽어서는 안 된다.
        val raw = runCatching { file(context, "agent.beat").readText() }.getOrNull()?.trim()
        if (raw.isNullOrEmpty()) return null
        val parts = raw.split(" ")
        val seconds = parts.getOrNull(0)?.toLongOrNull() ?: return null
        return Beat(
            ageSeconds = (System.currentTimeMillis() / 1000) - seconds,
            level = parts.getOrNull(1)?.toIntOrNull(),
            plugged = parts.getOrNull(2) == "1",
            want = parts.getOrNull(3) ?: "?",
            passThrough = parts.getOrNull(4) ?: "?",
            protectOn = parts.getOrNull(5)?.let { if (it == "?") null else it == "1" },
            protectThreshold = parts.getOrNull(6)?.toIntOrNull(),
            ping = parts.getOrNull(7),
        )
    }

    fun isAlive(context: Context): Boolean = beat(context)?.alive == true

    /**
     * 지금 이 순간 살아 있는지 **확인한다.**
     *
     * 심장박동의 나이만 보면 "최근에 살아 있었다" 까지밖에 말할 수 없다. 그 사이에 죽었으면
     * 한 주기 동안 살아 있는 것으로 보이고, 그 판정을 믿은 [ensureRunning] 이 되살리기를
     * 건너뛴다. 실제로 그렇게 됐다.
     *
     * 앱이 다른 UID 의 프로세스를 직접 확인할 방법은 없다 — 안드로이드는 `/proc` 을
     * `hidepid` 로 마운트해서 `ps` 든 `/proc/<pid>` 든 우리 자신만 보인다. 그래서
     * 토큰을 던지고 메아리를 기다린다. 대답하면 확실히 살아 있는 것이다.
     */
    suspend fun verifyAlive(context: Context, timeoutMillis: Long = 15_000): Boolean {
        // 나이만으로도 이미 죽었으면 기다릴 이유가 없다.
        if (!isAlive(context)) return false

        val token = System.currentTimeMillis().toString(36)
        runCatching { file(context, "agent.ping").writeText(token) }
            .onFailure { return isAlive(context) }

        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (beat(context)?.ping == token) return true
            kotlinx.coroutines.delay(500)
        }
        Log.i(TAG, "agent did not answer the ping")
        return false
    }

    /** 정책을 에이전트에게 넘긴다. 다음 주기에 반영된다. */
    fun writeConfig(context: Context, prefs: Prefs) {
        dir(context).mkdirs()
        val text = "${prefs.mode.name}\n${prefs.low}\n${prefs.high}\n${prefs.generation}\n"
        val f = file(context, "agent.conf")
        runCatching { f.writeText(text) }.recoverCatching {
            // 이 파일이 어쩌다 shell 소유가 되면 앱은 쓰지 못한다 —
            // `adb push` 는 덮어쓰기가 아니라 교체라 소유권이 넘어간다.
            // 지우고 다시 만들면 우리 것으로 돌아온다.
            f.delete()
            f.writeText(text)
        }.onFailure { Log.w(TAG, "cannot write agent.conf: ${it.message}") }
    }

    /**
     * 에이전트를 멈춘다. 다음 주기에 스스로 나간다.
     *
     * 심장박동을 **여기서 즉시** 지운다. 에이전트가 나가면서 비우기를 기다리면 최대 한 주기
     * 동안 죽은 에이전트가 살아 있는 것으로 보이고, 그 사이에 누른 "Start" 가
     * "이미 돌고 있다" 며 무시된다.
     */
    fun stop(context: Context) {
        runCatching { file(context, "agent.stop").writeText("1") }
        runCatching { file(context, "agent.beat").writeText("") }
    }

    /**
     * 살아 있지 않으면 ADB 로 띄운다. 살아 있으면 아무것도 하지 않는다 —
     * 이 경로가 ADB 를 쓰는 유일한 곳이고, 되도록 지나가지 않는 것이 목적이다.
     */
    suspend fun ensureRunning(context: Context, bootstrap: AdbBootstrap): Result<String> {
        val d = dir(context)
        d.mkdirs()

        // 살아 있다고 판단하든 아니든 정지 플래그부터 걷는다. 남겨두면 지금 돌고 있는
        // 에이전트가 다음 주기에 그걸 보고 나가버린다 — "Start" 를 눌렀는데 죽는 셈이다.
        runCatching { File(d, "agent.stop").delete() }

        if (verifyAlive(context)) return Result.success("already running")

        File(d, "agent.sh").writeText(script())

        // 에이전트가 쓸 파일을 앱이 먼저 만들어 둔다.
        //
        // 여기가 이 채널의 유일한 규칙이다. shell 이 **만든** 파일은 shell 소유가 되고
        // 앱은 그것을 읽지 못한다(EACCES). 반대로 앱이 만들어 둔 파일에 shell 이 덮어쓰면
        // 소유자는 앱으로 유지된다. 그래서 에이전트는 만들지 않고 덮어쓰기만 한다.
        listOf("agent.beat", "agent.pid", "agent.ping").forEach { name ->
            runCatching {
                val f = File(d, name)
                f.delete()
                f.writeText("")
            }
        }

        Log.i(TAG, "launching agent")
        if (!bootstrap.isWirelessDebuggingEnabled) Prefs(context).weEnabledAdb = true
        val sh = File(d, "agent.sh").absolutePath
        // 이전 인스턴스를 먼저 정리한다. 앱을 재설치하면 옛 사본이 남아 돌 수 있다.
        val result = bootstrap.execute("sh $sh", keepEnabled = true)
        if (result.isFailure) return result

        // 명령이 돌아왔다고 에이전트가 사는 것은 아니다. 스크립트가 pid 파일을 확인하고서야
        // 반환하지만, 그래도 여기서 한 번 더 본다 — 성공 보고와 실제 상태가 어긋나면
        // 감시 알람이 20분 뒤에나 알아채게 된다.
        repeat(10) {
            if (isAlive(context)) return Result.success(result.getOrDefault("started"))
            kotlinx.coroutines.delay(500)
        }
        return Result.failure(
            IllegalStateException("agent did not report in: ${result.getOrNull()?.trim()}"),
        )
    }

    /**
     * 에이전트 본체.
     *
     * 배터리를 sysfs 로 읽지 않는다 — One UI 8 에서는 shell 에게도 막혀 있다.
     * `dumpsys battery` 는 읽을 수 있고, 잔량과 충전 여부는 그것으로 충분하다.
     * 전류처럼 정밀한 값은 앱이 `BatteryManager` 로 훨씬 잘 읽으므로 여기서 다루지 않는다.
     * 하는 일이 적을수록 죽을 일도 적다.
     */
    private fun script(): String = """
        #!/system/bin/sh
        # samsung-power-direct agent — 앱이 생성한다. 직접 고치지 말 것.

        D="DOLLAR(dirname "DOLLAR0")"
        PID="DOLLARD/agent.pid"

        # 감시 루프. 실제로 일하는 쪽.
        run() {
            # 우리를 낳은 세션이 끊기는 것은 우리 일이 아니다.
            trap '' HUP
            echo DOLLARDOLLAR > "DOLLARPID"
            lastgen=""

            while true; do
                if [ -f "DOLLARD/agent.stop" ]; then
                    # 지우지 않는다 — 다시 만들면 shell 소유가 되어 앱이 못 읽는다.
                    : > "DOLLARPID"
                    : > "DOLLARD/agent.beat"
                    exit 0
                fi

                mode=DOLLAR(sed -n 1p "DOLLARD/agent.conf" 2>/dev/null)
                low=DOLLAR(sed -n 2p "DOLLARD/agent.conf" 2>/dev/null)
                high=DOLLAR(sed -n 3p "DOLLARD/agent.conf" 2>/dev/null)
                gen=DOLLAR(sed -n 4p "DOLLARD/agent.conf" 2>/dev/null)
                ping=DOLLAR(cat "DOLLARD/agent.ping" 2>/dev/null)

                # 사용자가 설정을 손댔거나 우리가 막 시작했다면, 이번 주기는
                # 히스테리시스를 건너뛰고 새 규칙으로 다시 정한다.
                fresh=0
                if [ "DOLLARgen" != "DOLLARlastgen" ]; then
                    fresh=1
                    lastgen="DOLLARgen"
                fi

                b=DOLLAR(dumpsys battery 2>/dev/null)
                lvl=DOLLAR(echo "DOLLARb" | grep -i "^  level:" | sed "s/.*: *//")
                plug=0
                echo "DOLLARb" | grep -qiE "(AC|USB|Wireless|Dock) powered: true" && plug=1

                # 삼성 배터리 보호. 우리 high 가 이 임계값 이상이면 두 기능이 겹친다.
                pmode=DOLLAR(echo "DOLLARb" | grep -i "mProtectBatteryMode" | sed "s/.*: *//")
                pthr=DOLLAR(echo "DOLLARb" | grep -i "^ *mProtectionThreshold" | sed "s/.*: *//")

                want=""
                if [ "DOLLARplug" = 1 ] && [ -n "DOLLARlvl" ] && [ -n "DOLLARlow" ]; then
                    case "DOLLARmode" in
                        Off) want=0 ;;
                        Always) want=1 ;;
                        *)
                            if [ "DOLLARlvl" -lt "DOLLARlow" ]; then
                                want=0
                            elif [ "DOLLARlvl" -ge "DOLLARhigh" ]; then
                                want=1
                            fi
                            ;;
                    esac
                fi

                cur=DOLLAR(settings get system pass_through 2>/dev/null)
                if [ -n "DOLLARwant" ] && [ "DOLLARwant" != "DOLLARcur" ]; then
                    settings put system pass_through "DOLLARwant"
                    cur="DOLLARwant"
                fi

                echo "DOLLAR(date +%s) DOLLARlvl DOLLARplug DOLLAR{want:-keep} DOLLARcur DOLLAR{pmode:-?} DOLLAR{pthr:-?} DOLLAR{ping:--}" > "DOLLARD/agent.beat"

                # 충전기가 없으면 할 일이 없다. 느긋하게 돌아 dumpsys 호출을 아낀다.
                if [ "DOLLARplug" = 1 ]; then sleep POLL; else sleep IDLEPOLL; fi
            done
        }

        # 기동. ADB 로 실행되는 쪽.
        start() {
            # 이전 인스턴스 정리. pid 파일이 비어 있을 수도 있으므로
            # (앱이 방금 새로 만들었을 수 있다) 이름으로도 한 번 훑는다.
            # 여기는 run 이 아니라 start 프로세스라 자기 자신을 죽일 걱정이 없다.
            p=DOLLAR(cat "DOLLARPID" 2>/dev/null)
            [ -n "DOLLARp" ] && kill "DOLLARp" 2>/dev/null
            for q in DOLLAR(pgrep -f "agent.sh run" 2>/dev/null); do
                kill "DOLLARq" 2>/dev/null
            done
            : > "DOLLARPID"
            rm -f "DOLLARD/agent.stop"

            # setsid 로 세션에서 떼어낸다. nohup 만으로는 기기에 따라 부족하다.
            if command -v setsid > /dev/null 2>&1; then
                setsid sh "DOLLAR0" run < /dev/null > /dev/null 2>&1 &
            else
                nohup sh "DOLLAR0" run < /dev/null > /dev/null 2>&1 &
            fi

            # 살아난 것을 확인하기 전에는 돌아가지 않는다.
            #
            # 이 스크립트를 실어 나른 ADB 세션은 우리가 반환하는 즉시 닫히고,
            # 세션이 닫히면 아직 그 프로세스 그룹 안에 남아 있는 것이 함께 죽는다.
            # fork 는 됐지만 분리를 끝내지 못한 자식이 그 경주에서 진다.
            # 여기서 잠깐 기다리면 그 경주 자체가 없어진다.
            i=0
            while [ ! -s "DOLLARPID" ] && [ "DOLLARi" -lt 50 ]; do
                sleep 0.1
                i=DOLLAR((i + 1))
            done

            if [ ! -s "DOLLARPID" ]; then
                echo "agent did not come up"
                exit 1
            fi
            echo "agent started pid DOLLAR(cat "DOLLARPID")"
        }

        case "DOLLAR1" in
            run)  run ;;
            stop) p=DOLLAR(cat "DOLLARPID" 2>/dev/null)
                  [ -n "DOLLARp" ] && kill "DOLLARp" 2>/dev/null
                  : > "DOLLARPID"
                  : > "DOLLARD/agent.beat"
                  echo stopped ;;
            *)    start ;;
        esac
    """.trimIndent().replace("DOLLAR", "$")
        .replace("IDLEPOLL", IDLE_POLL_SECONDS.toString())
        .replace("POLL", POLL_SECONDS.toString())

    private const val TAG = "Agent"
}
