package cn.cloudhs.tvremoteime.server;

import android.content.Context;
import android.util.Log;

import java.util.Map;

import cn.cloudhs.tvremoteime.IMEService;
import cn.cloudhs.tvremoteime.input.TouchInjector;
import fi.iki.elonen.NanoHTTPD;

/**
 * 接收 Web 前端 /touch 接口请求并调用 TouchInjector。
 * 参数：
 *   action: move | down | up | click | longclick | scroll | home
 *   dx, dy : 相对位移（触摸板像素，move/scroll 用）
 */
public class TouchRequestProcesser implements RequestProcesser {
    private static final String TAG = "TouchRequestProcesser";
    private final Context context;
    private final RemoteServer remoteServer;
    private TouchInjector injector;

    public TouchRequestProcesser(Context context, RemoteServer remoteServer) {
        this.context = context.getApplicationContext();
        this.remoteServer = remoteServer;
    }

    private TouchInjector getInjector() {
        if (injector == null) {
            injector = new TouchInjector(context);
            // 使用持久化的默认灵敏度
            float sens = cn.cloudhs.tvremoteime.AppConfig.getInstance(context).getTouchSensitivity();
            injector.setSensitivity(sens);
        }
        return injector;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return session.getMethod() == NanoHTTPD.Method.POST && "/touch".equalsIgnoreCase(fileName);
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName,
                                         Map<String, String> params, Map<String, String> files) {
        String action = params.get("action");
        if (action == null) {
            return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "{\"ok\":false,\"msg\":\"missing action\"}");
        }
        float dx = 0f, dy = 0f;
        try {
            if (params.get("dx") != null) dx = Float.parseFloat(params.get("dx"));
            if (params.get("dy") != null) dy = Float.parseFloat(params.get("dy"));
        } catch (NumberFormatException ignored) {}

        String sensStr = params.get("sens");
        TouchInjector ti = getInjector();
        if (sensStr != null) {
            try { ti.setSensitivity(Float.parseFloat(sensStr)); } catch (Exception ignored) {}
        }

        boolean ok;
        try {
            ok = ti.sendTouch(action, dx, dy);
        } catch (Throwable t) {
            Log.e(TAG, "触摸处理异常", t);
            ok = false;
        }
        return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK,
                "{\"ok\":" + ok + ",\"available\":" + ti.isAvailable() + "}");
    }
}
