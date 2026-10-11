package cn.cloudhs.tvremoteime.server;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.Map;

import cn.cloudhs.tvremoteime.Environment;
import cn.cloudhs.tvremoteime.RootUtil;
import fi.iki.elonen.NanoHTTPD;

/**
 * 其它 POST 请求处理：缓存清理 + 远程打开电视端系统设置页。
 */
public class OtherPostRequestProcesser implements RequestProcesser {
    private Context context;

    public OtherPostRequestProcesser(Context context){
        this.context = context;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        if(session.getMethod() == NanoHTTPD.Method.POST){
            switch (fileName) {
                case "/clearCache":
                case "/imeSettings":
                case "/overlaySettings":
                case "/rootSetIme":
                case "/adbConnect":
                    return true;
            }
        }
        return false;
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        switch (fileName) {
            case "/clearCache":
                RemoteServerFileManager.clearAllFiles();
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
            case "/imeSettings":
                // 远程打开电视端「输入法设置」页，用户用遥控器即可选择默认输入法
                return openSettingsResponse(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            case "/overlaySettings":
                // 远程打开电视端「显示在其他应用上层」授权页（触摸板光标需要）
                return openSettingsResponse(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            case "/rootSetIme":
                // ROOT 设备一键把自己设为默认输入法（免遥控器操作）
                JSONObject r = new JSONObject();
                boolean ok = false;
                String msg;
                if (RootUtil.isRootAvailableCached()) {
                    ok = RootUtil.setAsDefaultIME(context);
                    msg = ok ? "已通过 ROOT 设为默认输入法" : "ROOT 执行失败，请检查 su 授权";
                } else {
                    msg = "设备未 ROOT，请改用「打开输入法设置」手动选择";
                }
                try {
                    r.put("ok", ok);
                    r.put("msg", msg);
                } catch (Exception ignored) {}
                return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK, r.toString());
            case "/adbConnect":
                // 主动发起/重试 ADB 连接（远程输入与全局按键的可靠通道）。
                // 连接是异步的，返回"已发起"，前端 1s 后查 /api/status 看结果。
                return adbConnectResponse();
            default:
                return RemoteServer.createPlainTextResponse(
                        NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        }
    }

    private NanoHTTPD.Response adbConnectResponse() {
        JSONObject r = new JSONObject();
        boolean started = false;
        String msg;
        try {
            cn.cloudhs.tvremoteime.adb.AdbHelper.createInstance();
            started = cn.cloudhs.tvremoteime.adb.AdbHelper.initService(context);
            cn.cloudhs.tvremoteime.adb.AdbHelper h = cn.cloudhs.tvremoteime.adb.AdbHelper.getInstance();
            boolean connected = h != null && h.isConnected();
            msg = connected ? "ADB 已连接"
                    : (started ? "已发起连接（需电视端开发者选项开启 USB/网络调试）" : "ADB 服务启动失败");
        } catch (Throwable t) {
            msg = "ADB 连接异常：" + t.getMessage();
        }
        try {
            r.put("ok", started);
            r.put("msg", msg);
        } catch (Exception ignored) {}
        return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK, r.toString());
    }

    /** 从服务进程尝试拉起设置页；Android 10+ 有后台启动限制，失败时在电视端 Toast 引导 */
    private NanoHTTPD.Response openSettingsResponse(Intent intent) {
        JSONObject r = new JSONObject();
        boolean ok = false;
        String msg;
        try {
            context.startActivity(intent);
            ok = true;
            msg = "已在电视端打开设置页";
        } catch (Throwable t) {
            msg = "无法远程打开设置页，请在电视上打开「云视远程输入法」App 操作";
            try {
                Environment.toastInHandler(context, "请在电视端打开「云视远程输入法」App 完成设置");
            } catch (Throwable ignored) {}
        }
        try {
            r.put("ok", ok);
            r.put("msg", msg);
        } catch (Exception ignored) {}
        return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK, r.toString());
    }
}
