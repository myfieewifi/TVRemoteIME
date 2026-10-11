package cn.cloudhs.tvremoteime.player;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

import java.net.URLDecoder;

import cn.cloudhs.tvremoteime.AppConfig;
import cn.cloudhs.tvremoteime.R;

/**
 * v1.0 新版投屏播放器（基于 ExoPlayer 2.19.1）
 *
 * 支持 MP4/MKV/FLV/HLS(m3u8)/DASH/RTSP；运行于 :player 独立进程，退出即回收内存。
 *
 * Intent extra:
 *   videoUrl / url / data:      视频地址
 *   videoTitle:                  标题（可选）
 *   type:                        hls/dash/rtsp（可选，自动检测）
 *   userAgent:                   HTTP User-Agent（可选，默认读配置）
 *   fromDLNA:                    是否由 DLNA 投屏拉起（true 时会回传播放状态）
 */
public class ExoPlayerActivity extends Activity {

    private static final String TAG = "ExoPlayerActivity";

    private StyledPlayerView playerView;
    private ExoPlayer player;
    private TextView tvTitle, tvPos, tvDur, tvStatus;
    private SeekBar seekBar;
    private ImageButton btnPlayPause, btnFF, btnRew;
    private LinearLayout controls;
    private LinearLayout topBar;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean mControlsShowing = true;
    private Runnable hideControlsTask;
    /** 顶部栏与控件区无操作自动隐藏延时 */
    private static final long HIDE_DELAY_MS = 5000L;
    private String videoUrl;
    private String videoTitle;
    private boolean mDestroyed = false;

    /** 接收主进程发来的「停止播放」广播 */
    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { finish(); }
    };

    /** 接收 DLNA 控制指令（播放/暂停/停止/拖动），由主进程 DlnaPlayBridgeService 转发 */
    private final BroadcastReceiver dlnaCtrlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || player == null) return;
            String cmd = intent.getStringExtra(DlnaPlayBridgeService.EXTRA_CTRL_CMD);
            if (cmd == null) return;
            Log.i(TAG, "DLNA 控制指令: " + cmd);
            switch (cmd) {
                case "play":
                    player.setPlayWhenReady(true);
                    showControls(false);
                    break;
                case "pause":
                    player.setPlayWhenReady(false);
                    break;
                case "stop":
                    finish();
                    break;
                case "seek":
                    long pos = intent.getLongExtra(DlnaPlayBridgeService.EXTRA_SEEK_POS, -1);
                    if (pos >= 0) player.seekTo(pos);
                    break;
                case "ff":
                    // 相对快进 10 秒（Web 端 /ctrl 使用）
                    player.seekTo(player.getCurrentPosition() + 10000);
                    break;
                case "rew":
                    // 相对快退 10 秒（Web 端 /ctrl 使用）
                    player.seekTo(Math.max(0, player.getCurrentPosition() - 10000));
                    break;
                case "toggle":
                    // 播放/暂停切换（状态由播放器自身判定）
                    if (player.isPlaying()) player.pause(); else player.play();
                    break;
            }
        }
    };

    /** 是否由 DLNA 投屏启动，true 时在播放状态变化时广播回传给 DLNA 框架 */
    private boolean mFromDLNA = false;

    private void notifyDlnaState(String stateAction) {
        if (!mFromDLNA) return;
        try {
            Intent i = new Intent(stateAction);
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Throwable t) { Log.e(TAG, "notify dlna state failed", t); }
    }

    public static void start(Context ctx, String url, String title) {
        Intent i = new Intent(ctx, ExoPlayerActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra("videoUrl", url);
        i.putExtra("videoTitle", title);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_exo_player);

        playerView = findViewById(R.id.player_view);
        tvTitle = findViewById(R.id.tv_title);
        tvPos = findViewById(R.id.tv_pos);
        tvDur = findViewById(R.id.tv_dur);
        tvStatus = findViewById(R.id.tv_status);
        seekBar = findViewById(R.id.seekbar);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnFF = findViewById(R.id.btn_ff);
        btnRew = findViewById(R.id.btn_rew);
        controls = findViewById(R.id.controls);
        topBar = findViewById(R.id.top_bar);
        // 控件不持有焦点：实体遥控器的方向键是播放快进/快退，不做焦点导航，
        // 防止按键被按钮/进度条抢占消费
        btnPlayPause.setFocusable(false);
        btnFF.setFocusable(false);
        btnRew.setFocusable(false);
        seekBar.setFocusable(false);

        videoUrl = getIntent().getStringExtra("videoUrl");
        videoTitle = getIntent().getStringExtra("videoTitle");
        mFromDLNA = getIntent().getBooleanExtra("fromDLNA", false);
        if (videoUrl == null && getIntent().getData() != null) {
            videoUrl = getIntent().getData().toString();
        }
        if (videoUrl == null) videoUrl = getIntent().getStringExtra("url");
        if (videoTitle == null) videoTitle = mFromDLNA ? "DLNA投屏" : "投屏播放";

        // 不再二次 URLDecoder.decode：投屏地址在 PlayRequestProcesser 处经由 NanoHTTPD
        // decodeParms 已做过一次百分号解码，这里再解会把 base64 token 里的 "+" 解成空格、
        // 或对残留 %xx 二次解码而损坏地址（含签名 token 的 URL 会播不了）。

        tvTitle.setText(videoTitle);
        tvStatus.setText("准备中...");

        // 左上角「关闭」与右上角「截图」按钮已按需求移除：
        // 容易误操作，退出统一走遥控器返回键
        btnPlayPause.setOnClickListener(v -> togglePlay());
        btnFF.setOnClickListener(v -> seekForward());
        btnRew.setOnClickListener(v -> seekBack());
        // 点击播放器主体切换控制条
        playerView.setOnClickListener(v -> toggleControls());
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            boolean wasPlaying = false;
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && player != null) {
                    long dur = player.getDuration();
                    long pos = dur > 0 ? dur * progress / seekBar.getMax() : progress;
                    tvPos.setText(formatTime(pos));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                wasPlaying = player != null && player.isPlaying();
                if (wasPlaying) player.pause();
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (player != null) {
                    long dur = player.getDuration();
                    long pos = dur > 0 ? dur * seekBar.getProgress() / seekBar.getMax() : seekBar.getProgress();
                    player.seekTo(pos);
                    if (wasPlaying) player.play();
                }
            }
        });

        hideControlsTask = () -> showControls(false);

        // 两个 Receiver 分别 try-catch + 各自加 flag：targetSdk 33 下 registerReceiver 不带
        // RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED 会抛 SecurityException；若共用一个 try，
        // stopReceiver 失败会连带 dlnaCtrlReceiver 也注册不上（网页停止 + DLNA 控制全断）。
        // 两个广播都 setPackage(本包)，同包跨进程投递，用 RECEIVER_NOT_EXPORTED 即可收到。
        try {
            IntentFilter stopFilter = new IntentFilter("cn.cloudhs.tvremoteime.ACTION_STOP_PLAY");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stopReceiver, stopFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(stopReceiver, stopFilter);
            }
        } catch (Exception e) { Log.e(TAG, "注册停止广播失败", e); }
        try {
            IntentFilter dlnaFilter = new IntentFilter(DlnaPlayBridgeService.ACTION_DLNA_CTRL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dlnaCtrlReceiver, dlnaFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(dlnaCtrlReceiver, dlnaFilter);
            }
        } catch (Exception e) { Log.e(TAG, "注册DLNA控制广播失败", e); }

        preparePlayer();
        // 控制条（快进/快退/暂停/进度条）默认常显：
        // 电视端遥控器不易唤回自动隐藏的浮层，实测用户"看不到控件"
        showControls(true);
    }

    /** 连推第二个视频时（CLEAR_TOP|SINGLE_TOP 复用本实例）换源播放，
     *  避免新起一个 Activity 导致两个视频同时出声 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String newUrl = intent.getStringExtra("videoUrl");
        if (newUrl == null && intent.getData() != null) newUrl = intent.getData().toString();
        if (newUrl == null) newUrl = intent.getStringExtra("url");
        if (newUrl == null || newUrl.trim().isEmpty()) return;
        String newTitle = intent.getStringExtra("videoTitle");
        mFromDLNA = intent.getBooleanExtra("fromDLNA", mFromDLNA);
        videoUrl = newUrl;
        videoTitle = newTitle == null ? (mFromDLNA ? "DLNA投屏" : "投屏播放") : newTitle;
        tvTitle.setText(videoTitle);
        tvStatus.setText("切换中...");
        preparePlayer();
    }

    private void preparePlayer() {
        try {
            // 换源（onNewIntent）时先释放旧实例，否则两个 player 同时播
            if (player != null) {
                try { player.stop(); player.release(); } catch (Throwable ignored) {}
                player = null;
            }
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);

            String userAgent = getIntent().getStringExtra("userAgent");
            if (userAgent == null || userAgent.trim().isEmpty()) {
                userAgent = AppConfig.getInstance(this).getPlayerUserAgent();
            }

            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(userAgent)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(15000);

            DefaultDataSource.Factory dsFactory = new DefaultDataSource.Factory(this, httpFactory);
            Uri uri = Uri.parse(videoUrl);
            String last = (uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment()).toLowerCase();
            String q = (uri.getQuery() == null ? "" : uri.getQuery()).toLowerCase();
            String type = getIntent().getStringExtra("type");

            MediaSource mediaSource;
            if ("hls".equalsIgnoreCase(type) || last.endsWith(".m3u8") || q.contains("m3u8")) {
                mediaSource = new HlsMediaSource.Factory(dsFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            } else if ("dash".equalsIgnoreCase(type) || last.endsWith(".mpd")) {
                mediaSource = new DashMediaSource.Factory(dsFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            } else if ("rtsp".equalsIgnoreCase(type) || "rtsp".equalsIgnoreCase(uri.getScheme())) {
                mediaSource = new RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(uri));
            } else {
                mediaSource = new ProgressiveMediaSource.Factory(dsFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            }

            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (mDestroyed) return;
                    switch (state) {
                        case Player.STATE_BUFFERING:
                            tvStatus.setText("缓冲中..."); break;
                        case Player.STATE_READY:
                            tvStatus.setText("");
                            updateSeekbar();
                            break;
                        case Player.STATE_ENDED:
                            tvStatus.setText("播放结束");
                            notifyDlnaState("cn.cloudhs.tvremoteime.PLAYER_END");
                            finish();
                            break;
                    }
                }
                @Override public void onIsPlayingChanged(boolean isPlaying) {
                    if (mDestroyed) return;
                    btnPlayPause.setImageResource(isPlaying
                            ? android.R.drawable.ic_media_pause
                            : android.R.drawable.ic_media_play);
                    notifyDlnaState(isPlaying
                            ? "cn.cloudhs.tvremoteime.PLAYER_START"
                            : "cn.cloudhs.tvremoteime.PLAYER_PAUSE");
                }
                @Override public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "播放器错误", error);
                    tvStatus.setText("播放失败：" + error.getErrorCodeName());
                    Toast.makeText(ExoPlayerActivity.this, "无法播放此视频", Toast.LENGTH_LONG).show();
                }
            });

            player.setMediaSource(mediaSource);
            player.setPlayWhenReady(true);
            player.prepare();
        } catch (Exception e) {
            Log.e(TAG, "播放器初始化失败", e);
            tvStatus.setText("播放器初始化失败");
        }
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) player.pause(); else player.play();
        showControls(true);
    }

    private void seekForward() {
        if (player == null) return;
        long dur = player.getDuration();
        long pos = player.getCurrentPosition() + 10000;
        if (dur > 0 && pos > dur) pos = dur;
        player.seekTo(pos);
        showControls(true);
    }

    private void seekBack() {
        if (player == null) return;
        long pos = player.getCurrentPosition() - 10000;
        if (pos < 0) pos = 0;
        player.seekTo(pos);
        showControls(true);
    }

    private final Runnable seekBarUpdater = new Runnable() {
        @Override public void run() { updateSeekbar(); }
    };

    private void updateSeekbar() {
        if (mDestroyed || player == null) return;
        long dur = player.getDuration();
        long pos = player.getCurrentPosition();
        if (dur > 0 && dur != C.TIME_UNSET) {
            int max = seekBar.getMax();
            seekBar.setProgress((int) (pos * max / dur));
            tvPos.setText(formatTime(pos));
            tvDur.setText(formatTime(dur));
        } else {
            tvPos.setText(formatTime(pos));
            tvDur.setText("--:--");
        }
        handler.postDelayed(seekBarUpdater, 500);
    }

    private String formatTime(long ms) {
        if (ms <= 0 || ms == C.TIME_UNSET) return "00:00";
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format("%02d:%02d:%02d", h, m, sec);
        return String.format("%02d:%02d", m, sec);
    }

    private void showControls(boolean show) {
        if (mDestroyed) return;
        handler.removeCallbacks(hideControlsTask);
        if (topBar != null) topBar.setVisibility(show ? View.VISIBLE : View.GONE);
        controls.setVisibility(show ? View.VISIBLE : View.GONE);
        mControlsShowing = show;
        // 顶部栏与控件区 X 秒无操作后自动隐藏；任意遥控键/触摸即唤回
        if (show) {
            handler.postDelayed(hideControlsTask, HIDE_DELAY_MS);
        }
    }

    private void toggleControls() { showControls(!mControlsShowing); }

    /**
     * 实体遥控器按键拦截。
     * 必须在 dispatchKeyEvent 层处理而不是 onKeyDown：按键先经视图树派发，
     * 控制条上的按钮/进度条一旦持有焦点就会把左右键消费成焦点移动/拖动，
     * 永远到不了 onKeyDown——这正是"实体遥控器左右键无法快进后退"的根因。
     * 在 Activity 入口直接拦截后，焦点状态不再影响任何遥控操作。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) seekBack();      // 长按连发：每次 ACTION_DOWN 重复都快退 10s
                showControls(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) seekForward();
                showControls(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down) togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down) togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) seekForward();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) seekBack();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (down) toggleControls();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // 上下键仅唤回/保持控制条
                if (down) showControls(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
                // 返回键 = 退出播放器（关闭按钮已移除，统一走这里）
                if (down) finish();
                return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        notifyDlnaState("cn.cloudhs.tvremoteime.PLAYER_STOP");
        try { unregisterReceiver(stopReceiver); } catch (Exception ignore) {}
        try { unregisterReceiver(dlnaCtrlReceiver); } catch (Exception ignore) {}
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    // ===== 生命周期说明 =====
    // 此 Activity 是电视播放器：用户在手机端操作、电视屏不操作时，
    // 系统通知/分屏/切后台都不应暂停播放；只在用户主动按返回/收到停止广播/播放结束时才退出。
    // 因此 onPause/onStop **不暂停播放器**，仅在 onDestroy 释放资源。
}
