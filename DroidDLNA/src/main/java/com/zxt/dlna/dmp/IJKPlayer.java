package com.zxt.dlna.dmp;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.zxt.dlna.util.Action;

/**
 * DLNA 控制命令中转（替代旧的 IJK 播放器 Activity）
 * 因为播放器运行在独立 :player 进程，DLNA 的控制指令（播放/暂停/停止/拖动/音量）
 * 会被 Cling 框架通过广播发送过来，本类负责把这些广播转发到 ExoPlayerActivity
 */
public class IJKPlayer {
    private static final String TAG = "DLNABridge";
    private static BroadcastReceiver sReceiver = null;
    private static boolean sRegistered = false;

    public static GPlayer.MediaListener mMediaListener;

    /**
     * 旧代码保留签名（被 ZxtMediaPlayer 调用）。
     * 新版由播放桥接服务直接拉起 ExoPlayerActivity，这里只需确保广播接收器已注册
     */
    public static void intentTo(Class<?> cls, Context context, String playURI, String name) {
        ensureReceiverRegistered(context);
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(context.getPackageName(),
                    "cn.cloudhs.tvremoteime.player.DlnaPlayBridgeService"));
            intent.putExtra("type", "video");
            intent.putExtra("name", name == null ? "" : name);
            intent.putExtra("playURI", playURI);
            context.startService(intent);
        } catch (Throwable t) {
            Log.e(TAG, "启动 DLNA 播放桥接服务失败", t);
        }
    }

    /** 注册接收 DLNA 控制广播 */
    public static synchronized void ensureReceiverRegistered(final Context ctx) {
        if (sRegistered) return;
        try {
            sReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || intent.getAction() == null) return;
                    String action = intent.getAction();
                    Log.i(TAG, "DLNA cmd: " + action);
                    // 把控制命令重新定向到 ExoPlayerActivity（跨进程广播）
                    Intent fwd = new Intent(action);
                    fwd.setPackage(context.getPackageName());
                    fwd.putExtras(intent);
                    context.sendBroadcast(fwd);

                    // 音量指令在本地直接处理（音量控制不依赖播放器进程）
                    if (Action.SET_VOLUME.equals(action)) {
                        try {
                            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                            double vol = intent.getDoubleExtra("volume", 0);
                            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                            int target = (int) (vol * max);
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                        } catch (Throwable t) {
                            Log.e(TAG, "set volume failed", t);
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Action.PLAY);
            filter.addAction(Action.PAUSE);
            filter.addAction(Action.STOP);
            filter.addAction(Action.SEEK);
            filter.addAction(Action.SET_VOLUME);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(sReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(sReceiver, filter);
            }
            sRegistered = true;
            Log.i(TAG, "DLNA control receiver registered");
        } catch (Throwable t) {
            Log.e(TAG, "register receiver failed", t);
        }
    }
}
