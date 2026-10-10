package cn.cloudhs.tvremoteime.server;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.util.Map;

import cn.cloudhs.tvremoteime.AppConfig;
import cn.cloudhs.tvremoteime.IMEService;
import fi.iki.elonen.NanoHTTPD;

/**
 * 云视远程输入法 - 配置更新 POST /api/config
 * 接收 JSON 字段更新用户配置，持久化到 SharedPreferences
 */
public class ConfigPostProcesser implements RequestProcesser {
    private static final String TAG = "ConfigPostProcesser";
    private final Context context;

    public ConfigPostProcesser(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return session.getMethod() == NanoHTTPD.Method.POST
                && "/api/config".equals(fileName);
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session,
                                         String fileName,
                                         Map<String, String> params,
                                         Map<String, String> files) {
        try {
            // RemoteServer.serveHttp 对所有 POST 已先执行 session.parseBody(files)，
            // 请求体已被完整读走（非表单的 JSON body 存于 files.get("postData")），
            // 此时再读 session.getInputStream() 只能得到 EOF，必须从 files 取值
            String bodyStr = files != null && files.get("postData") != null
                    ? files.get("postData").trim() : "";
            JSONObject json;
            if (bodyStr.isEmpty()) {
                // 兼容 form 表单提交
                json = new JSONObject();
                for (Map.Entry<String, String> e : params.entrySet()) {
                    String v = e.getValue();
                    if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
                        json.put(e.getKey(), Boolean.parseBoolean(v));
                    } else {
                        try {
                            if (v.contains(".")) json.put(e.getKey(), Float.parseFloat(v));
                            else json.put(e.getKey(), Integer.parseInt(v));
                        } catch (NumberFormatException nfe) {
                            json.put(e.getKey(), v);
                        }
                    }
                }
            } else {
                json = new JSONObject(bodyStr);
            }
            AppConfig.getInstance(context).applyFromJson(json);

            // 如果改了端口，提示重启服务才能生效（在 IMEService 内处理）
            if (json.has("serverPort")) {
                Log.i(IMEService.TAG, "serverPort changed, will take effect on restart");
            }
            JSONObject ok = new JSONObject();
            ok.put("ok", true);
            ok.put("config", AppConfig.getInstance(context).toJson());
            return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK, ok.toString());
        } catch (Exception e) {
            Log.e(TAG, "POST /api/config error", e);
            return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }
}
