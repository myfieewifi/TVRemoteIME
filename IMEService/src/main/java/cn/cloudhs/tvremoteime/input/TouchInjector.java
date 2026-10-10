package cn.cloudhs.tvremoteime.input;

import android.content.Context;
import android.graphics.Point;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;

import java.lang.reflect.Method;

import cn.cloudhs.tvremoteime.IMEService;

/**
 * 通过反射调用系统 InputManager 注入全局触摸事件（模拟触摸板/鼠标）。
 * 需要该输入法被设置为默认 IME 时具备 INJECT_EVENTS 能力，在大多数 TV 系统上可行。
 */
public class TouchInjector {
    private static final String TAG = "TouchInjector";

    // MotionEvent 注入来源，使用 INJECT_INPUT_EVENT_MODE_ASYNC
    private static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
    private static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 2;

    private static Object sInputManager;
    private static Method sInjectInputEventMethod;
    private static boolean sAvailable = false;

    // 当前指针状态
    private float mCurX = -1f;
    private float mCurY = -1f;
    private int mScreenW = -1;
    private int mScreenH = -1;

    // 触摸板灵敏度（每 px 移动 对应屏幕 dp 移动的倍数）
    private float mSensitivity = 1.6f;

    private boolean mPointerDown = false;
    private long mLastDownTime = 0;
    private long mLastTapTime = 0;
    private float mLastTapX = 0f, mLastTapY = 0f;

    public TouchInjector(Context context) {
        init(context);
    }

    private void init(Context context) {
        try {
            Class<?> imClass = Class.forName("android.hardware.input.InputManager");
            Method getInstance = imClass.getMethod("getInstance");
            sInputManager = getInstance.invoke(null);
            // injectInputEvent(InputEvent event, int mode)
            sInjectInputEventMethod = imClass.getMethod("injectInputEvent",
                    Class.forName("android.view.InputEvent"), int.class);
            sAvailable = true;
            Log.i(TAG, "InputManager injectInputEvent 初始化成功");
        } catch (Throwable t) {
            Log.e(TAG, "InputManager 反射初始化失败，触摸板功能不可用", t);
            sAvailable = false;
        }

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            Display d = wm.getDefaultDisplay();
            Point p = new Point();
            d.getRealSize(p);
            mScreenW = p.x;
            mScreenH = p.y;
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            // 初始光标位置在屏幕中心
            mCurX = p.x / 2f;
            mCurY = p.y / 2f;
        }
    }

    public boolean isAvailable() { return sAvailable; }

    /** 设置触摸板灵敏度 0.5~3.0 */
    public void setSensitivity(float s) { this.mSensitivity = Math.max(0.5f, Math.min(3.0f, s)); }

    /**
     * 处理来自前端的触摸板事件。
     * @param action: "move" / "down" / "up" / "click" / "longclick" / "scroll"
     * @param dx, dy: move/scroll 时的相对偏移（触摸板像素）
     */
    public synchronized boolean sendTouch(String action, float dx, float dy) {
        if (!sAvailable) return false;
        try {
            switch (action) {
                case "move":
                    mCurX += dx * mSensitivity;
                    mCurY += dy * mSensitivity;
                    clamp();
                    if (mPointerDown) {
                        injectMotion(MotionEvent.ACTION_MOVE, mCurX, mCurY, true);
                    } else {
                        // 作为 HOVER_MOVE（光标移动，按下未触发时相当于移动鼠标）
                        injectHoverMove(mCurX, mCurY);
                    }
                    break;
                case "down":
                    clamp();
                    mPointerDown = true;
                    mLastDownTime = SystemClock.uptimeMillis();
                    injectMotion(MotionEvent.ACTION_DOWN, mCurX, mCurY, true);
                    break;
                case "up":
                    if (!mPointerDown) return true;
                    mPointerDown = false;
                    long now = SystemClock.uptimeMillis();
                    injectMotion(MotionEvent.ACTION_UP, mCurX, mCurY, true);
                    // 判定点击（短按 300ms 内且位移很小）
                    if (now - mLastDownTime < 320) {
                        // 双击检测
                        if (now - mLastTapTime < 320
                                && Math.abs(mCurX - mLastTapX) < 20
                                && Math.abs(mCurY - mLastTapY) < 20) {
                            // 双击
                            injectClick(mCurX, mCurY);
                            mLastTapTime = 0;
                        } else {
                            mLastTapTime = now;
                            mLastTapX = mCurX;
                            mLastTapY = mCurY;
                        }
                    }
                    break;
                case "click":
                    // 单击（明确请求）
                    clamp();
                    injectClick(mCurX, mCurY);
                    break;
                case "longclick":
                    clamp();
                    injectLongClick(mCurX, mCurY);
                    break;
                case "scroll":
                    // 滚轮：dy >0 向下，dy<0 向上
                    clamp();
                    injectScroll(mCurX, mCurY, -dy * 0.3f);
                    break;
                case "home":
                    resetPos();
                    break;
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "触摸注入失败", t);
            return false;
        }
    }

    private void resetPos() {
        mCurX = mScreenW / 2f;
        mCurY = mScreenH / 2f;
    }

    private void clamp() {
        if (mCurX < 0) mCurX = 0;
        if (mCurY < 0) mCurY = 0;
        if (mScreenW > 0 && mCurX > mScreenW - 1) mCurX = mScreenW - 1;
        if (mScreenH > 0 && mCurY > mScreenH - 1) mCurY = mScreenH - 1;
    }

    private void injectClick(float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        MotionEvent up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        inject(down);
        try { Thread.sleep(16); } catch (InterruptedException ignored) {}
        inject(up);
        down.recycle();
        up.recycle();
    }

    private void injectLongClick(float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        inject(down);
        // 先 move 一下再等 500ms
        try { Thread.sleep(520); } catch (InterruptedException ignored) {}
        MotionEvent up = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        inject(up);
        down.recycle();
        up.recycle();
    }

    private void injectScroll(float x, float y, float vScroll) {
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL,
                x, y, 0);
        ev.setSource(InputDevice.SOURCE_MOUSE);
        try {
            // setAxisValue 是 public 方法
            ev.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
            inject(ev);
        } catch (Throwable t) {
            Log.w(TAG, "滚轮注入失败，退化为 DPAD key", t);
        }
        ev.recycle();
    }

    private void injectHoverMove(float x, float y) {
        // 部分 TV 系统不支持 HOVER，退化为小位移 move/不注出
        try {
            long now = SystemClock.uptimeMillis();
            MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0);
            ev.setSource(InputDevice.SOURCE_MOUSE);
            inject(ev);
            ev.recycle();
        } catch (Throwable t) {
            Log.w(TAG, "hover move 失败", t);
        }
    }

    private void injectMotion(int action, float x, float y, boolean touchSource) {
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(
                action == MotionEvent.ACTION_DOWN ? now : mLastDownTime,
                now, action, x, y, 0);
        ev.setSource(touchSource ? InputDevice.SOURCE_TOUCHSCREEN : InputDevice.SOURCE_MOUSE);
        inject(ev);
        ev.recycle();
    }

    private void inject(MotionEvent ev) {
        if (sInjectInputEventMethod == null || sInputManager == null) return;
        try {
            sInjectInputEventMethod.invoke(sInputManager, ev, INJECT_INPUT_EVENT_MODE_ASYNC);
        } catch (Throwable t) {
            Log.e(TAG, "injectInputEvent 调用失败", t);
        }
    }
}
