package cn.cloudhs.tvremoteime.server;

import android.content.Context;

import org.json.JSONObject;

import java.util.Map;

import cn.cloudhs.tvremoteime.AppPackagesHelper;
import cn.cloudhs.tvremoteime.RootUtil;
import fi.iki.elonen.NanoHTTPD;

/**
 * 应用管理：列表 / 启动 / 卸载 / 启动系统应用
 */

public class AppRequestProcesser implements RequestProcesser {
    private Context context;

    public AppRequestProcesser(Context context){
        this.context = context;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        if(session.getMethod() == NanoHTTPD.Method.POST){
            switch (fileName) {
                case "/apps":
                case "/uninstall":
                case "/run":
                case "/runSystem":
                    return true;
            }
        }
        return false;
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
            switch (fileName) {
                case "/apps":
                    return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK,
                            AppPackagesHelper.getQueryAppInfoJsonString(this.context, "true".equals(params.get("system"))));
                case "/uninstall":
                    // 确认动作由 Web 端 HTML 页完成后才发请求到这里：
                    // - 有 ROOT：静默卸载（系统应用/用户应用均可）
                    // - 无 ROOT：用户应用走系统卸载弹窗（需遥控器在电视端点确认，系统机制绕不开）；
                    //            系统应用无法卸载，明确返回失败原因
                    return uninstall(params.get("packageName"));
                case "/run":
                    if (params.get("packageName") != null) {
                        AppPackagesHelper.runPackage(params.get("packageName"), this.context);
                    }
                    return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
                case "/runSystem":
                    if (params.get("packageName") != null) {
                        AppPackagesHelper.runSystemPackage(params.get("packageName"), this.context);
                    }
                    return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
                default:
                    return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
            }
    }

    private NanoHTTPD.Response uninstall(String packageName) {
        JSONObject r = new JSONObject();
        boolean ok = false;
        String msg;
        if (packageName == null || packageName.trim().isEmpty()) {
            msg = "缺少 packageName";
        } else if (AppPackagesHelper.getApplicationInfo(packageName, this.context) == null) {
            msg = "应用不存在（可能已被卸载）";
        } else if (RootUtil.isRootAvailableCached()) {
            boolean sys = AppPackagesHelper.isSystemPackage(packageName, this.context);
            ok = AppPackagesHelper.uninstallPackageAsRoot(packageName, sys);
            msg = ok
                    ? (sys ? "已通过 ROOT 静默卸载系统应用" : "已通过 ROOT 静默卸载")
                    : "ROOT 卸载失败，请检查 su 授权";
        } else if (AppPackagesHelper.isSystemPackage(packageName, this.context)) {
            msg = "系统应用卸载需要 ROOT 权限";
        } else {
            // 无 ROOT 的用户应用：拉起系统卸载确认页（需遥控器确认）
            AppPackagesHelper.uninstallPackage(packageName, this.context);
            ok = true;
            msg = "无 ROOT：请在电视端用遥控器确认系统卸载弹窗";
        }
        try {
            r.put("ok", ok);
            r.put("msg", msg);
        } catch (Exception ignored) {}
        return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK, r.toString());
    }
}
