package cn.cloudhs.tvremoteime.service;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import cn.cloudhs.tvremoteime.IMEService;
import cn.cloudhs.tvremoteime.MainActivity;
import cn.cloudhs.tvremoteime.R;

/**
 * 前台守护服务：
 * - 通过 startForeground 常驻通知，降低被系统 LMKD/厂商杀后台概率
 * - 定期检查远程服务是否还在运行，掉了就拉起
 * - 输入法 Service 本身不能成为前台服务（IME 系统特殊处理），故独立一个守护进程
 */
public class ForegroundGuardService extends Service {
    private static final String CH_ID = "tvremote_guard";
    private static final int NOTI_ID = 0x7701;
    private static final long CHECK_INTERVAL = 15_000L; // 每15秒巡逻一次

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable checkTask;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTI_ID, buildNotification("云视远程输入法已在后台运行"));

        checkTask = new Runnable() {
            @Override public void run() {
                ensureIMEServiceRunning();
                handler.postDelayed(this, CHECK_INTERVAL);
            }
        };
        handler.post(checkTask);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY：被系统杀掉后会在资源允许时自动重建
        ensureIMEServiceRunning();
        return START_STICKY;
    }

    private void ensureIMEServiceRunning() {
        try {
            // 检查 IMEService 是否存活；不存活就启动
            if (!isServiceRunning(IMEService.class.getName())) {
                Intent svc = new Intent();
                svc.setComponent(new ComponentName(this, IMEService.class));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
            }
        } catch (Throwable ignore) {}
    }

    private boolean isServiceRunning(String cls) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return true;
            for (ActivityManager.RunningServiceInfo s : am.getRunningServices(200)) {
                if (s.service != null && cls.equals(s.service.getClassName())) return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CH_ID, "远程输入守护", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("输入法服务常驻通知");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String text) {
        Intent main = new Intent(this, MainActivity.class);
        main.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, main,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nb = new Notification.Builder(this, CH_ID);
        } else {
            nb = new Notification.Builder(this);
        }
        nb.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("云视远程输入法")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(Notification.PRIORITY_LOW);
        return nb.build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(checkTask);
        // 自杀后让系统重启
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(new Intent(this, ForegroundGuardService.class));
        } else {
            startService(new Intent(this, ForegroundGuardService.class));
        }
    }
}
