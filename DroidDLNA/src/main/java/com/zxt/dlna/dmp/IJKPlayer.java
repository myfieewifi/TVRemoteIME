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
 * DLNA 控制命令中转桥
 *
 * 接收 ZxtMediaPlayer 发来的控制广播（Action.DMR + helpAction），转发到
 * cn.cloudhs.tvremoteime.player.DlnaPlayBridgeService（主进程服务）；
 * 同时接收 ExoPlayerActivity 回传的播放状态（PLAYER_START/PAUSE/STOP/END/SEEK_DONE），
 * 通过 MediaListener 回调给 Cling 框架，让手机APP能看到同步的状态。
 */
public class IJKPlayer {
    private static final String TAG = "DLNABridge";

    // ExoPlayerActivity 回传播放状态的 action（播放器在独立 :player 进程发送，需要用包限定接收）
    public static final String ACTION_PLAYER_START    = "cn.cloudhs.tvremoteime.PLAYER_START";
    public static final String ACTION_PLAYER_PAUSE    = "cn.cloudhs.tvremoteime.PLAYER_PAUSE";
    public static final String ACTION_PLAYER_STOP     = "cn.cloudhs.tvremoteime.PLAYER_STOP";
    public static final String ACTION_PLAYER_END      = "cn.cloudhs.tvremoteime.PLAYER_END";
    public static final String ACTION_PLAYER_SEEK_DONE = "cn.cloudhs.tvremoteime.PLAYER_SEEK_DONE";

    private static BroadcastReceiver sReceiver = null;
    private static boolean sRegistered = false;
    private static Context sAppCtx = null;

    public static GPlayer.MediaListener mMediaListener;

    /** 旧代码兼容入口：ZxtMediaPlayer.setURI 调用此方法拉起播放器 */
    public static void intentTo(Class<?> cls, Context context, String playURI, String name) {
        // 不实际使用 cls（旧 XLVideoPlayActivity），统一走 DlnaPlayBridgeService 拉起 ExoPlayer
        ensureReceiverRegistered(context);
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(context.getPackageName(),
                "cn.cloudhs.tvremoteime.player.DlnaPlayBridgeService"));
        intent.putExtra("type", "video");
        intent.putExtra("name", name == null ? "DLNA投屏" : name);
        intent.putExtra("playURI", playURI);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Log.e(TAG, "启动播放桥接服务失败", t);
        }
    }

    public static void setMediaListener(GPlayer.MediaListener listener) {
        mMediaListener = listener;
    }

    public static void ensureReceiverRegistered(Context context) {
        if (sRegistered) return;
        if (context == null) return;
        sAppCtx = context.getApplicationContext();
        IntentFilter filter = new IntentFilter();
        // ZxtMediaPlayer 的控制指令：Action.DMR + helpAction 字段（play/pause/stop/seek/setvolume）
        filter.addAction(Action.DMR);
        // 播放器状态回传
        filter.addAction(ACTION_PLAYER_START);
        filter.addAction(ACTION_PLAYER_PAUSE);
        filter.addAction(ACTION_PLAYER_STOP);
        filter.addAction(ACTION_PLAYER_END);
        filter.addAction(ACTION_PLAYER_SEEK_DONE);
        sReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null) return;
                String act = intent.getAction();
                if (act == null) return;

                if (Action.DMR.equals(act)) {
                    // ZxtMediaPlayer 通过 helpAction 字段发指令（play/pause/stop/seek/setvolume）
                    String help = intent.getStringExtra("helpAction");
                    if (help == null) return;
                    forwardCtrlToPlayer(help, intent);
                    return;
                }

                // 播放器状态回传 → 通知 Cling
                if (mMediaListener == null) return;
                try {
                    if (ACTION_PLAYER_START.equals(act)) {
                        mMediaListener.start();
                    } else if (ACTION_PLAYER_PAUSE.equals(act)) {
                        mMediaListener.pause();
                    } else if (ACTION_PLAYER_STOP.equals(act) || ACTION_PLAYER_END.equals(act)) {
                        mMediaListener.stop();
                        if (ACTION_PLAYER_END.equals(act)) mMediaListener.endOfMedia();
                    } else if (ACTION_PLAYER_SEEK_DONE.equals(act)) {
                        int pos = intent.getIntExtra("position", 0);
                        mMediaListener.positionChanged(pos);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "回调播放器状态失败", t);
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sAppCtx.registerReceiver(sReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                sAppCtx.registerReceiver(sReceiver, filter);
            }
            sRegistered = true;
            Log.i(TAG, "DLNA 控制/状态广播接收器已注册");
        } catch (Throwable t) {
            Log.e(TAG, "注册广播接收器失败", t);
        }
    }

    /** 把 DLNA 控制指令通过 DlnaPlayBridgeService 转发给独立进程中的播放器 */
    private static void forwardCtrlToPlayer(String help, Intent src) {
        if (sAppCtx == null) return;
        String cmd;
        long seekPos = 0;
        switch (help) {
            case Action.PLAY:    cmd = "play";  break;
            case Action.PAUSE:   cmd = "pause"; break;
            case Action.STOP:    cmd = "stop";  break;
            case Action.SEEK:
                cmd = "seek";
                // ZxtMediaPlayer.seek() 传的 position 单位是毫秒
                seekPos = src.getLongExtra("position", 0);
                break;
            case Action.SET_VOLUME:
                // 音量由 AudioManager 全局控制，这里仅设置系统媒体音量
                try {
                    double v = src.getDoubleExtra("volume", -1);
                    if (v >= 0) {
                        AudioManager am = (AudioManager) sAppCtx.getSystemService(Context.AUDIO_SERVICE);
                        if (am != null) {
                            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, (int)(v * max), 0);
                        }
                    }
                } catch (Throwable ignore) {}
                return;
            default:
                Log.w(TAG, "未识别的 DLNA 控制指令: " + help);
                return;
        }
        Intent si = new Intent();
        si.setComponent(new ComponentName(sAppCtx.getPackageName(),
                "cn.cloudhs.tvremoteime.player.DlnaPlayBridgeService"));
        si.putExtra("dlna_action", cmd);
        if ("seek".equals(cmd)) {
            si.putExtra("seek_pos", seekPos);
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                sAppCtx.startForegroundService(si);
            } else {
                sAppCtx.startService(si);
            }
        } catch (Throwable t) {
            Log.e(TAG, "转发 DLNA 控制指令失败: " + cmd, t);
        }
    }
}
