package cn.cloudhs.tvremoteime.server;

import android.content.Context;

import cn.cloudhs.tvremoteime.AppPackagesHelper;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 应用图标请求处理器
 * 兼容 /icon/<pkg>（旧路径）与 /appicon/<pkg>（新前端路径）两种格式
 */
public class AppIconRequestProcesser implements RequestProcesser {
    private final Context context;

    public AppIconRequestProcesser(Context context) {
        this.context = context;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return session.getMethod() == NanoHTTPD.Method.GET
                && (fileName.startsWith("/icon/") || fileName.startsWith("/appicon/"));
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName,
                                         Map<String, String> params, Map<String, String> files) {
        String packageName;
        if (fileName.startsWith("/appicon/")) {
            packageName = fileName.substring("/appicon/".length());
        } else {
            packageName = fileName.substring("/icon/".length());
        }
        try { packageName = URLDecoder.decode(packageName, "UTF-8"); } catch (Exception ignore) {}
        byte[] data = AppPackagesHelper.getAppIcon(packageName, this.context);
        if (data == null) {
            return RemoteServer.createPlainTextResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND, "Not Found");
        }
        ByteArrayInputStream stream = new ByteArrayInputStream(data);
        return RemoteServer.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "image/png", stream, data.length);
    }
}
