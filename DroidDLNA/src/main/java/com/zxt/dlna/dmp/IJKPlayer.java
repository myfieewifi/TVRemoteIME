package com.zxt.dlna.dmp;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import com.zxt.dlna.util.Action;

/**
 * DLNA 控制命令中转（替代旧的 IJK 播放器 Activity）
 * 因为播放器运行在独立 :player 进程，DLNA 的控制指令（播放/暂停/停止/拖动/音量）
 * 会被 Cling 框架通过广播发送过来，本类负责把这些广播转发到 ExoPlayerActivity；
 * 同时接收播放器回传的状态（开始/暂停/停止/结束），通过 MediaListener 回调给 Cling 框架。
 */
public class IJKPlayer {
    private static final String TAG = "DLNABridge";

    // 播放器回传状态的广播 action（由 ExoPlayerActivity 在播放状态变化时发送）
    public static final String ACTION_PLAYER_START   = "cn.cloudhs.tvremoteime.PLAYER_START";
    public static final String ACTION_PLAYER_PAUSE   = "cn.cloudhs.tvremoteime.PLAYER_PAUSE";
    public static final String ACTION_PLAYER_STOP    = "cn.cloudhs.tvremoteime.PLAYER_STOP";
    public static final String ACTION_PLAYER_END     = "cn.cloudhs.tvremoteime.PLAYER_END";
    public static final String ACTION_PLAYER_SEEK_DONE = "cn.cloudhs.tvremoteime.PLAYER_SEEK_DONE";

    private static BroadcastReceiver sReceiver = null;
    private static boolean sRegistered = false;
    private static Context sAppCtx = null;

    public static GPlayer.MediaListener mMediaListener;

    /**
     * 旧代码兼容入口：ZxtMediaPlayer.ensureControl() 调用此方法启动播放器
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            Log.i(TAG, "sent play intent: " + playURI);
        } catch (Throwable t) {
            Log.e(TAG, "start play service failed", t);
        }
    }

    /**
     * 被 ZxtMediaPlayer 调用（旧拼写），直接转发到新方法
     */
    public static void ensureReceiverRegistered(Context ctx) {
        ensureReceiverRegistered(ctx);
    }

    /**
     * 设置播放状态回调监听器，由 ZxtMediaPlayer 传入
     */
    public static void setMediaListener(GPlayer.MediaListener listener) {
        mMediaListener = listener;
        Log.i(TAG, "media listener set: " + (listener != null));
    }

    /**
     * 注册广播接收器，双向桥接 DLNA <-> 播放器
     */
    public static synchronized void ensureReceiverRegistered(final Context ctx) {
        if (sRegistered) return;
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();
        sAppCtx = appCtx;
        sReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                String action = intent.getAction();
                // ---- 播放器回传状态：调用 MediaListener 回调 Cling 框架 ----
                try {
                    if (ACTION_PLAYER_START.equals(action) && mMediaListener != null) {
                        mMediaListener.start();
                        return;
                    }
                    if (ACTION_PLAYER_PAUSE.equals(action) && mMediaListener != null) {
                        mMediaListener.pause();
                        return;
                    }
                    if (ACTION_PLAYER_STOP.equals(action) && mMediaListener != null) {
                        mMediaListener.stop();
                        return;
                    }
                    if (ACTION_PLAYER_END.equals(action) && mMediaListener != null) {
                        mMediaListener.endOfMedia();
                        return;
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "notify listener failed", t);
                }
                // ---- DLNA 控制指令：转发到播放器进程 ----
                try {
                    Intent fwd = new Intent(action);
                    fwd.setPackage(appCtx.getPackageName());
                    if (intent.getExtras() != null) fwd.putExtras(intent.getExtras());
                    fwd.setComponent(new ComponentName(appCtx.getPackageName(),
                            "cn.cloudhs.tvremoteime.player.ExoPlayerActivity"));
                    // 这些 action 是发给 Activity 的控制广播；
                    // Activity 在独立进程里，我们通过桥接服务转发
                    Intent ctrl = new Intent();
                    ctrl.setComponent(new ComponentName(appCtx.getPackageName(),
                            "cn.cloudhs.tvremoteime.player.DlnaPlayBridgeService"));
                    ctrl.putExtra("dlna_action", action);
                    if (intent.getExtras() != null) ctrl.putExtras(intent.getExtras());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appCtx.startForegroundService(ctrl);
                    } else {
                        appCtx.startService(ctrl);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "forward control failed", t);
                }
                // 音量控制直接在本进程处理（系统音量，不跨进程）
                if (Action.SET_VOLUME.equals(action)) {
                    try {
                        AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
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
        // DLNA 发给播放器的控制指令
        filter.addAction(Action.PLAY);
        filter.addAction(Action.PAUSE);
        filter.addAction(Action.STOP);
        filter.addAction(Action.SEEK);
        filter.addAction(Action.SET_VOLUME);
        // 播放器回传给 DLNA 的状态
        filter.addAction(ACTION_PLAYER_START);
        filter.addAction(ACTION_PLAYER_PAUSE);
        filter.addAction(ACTION_PLAYER_STOP);
        filter.addAction(ACTION_PLAYER_END);
        filter.addAction(ACTION_PLAYER_SEEK_DONE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(sReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                appCtx.registerReceiver(sReceiver, filter);
            }
            sRegistered = true;
            Log.i(TAG, "DLNA bridge receiver registered");
        } catch (Throwable t) {
            Log.e(TAG, "register receiver failed", t);
        }
    }
}
