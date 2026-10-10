package cn.cloudhs.tvremoteime.server;

import android.content.Context;
import android.util.Log;

import cn.cloudhs.tvremoteime.IMEService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 处理 /tv.txt 的读写。
 *
 * 默认的直播源模板放在 assets/tv.txt（不使用 res/raw，避免开启资源压缩
 * （shrinkResources）时该资源被判定为未引用而裁剪，进而导致 R.raw 类里
 * 根本不生成对应字段）。首次启动时复制到应用数据目录，之后由用户通过
 * 网页端或接口覆盖更新。
 */
public class TVRequestProcesser implements RequestProcesser {

    /** 内置默认直播源模板（assets 目录下） */
    private static final String DEFAULT_TV_ASSET = "tv.txt";

    private Context context;
    private File tvFile = new File(RemoteServerFileManager.baseDir, "tv.txt");

    public TVRequestProcesser(Context context) {
        this.context = context;
        initTVData();
    }

    private void initTVData() {
        if (!tvFile.exists()) {
            InputStream ins = null;
            FileOutputStream out = null;
            try {
                ins = openDefaultTV();
                out = new FileOutputStream(tvFile);
                byte[] b = new byte[1024];
                int n;
                while ((n = ins.read(b)) != -1) {
                    out.write(b, 0, n);
                }
            } catch (Exception e) {
                Log.e(IMEService.TAG, "init tv data", e);
            } finally {
                try { if (ins != null) ins.close(); } catch (IOException ignored) {}
                try { if (out != null) out.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** 打开内置的默认直播源模板 */
    private InputStream openDefaultTV() throws IOException {
        return context.getAssets().open(DEFAULT_TV_ASSET);
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return "/tv.txt".equalsIgnoreCase(fileName);
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        if (session.getMethod() == NanoHTTPD.Method.POST) {
            //edit
            String text = params.get("text");
            try {
                OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(tvFile), "utf-8");
                out.write(text == null ? "" : text);
                out.close();
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "ok");
            } catch (IOException e) {
                Log.e(IMEService.TAG, "POST /tv.txt", e);
            }
            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "fail");
        } else {
            try {
                InputStream inputStream = tvFile.exists() ? new FileInputStream(tvFile) : openDefaultTV();
                return RemoteServer.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain; charset=utf-8", inputStream, (long) inputStream.available());
            } catch (IOException ioex) {
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: " + ioex.getMessage());
            }
        }
    }
}
