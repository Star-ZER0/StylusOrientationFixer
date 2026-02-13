//Copyright 2026 星澪Star_ZER0. All rights reserved.
/*
  草泥马小米你冯亖了从MIUI14就有的Bug给你反馈了也不修到HyperOS3都几把还有
  尼玛的都是吃白饭的是吧有Bug不修是等着过年吗
  天天更新就写个对联合个补丁，还不让解锁BL谁几把还买你的东西，滚吧乐色玩意
 */
package cc.star0.stylusorientationfixer;

import android.view.InputEvent;
import android.view.MotionEvent;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;


public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "[StylusOrientationFixer]";
    private static final boolean DEBUG = BuildConfig.DEBUG;

    /** 预计算的数学常量，避免热路径中反复计算 */
    private static final float HALF_PI = (float) (Math.PI / 2.0);
    private static final float PI = (float) Math.PI;
    private static final float TWO_PI = (float) (2.0 * Math.PI);

    /** 重入深度计数器：> 0 时表示当前线程正在处理事件，跳过嵌套调用。 */
    private static final ThreadLocal<Integer> sDispatchDepth = ThreadLocal.withInitial(() -> 0);

    /**
     * 将 orientation 逆时针旋转 90°（加 π/2），归一化到 [-π, π]。
     */
    private static float rotateOrientation(float orientation) {
        float result = orientation + HALF_PI;
        if (result > PI) {
            result -= TWO_PI;
        } else if (result < -PI) {
            result += TWO_PI;
        }
        return result;
    }

    /**
     * 检测指定 pointer 是否为触控笔输入。
     */
    private static boolean isStylusPointer(MotionEvent event) {
        return (event.getPointerCount() == 1 && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS);
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + " Module loaded in: " + lpparam.packageName);
        try {
            final Class<?> receiverClass = XposedHelpers.findClass("android.view.InputEventReceiver", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(receiverClass, "dispatchInputEvent",
                    int.class, InputEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.args[1] instanceof InputEvent)) {
                                if (DEBUG) XposedBridge.log(TAG + " [before] args[1] is not InputEvent, skipping");
                                return;
                            } else if (!(param.args[1] instanceof MotionEvent)) {
                                if (DEBUG) XposedBridge.log(TAG + " [before] InputEvent is not MotionEvent (KeyEvent?), skipping");
                                return;
                            }

                            int depth = sDispatchDepth.get();
                            sDispatchDepth.set(depth + 1);
                            if (depth > 0) {
                                if (DEBUG) XposedBridge.log(TAG + " [before] Reentrant call detected (depth=" + (depth + 1) + "), skipping patch");
                                return;
                            }

                            try {
                                patchMotionEvent(param);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " patchMotionEvent failed: " + t.getMessage());
                                if (DEBUG) XposedBridge.log(TAG + " Stack trace: " + android.util.Log.getStackTraceString(t));
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // 仅对 MotionEvent 递减计数器，与 before 中递增逻辑匹配
                            if (param.args[1] instanceof MotionEvent) {
                                int depth = sDispatchDepth.get();
                                if (depth > 0) {
                                    sDispatchDepth.set(depth - 1);
                                }
                                if (DEBUG) XposedBridge.log(TAG + " [after] depth: " + depth + " -> " + (depth > 0 ? depth - 1 : 0));
                            }
                        }
                    });

            XposedBridge.log(TAG + " Source hook (InputEventReceiver) registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Source hook FAILED: " + t.getMessage());
        }
    }

    /**
     * 从原始 MotionEvent 中提取所有 pointer 数据，旋转触控笔的 orientation，
     * 用 MotionEvent.obtain() 重建新事件并替换 param.args[1]。
     * <p>
     * 注意：不可 recycle 原始事件，其生命周期由系统 native 层管理。
     */
    private void patchMotionEvent(XC_MethodHook.MethodHookParam param) {
        MotionEvent event = (MotionEvent) param.args[1];

        if (!isStylusPointer(event)) {
            if (DEBUG) {
                XposedBridge.log(TAG + " [patch] Not a stylus event (toolType="
                        + event.getToolType(0) + ", action=" + event.getAction()
                        + ", pointerCount=" + event.getPointerCount() + "), skipping");
            }
            return;
        }

        if (DEBUG) {
            XposedBridge.log(TAG + " [patch] Stylus event detected: action=" + event.getAction()
                    + " source=0x" + Integer.toHexString(event.getSource())
                    + " deviceId=" + event.getDeviceId()
                    + " pointerCount=" + event.getPointerCount()
                    + " historySize=" + event.getHistorySize());
        }

        // 提取 pointer 0 的属性（笔输入只有一个 pointer）
        MotionEvent.PointerProperties[] props = { new MotionEvent.PointerProperties() };
        event.getPointerProperties(0, props[0]);

        int historySize = event.getHistorySize();

        // ---- 确定 obtain() 使用的初始样本 ----
        // addBatch() 语义：当前样本 → 历史末尾，新数据 → 当前样本
        // 因此必须按时间顺序喂入：history[0] → history[1] → … → current
        MotionEvent.PointerCoords[] initialCoords = { new MotionEvent.PointerCoords() };
        long initialTime;

        if (historySize > 0) {
            // 以第一个历史样本作为起始
            event.getHistoricalPointerCoords(0, 0, initialCoords[0]);
            initialTime = event.getHistoricalEventTime(0);
        } else {
            // 无历史，直接用当前样本
            event.getPointerCoords(0, initialCoords[0]);
            initialTime = event.getEventTime();
        }

        float origOrientation = initialCoords[0].orientation;
        float newOrientation = rotateOrientation(origOrientation);
        initialCoords[0].orientation = newOrientation;

        if (DEBUG) {
            XposedBridge.log(TAG + " [patch] initial orientation: " + origOrientation
                    + " -> " + newOrientation
                    + " | tilt=" + initialCoords[0].getAxisValue(MotionEvent.AXIS_TILT)
                    + " | pressure=" + initialCoords[0].pressure
                    + " | x=" + initialCoords[0].x + " y=" + initialCoords[0].y);
        }

        // 用初始样本创建事件
        MotionEvent patched = MotionEvent.obtain(
                event.getDownTime(),
                initialTime,
                event.getAction(),
                1,
                props,
                initialCoords,
                event.getMetaState(),
                event.getButtonState(),
                event.getXPrecision(),
                event.getYPrecision(),
                event.getDeviceId(),
                event.getEdgeFlags(),
                event.getSource(),
                event.getFlags());

        // ---- 按正确顺序追加剩余样本 ----
        if (historySize > 0) {
            if (DEBUG) XposedBridge.log(TAG + " [patch] Processing " + historySize + " historical batches");
            MotionEvent.PointerCoords[] batchCoords = { new MotionEvent.PointerCoords() };

            // 追加 history[1] … history[N-1]
            for (int i = 1; i < historySize; i++) {
                event.getHistoricalPointerCoords(0, i, batchCoords[0]);
                batchCoords[0].orientation = rotateOrientation(batchCoords[0].orientation);
                patched.addBatch(event.getHistoricalEventTime(i), batchCoords, event.getMetaState());
            }

            // 最后追加真正的「当前」样本
            MotionEvent.PointerCoords[] currentCoords = { new MotionEvent.PointerCoords() };
            event.getPointerCoords(0, currentCoords[0]);
            currentCoords[0].orientation = rotateOrientation(currentCoords[0].orientation);
            patched.addBatch(event.getEventTime(), currentCoords, event.getMetaState());
        }

        // 替换事件；不要 recycle 原始事件，其生命周期由 native InputDispatcher 管理
        param.args[1] = patched;
        if (DEBUG) XposedBridge.log(TAG + " [patch] Event replaced successfully");
    }
}
