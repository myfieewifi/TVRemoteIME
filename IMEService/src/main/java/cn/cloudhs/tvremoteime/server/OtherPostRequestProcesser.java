package cn.cloudhs.tvremoteime.server;

import android.content.Context;

import cn.cloudhs.tvremoteime.AppPackagesHelper;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
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
            default:
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        }
    }
}
