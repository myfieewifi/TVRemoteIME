package cn.cloudhs.tvremoteime.player;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import cn.cloudhs.tvremoteime.AppConfig;

/**
 * 云视远程输入法 - DLNA 播放桥接服务
 *
 * 手机端视频 APP（腾讯视频/哔哩哔哩等）通过 DLNA/UPnP 协议向本设备下发
 * SetAVTransportURI 后，DroidDLNA 模块会启动本服务，由本服务在输入法主进程中
 * 拉起播放器 Activity。
 *
 * 之所以单独放在 IMEService 模块，是为了让 DroidDLNA 模块无需依赖 IMEService 模块
 * （避免两个 library 互相依赖），DroidDLNA 只需用字符串形式的显式组件名启动本服务即可。
 */
public class DlnaPlayBridgeService extends Service {
    private static final String TAG = "DlnaPlayBridge";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String uri = intent.getStringExtra("playURI");
            String name = intent.getStringExtra("name");
            String type = intent.getStringExtra("type");
            launchPlayer(uri, name, type);
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private void launchPlayer(String uri, String name, String type) {
        if (uri == null || uri.trim().isEmpty()) {
            Log.w(TAG, "空播放地址，忽略");
            return;
        }
        try {
            Intent i = new Intent(this, ExoPlayerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra("videoUrl", uri);
            i.putExtra("videoTitle", name == null ? "DLNA 投屏" : name);
            i.putExtra("fromDLNA", true);
            // 播放器运行在独立进程，UA 需由主进程读取后随 Intent 传递
            i.putExtra("userAgent", AppConfig.getInstance(this).getPlayerUserAgent());
            startActivity(i);
            Log.i(TAG, "DLNA 拉起播放器: " + uri);
        } catch (Throwable t) {
            Log.e(TAG, "拉起播放器失败", t);
        }
    }
}
