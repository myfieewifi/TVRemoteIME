package cn.cloudhs.tvremoteime.upnp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import com.zxt.dlna.dmr.ZxtMediaRenderer;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;

import cn.cloudhs.tvremoteime.MainActivity;
import cn.cloudhs.tvremoteime.R;

/**
 * DLNA 前台常驻服务
 * 确保输入法被杀、Activity退出后，DLNA投屏服务仍能被局域网APP搜索到
 */
public class DLNARendererService extends Service {
    private static final String TAG = "DLNARendererService";
    private static final String CHANNEL_ID = "tvremote_dlna";
    private static final int NOTIFICATION_ID = 101;

    private static volatile boolean sRunning = false;
    private ZxtMediaRenderer mMediaRenderer = null;
    private AndroidUpnpService mUpnpService = null;
    private boolean mBound = false;

    private final ServiceConnection mUpnpConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mUpnpService = (AndroidUpnpService) service;
            Log.i(TAG, "DLNA Upnp service connected, registering renderer device");

            try {
                String dlnaName = getString(R.string.app_name);
                String suffix = cn.cloudhs.tvremoteime.AppConfig.getInstance(DLNARendererService.this).getDLNANameSuffix();
                if(!TextUtils.isEmpty(suffix)) dlnaName += "(" + suffix + ")";

                if(mMediaRenderer != null){
                    mUpnpService.getRegistry().removeAllLocalDevices();
                    mMediaRenderer = null;
                }
                mMediaRenderer = new ZxtMediaRenderer(1, dlnaName, getApplicationContext());
                mUpnpService.getRegistry().addDevice(mMediaRenderer.getDevice());
                Log.i(TAG, "DLNA renderer \"" + dlnaName + "\" registered, ready for cast");
            } catch (Throwable t) {
                Log.e(TAG, "Failed to register DLNA renderer", t);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "DLNA Upnp service disconnected");
            mUpnpService = null;
        }
    };

    public static boolean isRunning() { return sRunning; }

    public static void startMe(Context ctx) {
        try {
            Intent i = new Intent(ctx, DLNARendererService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startMe failed", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        // 绑定 Cling Upnp 服务
        Intent upnpIntent = new Intent(this, AndroidUpnpServiceImpl.class);
        mBound = bindService(upnpIntent, mUpnpConn, Context.BIND_AUTO_CREATE);
        Log.i(TAG, "DLNA renderer service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new Binder();
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        try { if (mBound) { unbindService(mUpnpConn); mBound = false; } } catch (Throwable ignore) {}
        try {
            if (mUpnpService != null && mMediaRenderer != null) {
                mMediaRenderer.stopAllMediaPlayers();
                mUpnpService.getRegistry().removeAllLocalDevices();
            }
        } catch (Throwable ignore) {}
        mUpnpService = null;
        mMediaRenderer = null;
        stopForeground(true);
        Log.i(TAG, "DLNA renderer service destroyed");
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                CHANNEL_ID, "DLNA投屏服务", NotificationManager.IMPORTANCE_MIN);
            chan.setDescription("保持DLNA投屏可被局域网设备发现");
            chan.setLightColor(Color.BLUE);
            chan.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            chan.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(chan);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return nb.setContentTitle(getString(R.string.app_name))
                 .setContentText("投屏服务已启动，可在视频APP中搜索本设备")
                 .setSmallIcon(R.mipmap.ic_launcher)
                 .setContentIntent(pi)
                 .setOngoing(true)
                 .setPriority(Notification.PRIORITY_MIN)
                 .build();
    }
}
