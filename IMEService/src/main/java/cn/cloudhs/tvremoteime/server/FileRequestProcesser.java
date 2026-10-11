package cn.cloudhs.tvremoteime.server;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import xllib.FileUtils;

/**
 */

public class FileRequestProcesser  implements RequestProcesser {
    private Context context;

    public FileRequestProcesser(Context context){
        this.context = context;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        if(session.getMethod() == NanoHTTPD.Method.GET){
            // 兼容两种路径：新前端用 /files?path=xxx，旧版用 /file/dir/xxx；以及 /file/download/xxx
            return fileName.equals("/files")
                    || fileName.startsWith("/file/dir/")
                    || fileName.startsWith("/file/download/");
        }
        else if(session.getMethod() == NanoHTTPD.Method.POST){
            switch (fileName) {
                case "/file/copy":
                case "/file/cut":
                case "/file/delete":
                case "/file/upload":
                    return true;
            }
        }
        return false;
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        if(session.getMethod() == NanoHTTPD.Method.GET){
            if(fileName.equals("/files")){
                // 新前端请求方式：GET /files?path=xxx
                String p = params != null ? params.get("path") : null;
                return responseDirData(p == null ? "" : p);
            }
            if(fileName.startsWith("/file/dir/")) {
                return responseDirData(fileName.substring("/file/dir/".length()));
            }else if(fileName.startsWith("/file/download/")){
                // 容忍 // 双重斜杠问题
                String rel = fileName.substring("/file/download/".length());
                while(rel.startsWith("/")) rel = rel.substring(1);
                return downloadFileData(rel);
            }
        }
        else if(session.getMethod() == NanoHTTPD.Method.POST) {
            String paths = params.get("paths");
            switch (fileName) {
                case "/file/copy":
                    if (!TextUtils.isEmpty(paths)) {
                        batchCopyFile(params.get("targetPath"), paths);
                    }
                    return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "ok");
                case "/file/cut":
                    if (!TextUtils.isEmpty(paths)) {
                        batchCutFile(params.get("targetPath"),paths);
                    }
                    return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "ok");
                case "/file/delete":
                    if (!TextUtils.isEmpty(paths)) {
                        batchDeleteFile(paths);
                    }
                    return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "ok");
                case "/file/upload":
                    return uploadFile(params, files);
            }
        }
        return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
    }

    private NanoHTTPD.Response responseDirData(String dirName) {
        try {
            dirName = java.net.URLDecoder.decode(dirName == null ? "" : dirName, "UTF-8");
        } catch (Exception ignore) {}
        // 规范化路径：去掉开头斜杠
        while(dirName.startsWith("/")) dirName = dirName.substring(1);
        File path = dirName.isEmpty()
                ? Environment.getExternalStorageDirectory()
                : new File(Environment.getExternalStorageDirectory(), dirName);
        String root = Environment.getExternalStorageDirectory().getPath();
        JSONArray dirs = new JSONArray();
        JSONArray files = new JSONArray();
        try {
            File[] subfiles = path.listFiles();
            if (subfiles == null) subfiles = new File[0];
            Arrays.sort(subfiles, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return f1.getName().toLowerCase().compareTo(f2.getName().toLowerCase());
                }
            });

            for (File file : subfiles) {
                if(file.isHidden()) continue;
                JSONObject item = new JSONObject();
                item.put("name", file.getName());
                String relPath = file.getPath().substring(root.length());
                item.put("path", relPath.isEmpty() ? "/" : relPath);
                item.put("fullPath", file.getPath().substring(root.length()));
                if (file.isDirectory()) {
                    dirs.put(item);
                }else {
                    item.put("size", file.length());
                    item.put("isMedia", FileUtils.isMediaFile(file.getName()));
                    files.put(item);
                }
            }

            JSONObject data = new JSONObject();
            if(!TextUtils.isEmpty(dirName)) {
                String parentRel = path.getParent() == null ? null : path.getParent().substring(root.length());
                if(!TextUtils.isEmpty(parentRel)) data.put("parent", parentRel);
            }
            data.put("dirs", dirs);
            data.put("files", files);
            return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK, data.toString());
        }catch (JSONException ex){
            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,  "SERVER INTERNAL ERROR: JSONException: " + ex.getMessage());
        }
    }

    private NanoHTTPD.Response downloadFileData(String fileName){
        File file = new File(Environment.getExternalStorageDirectory(), fileName);
        if(!file.exists()){
            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        }
        try{
            InputStream inputStream = new FileInputStream(file);
            return RemoteServer.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                    NanoHTTPD.getMimeTypeForFile(file.getName()) + "; charset=utf-8", inputStream, (long)inputStream.available());
        } catch (Exception e) {
            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        }
    }

    private NanoHTTPD.Response uploadFile(Map<String, String> params, Map<String, String> files){
        String uploadFileName  = params.get("file");
        String localFilename = files.get("file");
        boolean r = false;
        // 上传成功后供前端拉取播放的可下载 URL（/file/download/<相对 externalStorage 的路径>）
        String downloadUrl = "";
        if(!TextUtils.isEmpty(uploadFileName) && !TextUtils.isEmpty(localFilename)) {
            // multipart 上传时：uploadFileName 是原始文件名（Content-Disposition filename），
            // localFilename 是 NanoHTTPD 落地的临时路径（getName() 形如 NanoHTTPDxxxx，无扩展名）。
            // 必须用原始文件名保存，否则下载时 getMimeTypeForFile 推断不出类型，播放器无法播放。
            // 同时去掉路径分隔符，防止目录穿越。
            String safeName = new File(uploadFileName).getName().replaceAll("[/\\\\]", "");
            if (!safeName.isEmpty()) {
                // targetSdk 33 无 requestLegacyExternalStorage / MANAGE_EXTERNAL_STORAGE，
                // Environment.getExternalStorageDirectory() 根目录在 Android 11+ 不可写。
                // 改用应用专属外部存储 getExternalFilesDir，无需权限即可写；
                // 它仍在 externalStorage 之下，/file/download 用相对路径可达。
                File appFilesDir = context.getExternalFilesDir(null);
                if (appFilesDir != null) {
                    File saveFilename = new File(appFilesDir, safeName);
                    File localFile = new File(localFilename);
                    r = localFile.renameTo(saveFilename);
                    if (r) {
                        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
                        String abs = saveFilename.getAbsolutePath();
                        if (abs.startsWith(root)) {
                            String rel = abs.substring(root.length());
                            if (!rel.startsWith("/")) rel = "/" + rel;
                            downloadUrl = "/file/download" + rel;
                        }
                        // 移植原项目 UploadRequestProcesser 逻辑：检测到上传的是 APK
                        // 则自动拉起安装程序（电视端显示安装界面）
                        if (safeName.toLowerCase().endsWith(".apk")) {
                            try {
                                cn.cloudhs.tvremoteime.AppPackagesHelper
                                        .installPackage(saveFilename, context);
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        }
        // 前端正则 /(https?:\/\/[^\s"']+|\/file\/[^\s"']+)/ 从响应里提取播放地址填入 castUrl，
        // 必须返回 /file/download/... 才能命中，否则上传后无法接播
        return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK,
                "{\"success\":" + (r ? "true": "false") + ",\"url\":\"" + downloadUrl + "\"}");
    }

    private void batchDeleteFile(String paths){
        String[] pathData = paths.split("\\|");
        for(String p : pathData){
            if(!TextUtils.isEmpty(p)) {
                File path = new File(Environment.getExternalStorageDirectory(), p);
                RemoteServerFileManager.deleteFile(path);
            }
        }
    }
    private void batchCopyFile(String targetPath, String paths){
        File targetPathFile = new File(Environment.getExternalStorageDirectory(), targetPath);
        if(!targetPathFile.exists()) return;

        String[] pathData = paths.split("\\|");
        for(String p : pathData){
            if(!TextUtils.isEmpty(p)) {
                File source = new File(Environment.getExternalStorageDirectory(), p);
                RemoteServerFileManager.copyFile(source, targetPathFile);
            }
        }
    }
    private void batchCutFile(String targetPath, String paths){
        File targetPathFile = new File(Environment.getExternalStorageDirectory(), targetPath);
        if(!targetPathFile.exists()) return;

        String[] pathData = paths.split("\\|");
        for(String p : pathData){
            if(!TextUtils.isEmpty(p)) {
                File source = new File(Environment.getExternalStorageDirectory(), p);
                RemoteServerFileManager.cutFile(source, targetPathFile);
            }
        }
    }
}
