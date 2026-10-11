package cn.cloudhs.tvremoteime;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManager;

import cn.cloudhs.tvremoteime.http.HTTPGet;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * 云视远程输入法 - 版本更新管理器
 * - 每个新版本只弹一次提示（按版本号记录"已忽略"）
 * - 支持手动检查更新（App 端和前端 Web 端共用一个远程 version.json）
 * - 强制更新 forced=true 时，关闭按钮不可用
 */
public class AutoUpdateManager {
    private static final String TAG = "AutoUpdateManager";

    /** 你的 GitHub 仓库 version.json 地址（改成自己仓库的 raw 链接即可） */
    private static String VERSION_URL =
            "https://raw.githubusercontent.com/myfieewifi/TVRemoteIME/master/released/version.json";

    /** 远程最新版本信息缓存（启动后拉取一次，/api/version 直接返回） */
    private static volatile JSONObject sCachedRemoteVersion = null;
    private static volatile long sLastFetchTime = 0;
    private static final long CACHE_TTL_MS = 10 * 60 * 1000; // 10 分钟缓存

    private final Context context;
    private final Handler uiHandler;
    private final File localFile;
    private boolean hasPromptedThisSession = false; // 本次启动会话内只弹一次

    public AutoUpdateManager(Context context) {
        this.context = context.getApplicationContext();
        this.uiHandler = new Handler(Looper.getMainLooper());
        this.localFile = new File(context.getExternalCacheDir(),
                context.getString(R.string.app_name) + ".apk");
    }

    /** 应用启动后延迟 30 秒再检查，避免抢冷启动资源 */
    public void checkOnStartDelayed() {
        if (!AppConfig.getInstance(context).isCheckUpdateOnStart()) return;
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkInBackground(false);
            }
        }, 30_000);
    }

    /** AppSettings/主界面点"检查更新"按钮时调用（强制弹窗，不记 dismissed） */
    public void checkManually() {
        checkInBackground(true);
    }

    /** 供 HTTP /api/version 返回最新版本信息 */
    public static JSONObject getCachedRemoteVersion(Context context) {
        if (sCachedRemoteVersion == null ||
                System.currentTimeMillis() - sLastFetchTime > CACHE_TTL_MS) {
            // 只返回缓存、后台线程刷新：避免国内电视访问 GitHub 被丢包时把
            // HTTP 工作线程同步阻塞十几秒，前端"检查更新"长时间无响应。
            // 先把时间戳前移，防止并发请求触发多次刷新。
            sLastFetchTime = System.currentTimeMillis();
            final Context appCtx = context.getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String data = HTTPGet.readString(VERSION_URL);
                        if (!TextUtils.isEmpty(data)) {
                            sCachedRemoteVersion = new JSONObject(data);
                            sLastFetchTime = System.currentTimeMillis();
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "background refresh version failed", e);
                    }
                }
            }).start();
        }
        // 返回给前端的对象：合并本地版本信息，前端不关心缓存状态
        JSONObject result = new JSONObject();
        try {
            int localCode = AppPackagesHelper.getCurrentPackageVersionCode(context);
            String localName = AppPackagesHelper.getCurrentPackageVersion(context);
            result.put("localVersionCode", localCode);
            result.put("localVersionName", localName);
            result.put("versionCode", localCode);
            result.put("versionName", localName);
            result.put("hasUpdate", false);
            result.put("message", "当前已是最新版本");
            result.put("updateUrl", "");
            result.put("forced", false);
            if (sCachedRemoteVersion != null) {
                if (sCachedRemoteVersion.has("versionCode")) {
                    int remoteCode = sCachedRemoteVersion.getInt("versionCode");
                    if (remoteCode > localCode) {
                        result.put("hasUpdate", true);
                        result.put("versionCode", remoteCode);
                    }
                }
                if (sCachedRemoteVersion.has("versionName")) {
                    result.put("versionName", sCachedRemoteVersion.getString("versionName"));
                }
                if (sCachedRemoteVersion.has("message")) {
                    result.put("message", sCachedRemoteVersion.getString("message"));
                }
                if (sCachedRemoteVersion.has("installAPK")) {
                    result.put("updateUrl", sCachedRemoteVersion.getString("installAPK"));
                }
                if (sCachedRemoteVersion.has("forced")) {
                    result.put("forced", sCachedRemoteVersion.getBoolean("forced"));
                }
            }
        } catch (JSONException ignored) {}
        return result;
    }

    // ----------------------------------------------------------------
    // 内部实现
    // ----------------------------------------------------------------

    private void checkInBackground(final boolean manual) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject remote = fetchServerVersion();
                    if (remote == null) {
                        if (manual) {
                            uiHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    showToast("检查更新失败，请检查网络");
                                }
                            });
                        }
                        return;
                    }
                    sCachedRemoteVersion = remote;
                    sLastFetchTime = System.currentTimeMillis();

                    final int localCode = AppPackagesHelper.getCurrentPackageVersionCode(context);
                    final int remoteCode = remote.has("versionCode")
                            ? remote.getInt("versionCode") : localCode;
                    final boolean forced = remote.has("forced")
                            && remote.getBoolean("forced");
                    final int dismissed = AppConfig.getInstance(context).getDismissedUpdateCode();

                    boolean needPrompt = remoteCode > localCode
                            && (manual || forced || remoteCode > dismissed)
                            && !hasPromptedThisSession;

                    if (!needPrompt) {
                        if (manual) {
                            uiHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    showToast("当前已是最新版本 v" +
                                            AppPackagesHelper.getCurrentPackageVersion(context));
                                }
                            });
                        }
                        return;
                    }

                    final boolean shouldDownload = forced || needDownload(remote);
                    if (shouldDownload) {
                        final boolean ok = downloadApk(remote);
                        if (!ok && !manual) return;
                    }

                    hasPromptedThisSession = true;
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            showUpdateDialog(remote, remoteCode, forced);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "checkInBackground", e);
                }
            }
        }).start();
    }

    private JSONObject fetchServerVersion() {
        try {
            String data = HTTPGet.readString(VERSION_URL);
            if (TextUtils.isEmpty(data)) return null;
            return new JSONObject(data);
        } catch (Exception e) {
            Log.e(TAG, "fetchServerVersion", e);
            return null;
        }
    }

    private boolean needDownload(JSONObject v) {
        try {
            if (!localFile.exists()) return true;
            PackageInfo info = context.getPackageManager()
                    .getPackageArchiveInfo(localFile.getAbsolutePath(), 0);
            if (info == null) return true;
            return info.versionCode < v.getInt("versionCode");
        } catch (Exception e) {
            return true;
        }
    }

    private boolean downloadApk(JSONObject v) {
        try {
            String url = v.getString("installAPK");
            if (TextUtils.isEmpty(url)) return false;
            return HTTPGet.downloadFile(url, localFile);
        } catch (Exception e) {
            Log.e(TAG, "downloadApk", e);
            return false;
        }
    }

    private void showUpdateDialog(final JSONObject v, final int remoteCode, boolean forced) {
        String versionName = v.optString("versionName", "");
        String message = v.optString("message", "");
        StringBuilder msg = new StringBuilder();
        msg.append("发现新版本 v").append(versionName).append("\n");
        if (!TextUtils.isEmpty(message)) {
            msg.append("\n更新内容：\n").append(message);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("版本更新").setIcon(R.mipmap.ic_launcher);
        builder.setMessage(msg.toString());
        builder.setCancelable(!forced);

        if (!forced) {
            builder.setNeutralButton("本次忽略", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // 记录已忽略此版本号，下次不再弹
                    AppConfig.getInstance(context).setDismissedUpdateCode(remoteCode);
                    dialog.dismiss();
                }
            });
        }
        builder.setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                AppPackagesHelper.installPackage(localFile, context);
                dialog.dismiss();
            }
        });
        if (!forced) {
            builder.setNegativeButton("稍后再说", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            });
        }
        try {
            AlertDialog dialog = builder.create();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            }
            dialog.setCanceledOnTouchOutside(!forced);
            dialog.show();
        } catch (Exception ex) {
            AppPackagesHelper.installPackage(localFile, context);
        }
    }

    private void showToast(String msg) {
        try {
            android.widget.Toast.makeText(context, msg,
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }
}
