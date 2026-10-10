package cn.cloudhs.tvremoteime.server;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

import org.json.JSONObject;

import java.io.File;
import java.util.Map;

import cn.cloudhs.tvremoteime.AppConfig;
import cn.cloudhs.tvremoteime.AppPackagesHelper;
import cn.cloudhs.tvremoteime.AutoUpdateManager;
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
