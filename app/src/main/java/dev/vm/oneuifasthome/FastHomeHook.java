package dev.vm.oneuifasthome;

import android.os.Looper;
import android.util.Log;
import android.util.Pair;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * One UI Home 13.1.09.7 (Android 12): speeds up the swipe-to-HOME gesture when the default home
 * is a third-party launcher (gesture handled by {@code FallbackSwipeHandler}).
 *
 * <p>Two hooks, both gated on "handler is FallbackSwipeHandler AND end target == HOME":
 * <ol>
 *   <li>Spring: while {@code AbsSwipeUpHandler.animateToProgressInternal} runs (it synchronously
 *       calls {@code RectFSpringAnim.start}, which reads {@code GestureViTunnerImpl} for the scale
 *       spring and constructs the X/Y {@code FlingSpringAnim}s that read the bounce spring), the
 *       tuner returns (damping {@link #DAMPING}, stiffness {@link #STIFFNESS}) instead of
 *       (0.75, 150) / (0.85, 150). Not applied for swipe-to-PiP.</li>
 *   <li>Scroll wait: after {@code AbsSwipeUpHandler.handleNormalGestureEnd} picked HOME, set
 *       {@code GestureState.STATE_RECENTS_SCROLLING_FINISHED} right away instead of waiting for
 *       {@code PagedView.onPageEndTransition} of the (invisible) FallbackRecentsView.</li>
 * </ol>
 * Any mismatch (class/method/field missing, wrong types) disables the affected hook; every
 * callback is wrapped in try/catch and falls back to stock behaviour.
 *
 * <p>Runtime kill switch without restarting the launcher: {@code setprop debug.oneuifasthome.off 1}
 * (read per gesture; resets on reboot).
 */
public class FastHomeHook implements IXposedHookLoadPackage {

    private static final String TAG = "OneUiFastHome";
    private static final String TARGET_PKG = "com.sec.android.app.launcher";

    private static final String CLS_ABS = "com.android.quickstep.AbsSwipeUpHandler";
    private static final String CLS_FALLBACK = "com.android.quickstep.FallbackSwipeHandler";
    private static final String CLS_GESTURE_STATE = "com.android.quickstep.GestureState";
    private static final String CLS_TUNER = "com.android.homescreen.gesture.GestureViTunnerImpl";

    /** Critically damped: no overshoot. With k=2500 the scale spring settles in ~0.2 s. */
    static final float DAMPING = 1.0f;
    static final float STIFFNESS = 2500f;

    private static final String KILL_PROP = "debug.oneuifasthome.off";

    /**
     * The FallbackSwipeHandler currently inside animateToProgressInternal with end target HOME.
     * Touched only on the main thread.
     */
    private static Object sHomeAnimHandler;

    private static Class<?> sFallbackCls;
    private static int sScrollFinishedFlag;
    private static Method sSysPropGet;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;
        if (!lpparam.isFirstApplication) return;
        try {
            install(lpparam.classLoader);
        } catch (Throwable t) {
            log("install failed, module inactive: " + t);
        }
    }

    private static void install(ClassLoader cl) {
        Class<?> abs = XposedHelpers.findClassIfExists(CLS_ABS, cl);
        sFallbackCls = XposedHelpers.findClassIfExists(CLS_FALLBACK, cl);
        Class<?> gs = XposedHelpers.findClassIfExists(CLS_GESTURE_STATE, cl);
        Class<?> tuner = XposedHelpers.findClassIfExists(CLS_TUNER, cl);
        if (abs == null || sFallbackCls == null || gs == null || !abs.isAssignableFrom(sFallbackCls)) {
            log("swipe handler classes not found/mismatch (abs=" + abs + ", fallback=" + sFallbackCls
                    + ", gestureState=" + gs + "), module inactive");
            return;
        }
        // mGestureState lives in SwipeUpAnimationLogic (superclass of AbsSwipeUpHandler).
        if (findFieldUp(abs, "mGestureState", gs) == null
                || findPublic(gs, "getEndTarget") == null) {
            log("mGestureState/getEndTarget not found, module inactive");
            return;
        }
        try {
            sSysPropGet = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class);
        } catch (Throwable t) {
            sSysPropGet = null; // kill switch unavailable, hooks stay on
        }

        boolean springOk = tuner != null && installSpringHooks(abs, tuner);
        boolean scrollOk = installScrollHook(abs, gs);
        log("installed: spring=" + springOk + " (d=" + DAMPING + ", k=" + STIFFNESS + ")"
                + ", skipRecentsScroll=" + scrollOk);
    }

    // ---------------------------------------------------------------- (a) spring

    private static boolean installSpringHooks(Class<?> abs, Class<?> tuner) {
        try {
            Method anim = findUniqueDeclared(abs, "animateToProgressInternal", 6);
            Method scale = findDeclared(tuner, "getDampingNStiffnessScaleAnimation");
            Method bounce = findDeclared(tuner, "getDampingNStiffnessBounceAnimation");
            if (anim == null || scale == null || bounce == null
                    || scale.getReturnType() != Pair.class || bounce.getReturnType() != Pair.class
                    || findFieldUp(abs, "mIsSwipingPipToHome", boolean.class) == null) {
                log("spring hook: signature mismatch (anim=" + anim + ", scale=" + scale
                        + ", bounce=" + bounce + "), skipped");
                return false;
            }

            XposedBridge.hookMethod(anim, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sHomeAnimHandler = null;
                    try {
                        if (isFallbackHome(param.thisObject)) sHomeAnimHandler = param.thisObject;
                    } catch (Throwable t) {
                        log("anim before: " + t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sHomeAnimHandler = null; // runs even if the original threw
                }
            });

            XC_MethodHook override = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object h = sHomeAnimHandler;
                        if (h == null || param.getThrowable() != null) return;
                        if (Looper.myLooper() != Looper.getMainLooper()) return;
                        if (XposedHelpers.getBooleanField(h, "mIsSwipingPipToHome")) return;
                        param.setResult(Pair.create(DAMPING, STIFFNESS));
                    } catch (Throwable t) {
                        log("tuner override: " + t);
                    }
                }
            };
            XposedBridge.hookMethod(scale, override);
            XposedBridge.hookMethod(bounce, override);
            return true;
        } catch (Throwable t) {
            log("spring hook failed: " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------- (b) recents scroll

    private static boolean installScrollHook(Class<?> abs, Class<?> gs) {
        try {
            Method end = findUniqueDeclared(abs, "handleNormalGestureEnd", 4);
            Field flag = gs.getDeclaredField("STATE_RECENTS_SCROLLING_FINISHED");
            Method setState = gs.getMethod("setState", int.class);
            Method hasState = gs.getMethod("hasState", int.class);
            if (end == null || flag.getType() != int.class
                    || !Modifier.isStatic(flag.getModifiers()) || setState == null || hasState == null) {
                log("scroll hook: signature mismatch (end=" + end + "), skipped");
                return false;
            }
            flag.setAccessible(true);
            sScrollFinishedFlag = flag.getInt(null);
            if (sScrollFinishedFlag == 0) {
                log("scroll hook: STATE_RECENTS_SCROLLING_FINISHED == 0, skipped");
                return false;
            }

            XposedBridge.hookMethod(end, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.getThrowable() != null) return;
                        if (!isFallbackHome(param.thisObject)) return;
                        Object state = XposedHelpers.getObjectField(param.thisObject, "mGestureState");
                        boolean has = (Boolean) XposedHelpers.callMethod(state, "hasState", sScrollFinishedFlag);
                        if (has) return;
                        Log.i(TAG, "HOME: marking recents scroll finished early");
                        XposedHelpers.callMethod(state, "setState", sScrollFinishedFlag);
                    } catch (Throwable t) {
                        log("scroll hook: " + t);
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            log("scroll hook failed: " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------- helpers

    /** FallbackSwipeHandler (third-party home) with end target HOME, and kill switch off. */
    private static boolean isFallbackHome(Object handler) {
        if (handler == null || !sFallbackCls.isInstance(handler)) return false;
        Object state = XposedHelpers.getObjectField(handler, "mGestureState");
        if (state == null) return false;
        Object target = XposedHelpers.callMethod(state, "getEndTarget");
        if (!(target instanceof Enum) || !"HOME".equals(((Enum<?>) target).name())) return false;
        return !killSwitchOn();
    }

    private static boolean killSwitchOn() {
        Method get = sSysPropGet;
        if (get == null) return false;
        try {
            return "1".equals(get.invoke(null, KILL_PROP, "0"));
        } catch (Throwable t) {
            return false;
        }
    }

    private static Method findUniqueDeclared(Class<?> cls, String name, int paramCount) {
        Method found = null;
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterTypes().length != paramCount || found != null) return null;
            found = m;
        }
        return found;
    }

    private static Method findDeclared(Class<?> cls, String name) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == 0) return m;
        }
        return null;
    }

    private static Method findPublic(Class<?> cls, String name) {
        try {
            return cls.getMethod(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findFieldUp(Class<?> cls, String name, Class<?> type) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                return type.isAssignableFrom(f.getType()) ? f : null;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        return null;
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }
}
