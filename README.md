# OneUI Fast Home

An Xposed module (legacy API 82; works with LSPosed / Vector) for **One UI Home** that removes most of the dead time after the swipe-home gesture when your default home app is a **third-party launcher**.

## The problem

On Samsung phones with gesture navigation, One UI Home stays the recents component even when another launcher is the default home. When you swipe home from an app, One UI Home runs the animation (`FallbackSwipeHandler`). Until it calls `RecentsAnimationController.finish()`, the system keeps a full-screen `recents_animation_input_consumer` window above everything. Your launcher is already drawn and visible, but taps never reach it, so the first tap after going home is often lost.

`finish()` waits for two things:

- the window-to-home spring (`RectFSpringAnim`), whose parameters are hard-coded in `GestureViTunnerImpl`: damping 0.75/0.85 and stiffness 150;
- the page settle of the invisible fallback recents view (`STATE_RECENTS_SCROLLING_FINISHED`).

Neither depends on the system animation scale settings, so setting them to 0 does not help.

Measured on a Galaxy S20 FE, from finger-up until touches reach the launcher:

| | Dead time |
|---|---|
| Stock | 0.80–0.94 s |
| With this module | 0.23–0.24 s |

## What it hooks

Every hook applies only when the handler is `com.android.quickstep.FallbackSwipeHandler` **and** the gesture end target is `HOME`. Recents, quick switch, new task, PiP and gestures with Samsung's own home (`LauncherSwipeHandlerV2`) are left alone.

1. **Stiffer home spring.** While `AbsSwipeUpHandler.animateToProgressInternal` starts the home animation, `GestureViTunnerImpl.getDampingNStiffnessScaleAnimation()` and `getDampingNStiffnessBounceAnimation()` return `(1.0, 2500)`. That spring is critically damped with no overshoot and settles in about 0.2 s.
2. **No wait for the invisible recents scroll.** After `AbsSwipeUpHandler.handleNormalGestureEnd` picks `HOME`, the module sets `GestureState.STATE_RECENTS_SCROLLING_FINISHED` right away. Normally it waits for `PagedView.onPageEndTransition`, which takes about 0.77 s.

The module fails safe. If a class, method, field or type does not match, that hook is not installed and a line starting with `OneUiFastHome:` goes to the framework log. Every callback is wrapped in try/catch and falls back to stock behaviour.

## Compatibility

Tested only on:

- **Phone:** Galaxy S20 FE (SM-G780G);
- **System:** Android 12 / One UI 4.1;
- **App:** One UI Home 13.1.09.7;
- **Root and framework:** Magisk 27 with Zygisk and Vector 2.2.

Other One UI versions may work if the class and method names match. Check the install line in the log.

## Build

```
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

A prebuilt APK is attached to the GitHub release.

## Install

1. Install the APK.
2. Enable the module in LSPosed or Vector, with scope **One UI Home** (`com.sec.android.app.launcher`).
3. Restart One UI Home, or reboot:

```
adb shell su -c 'kill $(pidof com.sec.android.app.launcher)'
```

With the Vector CLI:

```
adb shell su -c '/data/adb/modules/zygisk_vector/cli modules enable dev.vm.oneuifasthome'
adb shell su -c '/data/adb/modules/zygisk_vector/cli scope set dev.vm.oneuifasthome com.sec.android.app.launcher/0'
adb shell su -c 'kill $(pidof com.sec.android.app.launcher)'
adb logcat | grep OneUiFastHome   # expect: installed: spring=true ... skipRecentsScroll=true
```

## Turn off

- **Instantly, no restart:** `adb shell setprop debug.oneuifasthome.off 1`. Set it to `0` to turn it back on. The value resets on reboot.
- **Disable:** turn the module off in the manager and restart One UI Home.
- **Remove:** `adb uninstall dev.vm.oneuifasthome`.

## License

MIT
