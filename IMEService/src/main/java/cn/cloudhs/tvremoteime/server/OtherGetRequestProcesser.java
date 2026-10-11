package cn.cloudhs.tvremoteime.server;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.util.Map;

import cn.cloudhs.tvremoteime.AppConfig;
import cn.cloudhs.tvremoteime.AppPackagesHelper;
import cn.cloudhs.tvremoteime.AutoUpdateManager;
import cn.cloudhs.tvremoteime.RootUtil;
import fi.iki.elonen.NanoHTTPD;

/**
 * 云视远程输入法 - 其它 GET 请求处理
 * 提供版本查询、存储查询、配置读取、更新检查等接口
 */
public class OtherGetRequestProcesser implements RequestProcesser {
    private final Context context;

    public OtherGetRequestProcesser(Context context) {
        this.context = context;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        if (session.getMethod() != NanoHTTPD.Method.GET) return false;
        switch (fileName) {
            case "/version":
            case "/sdcard_stat":
            case "/api/version":
            case "/api/config":
            case "/api/status":
                return true;
        }
        return false;
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session,
                                         String fileName,
                                         Map<String, String> params,
                                         Map<String, String> files) {
        switch (fileName) {
            case "/version":
                // 兼容旧接口
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,
                        AppPackagesHelper.getCurrentPackageVersion(context));
            case "/api/version":
                // 前端检查更新使用的结构化接口
                JSONObject info = AutoUpdateManager.getCachedRemoteVersion(context);
                return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK,
                        info.toString());
            case "/api/status":
                return getStatusResponse();
            case "/api/config":
                JSONObject cfg = AppConfig.getInstance(context).toJson();
                return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK,
                        cfg.toString());
            case "/sdcard_stat":
                return getSDCardStatResponse();
            default:
                return RemoteServer.createPlainTextResponse(
                        NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        }
    }

    /**
     * /api/status：电视端能力诊断（前端据此显示可操作的状态提示）。
     * - imeEnabled / imeDefault：远程文字输入能否生效的关键
     * - overlayGranted：触摸板光标浮层能否显示的关键
     * - rootAvailable / adbConnected：注入与 ROOT 卸载的兜底通道
     * - ijkAvailable：本安装包是否含 IJKPlayer 内核
     */
    private NanoHTTPD.Response getStatusResponse() {
        JSONObject st = new JSONObject();
        try {
            // 注意：本文件 import 的 Environment 是 android.os.Environment，
            // 应用自身的 Environment 工具类必须用全限定名
            st.put("imeEnabled", cn.cloudhs.tvremoteime.Environment.isEnableIME(context));
            st.put("imeDefault", cn.cloudhs.tvremoteime.Environment.isDefaultIME(context));
            boolean overlay;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                overlay = android.provider.Settings.canDrawOverlays(context);
            } else {
                overlay = true;
            }
            st.put("overlayGranted", overlay);
            st.put("rootAvailable", RootUtil.isRootAvailableCached());
            st.put("ijkAvailable", AppConfig.isIjkAvailable());
            st.put("touchInjectOn",
                    cn.cloudhs.tvremoteime.input.TouchAccessibilityService.isReady());
            st.put("adbConnected",
                    cn.cloudhs.tvremoteime.adb.AdbHelper.getInstance() != null
                            && cn.cloudhs.tvremoteime.adb.AdbHelper.getInstance().isRunning());
        } catch (Exception e) {
            Log.w("TVRemoteIME", "build status failed", e);
        }
        return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK, st.toString());
    }

    private NanoHTTPD.Response getSDCardStatResponse() {
        File path = Environment.getExternalStorageDirectory();
        StatFs stat = new StatFs(path.getPath());
        long totalBytes, availableBytes;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            totalBytes = (long) stat.getBlockCount() * stat.getBlockSize();
            availableBytes = (long) stat.getAvailableBlocks() * stat.getBlockSize();
        } else {
            totalBytes = stat.getTotalBytes();
            availableBytes = stat.getAvailableBytes();
        }
        return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK,
                "{\"totalBytes\":" + totalBytes
                        + ", \"availableBytes\":" + availableBytes + "}");
    }
}
