package cn.cloudhs.tvremoteime.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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
import com.google.android.exoplayer2.util.Util;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

import cn.cloudhs.tvremoteime.AppConfig;
import cn.cloudhs.tvremoteime.R;

/**
 * v1.0 新版投屏播放器：基于 ExoPlayer 2.19.1，支持 MP4/MKV/FLV/HLS(m3u8)/DASH/RTSP。
 *
 * Intent extra:
 *   videoUrl: 视频地址
 *   videoTitle: 标题（可选）
 */
public class ExoPlayerActivity extends Activity {

    private static final String TAG = "ExoPlayerActivity";

    private StyledPlayerView playerView;
    private ExoPlayer player;
    private TextView tvTitle, tvPos, tvDur, tvStatus;
    private SeekBar seekBar;
    private ImageButton btnPlayPause, btnBack, btnFF, btnRew, btnFull;
    private LinearLayout controls;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean mControlsShowing = true;
    private Runnable hideControlsTask;
    private String videoUrl;
    private String videoTitle;

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
        btnBack = findViewById(R.id.btn_back);
        btnFF = findViewById(R.id.btn_ff);
        btnRew = findViewById(R.id.btn_rew);
        btnFull = findViewById(R.id.btn_fullscreen);
        controls = findViewById(R.id.controls);

        videoUrl = getIntent().getStringExtra("videoUrl");
        videoTitle = getIntent().getStringExtra("videoTitle");
        if (videoUrl == null && getIntent().getData() != null) {
            videoUrl = getIntent().getData().toString();
        }
        if (videoUrl == null) {
            videoUrl = getIntent().getStringExtra("url");
        }
        if (videoTitle == null) videoTitle = "投屏播放";

        // 兼容 url 编码
        try { videoUrl = URLDecoder.decode(videoUrl, "UTF-8"); } catch (Exception ignore) {}

        tvTitle.setText(videoTitle);
        tvStatus.setText("准备中...");

        btnBack.setOnClickListener(v -> finish());
        btnPlayPause.setOnClickListener(v -> togglePlay());
        btnFF.setOnClickListener(v -> seekForward());
        btnRew.setOnClickListener(v -> seekBack());
        btnFull.setOnClickListener(v -> toggleControls());
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            boolean seeking = false;
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && player != null) tvPos.setText(formatTime(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (player != null) player.seekTo(seekBar.getProgress());
                seeking = false;
            }
        });

        hideControlsTask = () -> showControls(false);
        preparePlayer();
        showControls(true);
    }

    private void preparePlayer() {
        try {
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);

            String userAgent = AppConfig.getInstance(this).getPlayerUserAgent();

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
            if ("hls".equalsIgnoreCase(type)
                    || last.endsWith(".m3u8") || q.contains("m3u8")) {
                mediaSource = new HlsMediaSource.Factory(dsFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            } else if ("dash".equalsIgnoreCase(type) || last.endsWith(".mpd")) {
                mediaSource = new DashMediaSource.Factory(dsFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            } else if ("rtsp".equalsIgnoreCase(type)
                    || "rtsp".equalsIgnoreCase(uri.getScheme())) {
                mediaSource = new RtspMediaSource.Factory()
                        .createMediaSource(MediaItem.fromUri(uri));
            } else {
                mediaSource = new ProgressiveMediaSource.Factory(dsFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            }

            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    switch (state) {
                        case Player.STATE_BUFFERING:
                            tvStatus.setText("缓冲中..."); break;
                        case Player.STATE_READY:
                            tvStatus.setText("");
                            updateSeekbar();
                            break;
                        case Player.STATE_ENDED:
                            tvStatus.setText("播放结束");
                            finish();
                            break;
                        case Player.STATE_IDLE:
                            break;
                    }
                }
                @Override public void onIsPlayingChanged(boolean isPlaying) {
                    btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                    if (!isPlaying && player != null && player.getPlaybackState() != Player.STATE_ENDED) {
                        btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
                    }
                }
                @Override public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "播放器错误", error);
                    tvStatus.setText("播放失败：" + error.getCauseName());
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
        if (player.isPlaying()) player.pause();
        else player.play();
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
        if (player == null) return;
        long dur = player.getDuration();
        long pos = player.getCurrentPosition();
        if (dur > 0 && dur != C.TIME_UNSET) {
            seekBar.setMax((int) dur);
            seekBar.setProgress((int) pos);
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
        controls.setVisibility(show ? View.VISIBLE : View.GONE);
        mControlsShowing = show;
        handler.removeCallbacks(hideControlsTask);
        if (show && player != null && player.isPlaying()) {
            handler.postDelayed(hideControlsTask, 4000);
        }
    }

    private void toggleControls() { showControls(!mControlsShowing); }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
                toggleControls();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (mControlsShowing) { seekBack(); return true; }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (mControlsShowing) { seekForward(); return true; }
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                togglePlay(); return true;
            case KeyEvent.KEYCODE_BACK:
                if (mControlsShowing && controls.getVisibility() != View.VISIBLE) {
                    finish(); return true;
                }
                showControls(false);
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && Util.SDK_INT <= 23) player.pause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null && Util.SDK_INT > 23) player.pause();
    }
}
