# Power Direct

Keep Samsung's **bypass charging** on — even when you aren't gaming.

*[한국어 README](README-kr.md)*

On Galaxy devices, Game Booster can take the battery out of the current path while a
game is in the foreground: the charger feeds the system directly and the battery just
sits there. It is the single best thing you can do for a tablet that lives on a
charging dock. But Samsung ties it to games, and the moment you leave the game it
goes away.

This app keeps that state on, whatever is in the foreground.

Verified end to end on **Galaxy Tab S9+ (SM-X810) / Android 16 / One UI 8**.

---

## Read this first

This is not an official feature, and it is not a magic battery saver. Before you install it:

- **The goal is to extend battery life somewhat, not to stop charging completely.**
  The battery is removed from the current path; it is not electrically disconnected.
  This device measures −16 mA while bypassing, but other devices have been reported
  with 30–70 mA of residual current.
- **The firmware's own conditions still apply and cannot be bypassed.** Bypass needs a
  PD/PPS charger that actually negotiated fast charging, and it only takes effect at
  **20% battery or above**. Below that the flag is ignored and the device charges
  normally — so you can never get stranded on a flat battery.
- **`Settings.System.pass_through` is not a public Samsung interface.** It can be
  removed or changed by any One UI update, without warning.
- **Nobody has measured how much this actually helps.** The mechanism is verified; the
  long-term benefit is not.
- **It needs shell privileges,** which it borrows from your own device's ADB. That means
  wireless debugging gets turned on while it works. See [How it works](#how-it-works).
- Recovery, if anything ever looks wrong, is **reboot**. `pass_through` resets to `0` on
  every boot, so the device heals itself even if you uninstall the app.

---

## What it does

While a charger is plugged in, the app holds the battery inside a band instead of
topping it off and sitting at 100%:

```
level >= high   →  bypass on   (charger feeds the system, battery idles)
level <  low    →  bypass off  (charge normally)
in between      →  keep the current state   (hysteresis, no flapping)
```

Defaults are **low 65 / high 75**. The 10-point band is wide enough that it never
flaps; 65→75 takes roughly 13 minutes of charging.

`high` defaults to 75 rather than 80 on purpose. Samsung's own **Battery protection**
caps at 80 and that cap can't be lowered — if we also acted at 80, Samsung would get
there first, hold at 80 and trickle-charge, and the battery would never leave the
current path. Intervening below that is what makes the real bypass happen.

Unplugged, the app does nothing at all.

### Modes

| Mode | Behaviour |
|---|---|
| **Auto** | The band policy above |
| **Always** | Bypass whenever plugged in, whatever the level |
| **Off** | Never bypass. Charge normally |

### Surfaces

| | |
|---|---|
| **App** | Mode, low/high sliders, live battery level, current, temperature, agent status, activity log |
| **Quick settings tile** | Tap to flip Auto ↔ Off. Long press opens the app |
| **Home screen widget** | Level, current, state and mode. Tap to flip Auto ↔ Off |
| **Notification** | Shown only while charging — status, plus a "turn it off now" action |

The tile's on/off is *what you left switched on*, not *what is happening right now*.
In Auto mode below the band, staying on while charging normally is correct behaviour —
the subtitle (`Bypassing · 80%`) is what tells you the live state.

---

## Setup

Install the APK, then grant one permission from a desktop. This is the one step the app
cannot do for itself:

```sh
adb shell pm grant moe.qwreey.powerdirect android.permission.WRITE_SECURE_SETTINGS
```

Open the app, tap **Pair**, and follow the pairing prompt once. After that it is
self-sufficient — including across reboots, with no user interaction.

Turning on Samsung's **Battery protection** (Settings → Battery, 80% cap) alongside this
is a good idea. The two live at different layers and don't conflict.

Requires **Android 11+** (wireless debugging didn't exist before that), arm64.

---

## How it works

`Settings.System.pass_through` (`0`/`1`) is the switch. Set it to `1` and the bypass
engages — verified at 0 mA battery current under a saturated 8-core load. No game
launcher registration, no foreground condition.

Writing it requires **shell UID**. `WRITE_SETTINGS` is not enough (AOSP blocks writes to
private `Settings.System` keys — it isn't a permission problem), and Shizuku was ruled
out because its "start on boot" is root-only, which means it would be dead at exactly
the moment recovery is needed after a reboot.

So the app borrows shell from its own device:

```
app  →(own ADB client, over loopback, once per boot)→  adbd  →  shell agent
                                                                   ↓
                                             Settings.System.pass_through
                                                                   ↓
                                    BatteryService  →  sysfs pass_through
```

ADB is used **once per boot**, only to launch a small shell-UID agent. The agent reads
`dumpsys battery` itself and applies the policy from then on. Because of that the app
doesn't need to wake up in the background: no alarms, no foreground service, no asking
the user to disable battery optimisation.

The ADB implementation (pairing, mDNS discovery, TLS) is ported from
[lan-mouse-android](https://github.com/qwreey/lan-mouse-android).

---

## Documentation

Written in Korean.

| | |
|---|---|
| [HANDOFF.md](HANDOFF.md) | Current state, dev environment, debugging commands |
| [docs/AGENT.md](docs/AGENT.md) | Agent architecture and five traps worth knowing before touching it |
| [docs/SPEC.md](docs/SPEC.md) | Established facts, architecture decisions and their reasoning |
| [docs/research/](docs/research/) | 12 research notes — every fact above traced to a measurement |

## Building

```sh
./gradlew assembleDebug
adb install -r build/outputs/apk/debug/samsung-power-direct-debug.apk
```

## Licence

**GPL-3.0** — not by choice but by descent. `adb/` and `pairing.cpp` come from
lan-mouse-android, which took them from [Key Mapper](https://github.com/keymapperorg/KeyMapper),
which is rooted in [Shizuku](https://github.com/RikkaApps/Shizuku). See [NOTICE.md](NOTICE.md).

Not affiliated with Samsung, Key Mapper, Shizuku or lan-mouse.
