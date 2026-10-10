package cn.cloudhs.tvremoteime.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 云视远程输入法 - 屏幕光标浮层
 *
 * 触摸板是通过系统注入事件来移动指针的，而注入事件不会触发系统自带的鼠标指针绘制，
 * 因此电视上「看不到光标」，用户无法在点击前判断位置。本类用「显示在其他应用上层」
 * 权限在屏幕上绘制一个圆形光标，跟随触摸板移动，做到「看得见、点得准」。
 *
 * 若未授予悬浮窗权限，则静默降级（不抛异常、不影响触摸点击本身）。
 */
public class CursorOverlay {
    private static final String TAG = "CursorOverlay";

    private final Context mCtx;
    private WindowManager mWm;
    private View mDot;
    private WindowManager.LayoutParams mLp;
    private boolean mAdded = false;
    private int mSizePx = 0;
    private int mScreenW = 0;
    private int mScreenH = 0;

    public CursorOverlay(Context context) {
        this.mCtx = context.getApplicationContext();
    }

    /** 是否具备绘制悬浮光标的权限 */
    public boolean canShow() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return Settings.canDrawOverlays(mCtx);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isShowing() {
        return mAdded;
    }

    public void show() {
        if (mAdded || !canShow()) return;
        try {
            mWm = (WindowManager) mCtx.getSystemService(Context.WINDOW_SERVICE);
            if (mWm == null) return;
            float density = mCtx.getResources().getDisplayMetrics().density;
            mSizePx = (int) (18 * density);
            if (mSizePx < 12) mSizePx = 12;

            mDot = new View(mCtx) {
                private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

                @Override
                protected void onDraw(Canvas canvas) {
                    float r = Math.min(getWidth(), getHeight()) / 2f;
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0xE64F6DFF);
                    canvas.drawCircle(r, r, r * 0.8f, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(2f, r * 0.16f));
                    paint.setColor(Color.WHITE);
                    canvas.drawCircle(r, r, r * 0.8f, paint);
                }
            };

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            mLp = new WindowManager.LayoutParams(mSizePx, mSizePx, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            mLp.gravity = Gravity.TOP | Gravity.LEFT;
            mLp.x = 0;
            mLp.y = 0;

            mWm.addView(mDot, mLp);
            mAdded = true;

            android.util.DisplayMetrics dm = mCtx.getResources().getDisplayMetrics();
            mScreenW = dm.widthPixels;
            mScreenH = dm.heightPixels;
        } catch (Throwable t) {
            Log.e(TAG, "显示光标失败", t);
            mAdded = false;
        }
    }

    /** 把光标移动到屏幕坐标 (x, y) */
    public void move(float x, float y) {
        try {
            if (canShow() && !mAdded) {
                show();
            }
            if (!mAdded || mWm == null || mDot == null) return;
            int px = (int) (x - mSizePx / 2f);
            int py = (int) (y - mSizePx / 2f);
            if (px < 0) px = 0;
            if (py < 0) py = 0;
            mLp.x = px;
            mLp.y = py;
            mWm.updateViewLayout(mDot, mLp);
        } catch (Throwable ignore) {
        }
    }

    public void hide() {
        try {
            if (mAdded && mWm != null && mDot != null) {
                mWm.removeView(mDot);
            }
        } catch (Throwable ignore) {
        }
        mAdded = false;
    }
}
