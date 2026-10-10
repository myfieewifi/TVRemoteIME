package cn.cloudhs.tvremoteime.server;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import java.net.URLDecoder;
import java.util.Map;

import cn.cloudhs.tvremoteime.AppConfig;
import cn.cloudhs.tvremoteime.AppPackagesHelper;
import cn.cloudhs.tvremoteime.IMEService;
import cn.cloudhs.tvremoteime.VideoPlayHelper;
import cn.cloudhs.tvremoteime.player.ExoPlayerActivity;
import fi.iki.elonen.NanoHTTPD;
import player.settings.GlobalSettings;

/**
 * 投屏播放接口。默认使用新版 ExoPlayer 内核，useSystem/useIJK=true 时走老逻辑。
 */
public class PlayRequestProcesser implements RequestProcesser {
    private static final String TAG = "PlayRequestProcesser";
    private final Context context;

    public PlayRequestProcesser(Context context){
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        if(session.getMethod() == NanoHTTPD.Method.POST){
            switch (fileName) {
                case "/play":
                case "/playStop":
                case "/changePlayFFI":
                    return true;
            }
        }
        return false;
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        switch (fileName) {
            case "/play": {
                String url = params.get("playUrl");
                if (TextUtils.isEmpty(url)) url = params.get("url");
                String title = params.get("title");
                if (TextUtils.isEmpty(title)) title = "投屏播放";
                if (TextUtils.isEmpty(url)) {
                    return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                            "{\"ok\":false,\"msg\":\"missing playUrl\"}");
                }
                // 注意：NanoHTTPD 的 decodeParms 已对查询参数做过一次百分号解码，
                // 这里不能再 URLDecoder.decode，否则会把 base64 token 里的 "+" 解成空格、
                // 或对残留 %xx 二次解码而损坏投屏地址（含签名 token 的 URL 会播不了）。

                // 【内存优化】收到投屏请求时才懒启动 DLNA 服务，避免输入法常驻占内存
                IMEService.ensureDLNAStarted(context);

                boolean useSystem = "true".equalsIgnoreCase(params.get("useSystem"));
                boolean useIJK    = "true".equalsIgnoreCase(params.get("useIJK")) || "true".equalsIgnoreCase(params.get("useXl"));
                boolean useExo    = "true".equalsIgnoreCase(params.get("useExo"));

                AppConfig config = AppConfig.getInstance(context);
                // 请求未显式指定内核时，使用持久化的默认内核（Web 端「投屏」页可切换）
                if (!useSystem && !useIJK && !useExo) {
                    useIJK = AppConfig.ENGINE_IJK.equals(config.getPlayerEngine());
                }

                if (useSystem) {
                    // 调系统播放器/旧逻辑
                    VideoPlayHelper.playUrl(this.context, url, 0, true);
                } else if (useIJK) {
                    // 旧 ijkplayer / 迅雷下载路径
                    VideoPlayHelper.playUrl(this.context, url, 0, false);
                } else {
                    // 默认走新版 ExoPlayer 播放器
                    try {
                        Intent i = new Intent(context, ExoPlayerActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        i.putExtra("videoUrl", url);
                        i.putExtra("videoTitle", title);
                        // 播放器运行在独立进程（:player），SharedPreferences 跨进程不保证实时同步，
                        // 故在主进程读取 UA 后通过 Intent 显式传递，避免播放器读到过期配置
                        i.putExtra("userAgent", config.getPlayerUserAgent());
                        context.startActivity(i);
                    } catch (Throwable t) {
                        Log.e(TAG, "启动 ExoPlayer 失败，退回旧播放器", t);
                        VideoPlayHelper.playUrl(this.context, url, 0, false);
                    }
                }
                return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK, "{\"ok\":true}");
            }
            case "/playStop":
                // 停止新播放器（通过广播）
                try {
                    Intent stop = new Intent("cn.cloudhs.tvremoteime.ACTION_STOP_PLAY");
                    // 限定包名，确保跨进程（播放器在 :player 独立进程）也能可靠收到
                    stop.setPackage(context.getPackageName());
                    context.sendBroadcast(stop);
                } catch (Exception ignored) {}
                try {
                    xllib.DownloadManager.instance().taskInstance().stopTask();
                } catch (Throwable ignored) {}
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
            case "/changePlayFFI":
                if(!TextUtils.isEmpty(params.get("speedInterval"))){
                    try{
                        GlobalSettings.FastForwardInterval = Integer.valueOf(params.get("speedInterval")) * 1000;
                    }catch (NumberFormatException ignored) {
                    }
                }
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
            default:
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        }
    }
}
