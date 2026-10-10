package cn.cloudhs.tvremoteime.server;

import android.content.Context;

import cn.cloudhs.tvremoteime.AppPackagesHelper;

import java.io.ByteArrayInputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 */

public class AppIconRequestProcesser  implements RequestProcesser {
    private Context context;

    public AppIconRequestProcesser(Context context){
        this.context = context;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return session.getMethod() == NanoHTTPD.Method.GET && fileName.startsWith("/icon/");
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        String packageName = fileName.substring("/icon/".length());
        byte[] data = AppPackagesHelper.getAppIcon(packageName, this.context);
        if(data == null){
            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND,  "Not Found");
        }else{
            ByteArrayInputStream stream = new ByteArrayInputStream(data);
            return RemoteServer.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "image/png", stream, data.length);
        }
    }
}
