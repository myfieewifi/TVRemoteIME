package cn.cloudhs.tvremoteime.server;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import cn.cloudhs.tvremoteime.IMEService;
import fi.iki.elonen.NanoHTTPD;

/**
 * 从 assets/web/ 目录提供静态 Web 文件（新版前端使用）。
 */
public class AssetsRequestProcesser implements RequestProcesser {
    private final Context context;
    private static final String PREFIX = "/web";
    private static final String ASSETS_ROOT = "web";

    public AssetsRequestProcesser(Context context) {
        this.context = context;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        if (session.getMethod() != NanoHTTPD.Method.GET) return false;
        // 新版前端入口 "/" 或 "" 或 "/index.html" 走 assets
        return "/".equals(fileName) || fileName.isEmpty()
                || fileName.equalsIgnoreCase(PREFIX)
                || fileName.equalsIgnoreCase(PREFIX + "/")
                || fileName.equalsIgnoreCase("/index.html")
                || fileName.toLowerCase().startsWith(PREFIX + "/")
                || isNewWebAsset(fileName);
    }

    private boolean isNewWebAsset(String path) {
        String lc = path.toLowerCase();
        return lc.equals("/app.js") || lc.equals("/app.css")
                || lc.equals("/favicon.svg") || lc.equals("/favicon.png")
                || lc.equals("/manifest.webmanifest");
    }

    private String mimeOf(String name) {
        String lc = name.toLowerCase();
        if (lc.endsWith(".html")) return "text/html";
        if (lc.endsWith(".css"))  return "text/css";
        if (lc.endsWith(".js"))   return "application/javascript";
        if (lc.endsWith(".json")) return "application/json";
        if (lc.endsWith(".svg"))  return "image/svg+xml";
        if (lc.endsWith(".png"))  return "image/png";
        if (lc.endsWith(".jpg") || lc.endsWith(".jpeg")) return "image/jpeg";
        if (lc.endsWith(".ico"))  return "image/x-icon";
        if (lc.endsWith(".webmanifest")) return "application/manifest+json";
        return "application/octet-stream";
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName,
                                         Map<String, String> params, Map<String, String> files) {
        String rel;
        if (fileName == null || fileName.isEmpty() || "/".equals(fileName)
                || "/index.html".equalsIgnoreCase(fileName)
                || PREFIX.equalsIgnoreCase(fileName)
                || (PREFIX + "/").equalsIgnoreCase(fileName)) {
            rel = "index.html";
        } else if (fileName.startsWith(PREFIX + "/")) {
            rel = fileName.substring((PREFIX + "/").length());
        } else if (fileName.startsWith("/")) {
            rel = fileName.substring(1);
        } else {
            rel = fileName;
        }
        if (rel.isEmpty()) rel = "index.html";

        String assetsPath = ASSETS_ROOT + "/" + rel;
        try {
            InputStream is = context.getAssets().open(assetsPath);
            long len = is.available();
            String mime = mimeOf(rel);
            if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")) {
                mime += "; charset=utf-8";
            }
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, mime, is, len);
        } catch (IOException e) {
            Log.w(IMEService.TAG, "assets not found: " + assetsPath, e);
            // 前端 SPA 路由全部回到 index.html
            if (!"index.html".equals(rel)) {
                return doResponse(session, "/index.html", params, files);
            }
            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "404 Not Found");
        }
    }
}
