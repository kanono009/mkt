# AccuTap

**AccuTap** is a lightweight native Kotlin Android app that performs the same
workload as a floating quiz clicker: a draggable overlay TAP button, one
immediate accessibility tap at its center, and one delayed accessibility tap
at the button's live position after a configured delay.

It is an independent rewrite with a corrected timing architecture.

---

## What Was Wrong With The Original Design (v1)

| # | v1 flaw | AccuTap fix |
|---|---|---|
| 1 | Delayed deadline recomputed inside the timing runnable (`nanoTime + delay`), ignoring the base captured at START. Every run inherited variable `post()` latency. | Single monotonic base (uptime + nano) captured once at START; deadline derived only from that base. |
| 2 | Delayed tap dispatched through `mainHandler.post {}`, adding main-looper queue jitter at the exact deadline. | `dispatchGesture()` is thread-safe; the urgent timing thread fires the gesture directly at the deadline. |
| 3 | Button hidden at the deadline, putting a WindowManager relayout IPC inside the critical path. | Hide scheduled with `postAtTime(target - 100 ms)`; relayout completes before the critical window. Restore at +250 ms. |
| 4 | `removeCallbacksAndMessages(token)` used, but posts were made without the token, so CANCEL could not remove pending hide/restore work. | All countdown posts use the token; cancel truly removes them. |
| 5 | Read `floatingButton.width` (view state) with a coerce fallback; racy before layout. | Fixed-size button; live center cached in volatile floats updated on every drag move. No view reads off main. |
| 6 | Final spin used `Thread.yield()`, surrendering the timeslice inside the last 3 ms. | `postAtTime` wake at target-6 ms, 1 ms sleeps until target-3 ms, then a pure nanoTime spin with no yield on an URGENT_DISPLAY thread. |
| 7 | Keyboard hide IPC could land after the base capture and delay the immediate tap. | Keyboard hidden and panel passivated before the base is captured. |
| 8 | Untokened 150 ms restore could collide with a quick restart. | Tokened restore. |
| 9 | No calibration hook for the constant input-pipeline offset (~+3 ms measured on-device). | `CALIBRATION_MS` constant (default 0; set to 3 to zero the pipeline offset). |

---

## Requirements

| Item | Value |
|---|---:|
| Language | Kotlin |
| UI style | Native Android views |
| Minimum SDK | 26 |
| Target SDK | 34 |
| Compile SDK | 34 |
| Tap mechanism | `AccessibilityService.dispatchGesture(...)` |
| Overlay mechanism | `TYPE_APPLICATION_OVERLAY` |
| Delay range | 0.000 to 31.000 seconds |
| Permissions | Overlay, Accessibility, Notifications (13+) |

---

## Runtime Workflow

1. Grant overlay permission and enable the accessibility tap service.
2. Start the overlay.
3. Drag TAP over the first target.
4. Enter a delay, e.g. `20.000`.
5. Press START COUNTDOWN.
6. Immediate tap fires at the live center.
7. Drag TAP to the second target.
8. At the deadline, the delayed tap fires at the live center.

---

## Timing Model

At START:

```text
baseUptime = SystemClock.uptimeMillis()
baseNano   = System.nanoTime()
targetNano = baseNano + (delay - CALIBRATION_MS) * 1e6
```

The timing thread wakes at `target - 6 ms`, sleeps in 1 ms slices until
`target - 3 ms`, then spins on `System.nanoTime()` until the deadline and
dispatches the gesture directly.

The button window is hidden at `target - 100 ms` and restored at
`target + 250 ms`, both tokened main-thread posts.

---

## Building

Local:

```bash
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```

Codemagic: push the repository and run the `android-debug` workflow; the
script generates the Gradle 8.7 wrapper automatically if missing.

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## Safety Notes

AccuTap only performs taps initiated by the user pressing START COUNTDOWN.
It does not read other apps' content, scrape data, or bypass protections.
Some apps block injected gestures or overlays for anti-fraud reasons; this
app does not attempt to bypass such protections. Use it only where allowed.
