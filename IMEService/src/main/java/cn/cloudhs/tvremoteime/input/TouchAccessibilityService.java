package cn.cloudhs.tvremoteime.input;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍触摸注入服务：
 * 反射 InputManager.injectInputEvent 需要系统级 INJECT_EVENTS 权限，普通应用必然被拒；
 * ADB 方案又要求电视开启网络调试。而 AccessibilityService.dispatchGesture 是
 * Android 7.0+ 提供的标准全局手势注入通道，用户在系统「无障碍」设置里启用本服务即可，
 * 无需 ROOT —— 这是触摸板点击在电视上"可靠落地"的正解。
 *
 * 用户开启路径：电视 设置 → 无障碍/更多 → 已下载的服务 → 云视远程输入法（触摸注入）
 * ROOT 设备可通过 RootUtil 一条命令静默开启。
 */
public class TouchAccessibilityService extends AccessibilityService {

    private static volatile TouchAccessibilityService sInstance;

    /** 无障碍服务是否已启用并就绪 */
    public static boolean isReady() {
        return sInstance != null;
    }

    /** 注入一次点击（异步派发，立即返回） */
    public static boolean tap(float x, float y) {
        TouchAccessibilityService s = sInstance;
        if (s == null) return false;
        try {
            GestureDescription.Builder b = new GestureDescription.Builder();
            Path p = new Path();
            p.moveTo(x, y);
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, 60));
            return s.dispatchGesture(b.build(), null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 注入长按 */
    public static boolean longPress(float x, float y) {
        TouchAccessibilityService s = sInstance;
        if (s == null) return false;
        try {
            GestureDescription.Builder b = new GestureDescription.Builder();
            Path p = new Path();
            p.moveTo(x, y);
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, 800));
            return s.dispatchGesture(b.build(), null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 注入滑动（滚动用） */
    public static boolean swipe(float x1, float y1, float x2, float y2, long durationMs) {
        TouchAccessibilityService s = sInstance;
        if (s == null) return false;
        try {
            GestureDescription.Builder b = new GestureDescription.Builder();
            Path p = new Path();
            p.moveTo(x1, y1);
            p.lineTo(x2, y2);
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, durationMs));
            return s.dispatchGesture(b.build(), null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 本服务只做手势注入，不消费无障碍事件
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        return super.onUnbind(intent);
    }
}
