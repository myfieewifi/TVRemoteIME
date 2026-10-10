package cn.cloudhs.tvremoteime;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import cn.cloudhs.tvremoteime.adb.AdbHelper;
import cn.cloudhs.tvremoteime.service.ForegroundGuardService;

/**
 * 开机/挂载存储广播接收器：开机后自启输入法服务 + 前台守护服务。
 * 注意：Android 8+ 后无法后台启动普通 Service，但 startForegroundService 在 BOOT_COMPLETED 广播中是被允许的。
 */
public class IMEServiceBroadCastReceiver extends BroadcastReceiver {
    private final String TAG = "IMEServiceBCR";
    private final String ACTION_BOOT = "android.intent.action.BOOT_COMPLETED";
    private final String MEDIA_MOUNTED = "android.intent.action.MEDIA_MOUNTED";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "receive msg:" + intent.getAction());
        Environment.context = context.getApplicationContext();
        if (ACTION_BOOT.equals(intent.getAction())
                || MEDIA_MOUNTED.equals(intent.getAction())) {
            // 启动输入法主服务
            Intent svc = new Intent();
            svc.setComponent(new ComponentName(context, IMEService.class));
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(svc);
                else context.startService(svc);
            } catch (Throwable ignore) {}
            // 启动前台守护服务
            try {
                Intent g = new Intent(context, ForegroundGuardService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(g);
                else context.startService(g);
            } catch (Throwable ignore) {}
            // 启动内置 ADB 服务（如果非默认输入法，可通过 Web 端引导走 ADB 切换）
            if (!Environment.isDefaultIME(context)) {
                try { if (AdbHelper.getInstance() == null) AdbHelper.createInstance(); } catch (Throwable ignore) {}
            }
            // ROOT 设备：自动把自己设为默认输入法
            try {
                if (RootUtil.isRootAvailable()) {
                    RootUtil.setAsDefaultIME(context);
                    RootUtil.keepServiceAlive(context);
                }
            } catch (Throwable ignore) {}
        }
    }
}
