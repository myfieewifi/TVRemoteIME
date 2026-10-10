package cn.cloudhs.tvremoteime;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import cn.cloudhs.tvremoteime.upnp.DLNARendererService;

/**
 * DLNA 投屏工具类
 */
public class DLNAUtils {
    private static final String PREFS = "dlna_settings";

    public static void setDLNANameSuffix(Context context, String nameSuffix){
        SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREFS, 0);
        sp.edit().putString("nameSuffix", nameSuffix == null ? "" : nameSuffix).apply();
        // 修改名称后重启 DLNA 服务生效
        stopDLNAService(context);
        startDLNAService(context);
    }

    public static String getDLNANameSuffix(Context context){
        SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREFS, 0);
        return sp.getString("nameSuffix", "");
    }

    /** 启动 DLNA 投屏前台服务（DMR） */
    public static synchronized void startDLNAService(final Context context){
        if (context == null) return;
        Context app = context.getApplicationContext();
        // 前台服务自维护Upnp绑定，重复调用也没关系（onStartCommand START_STICKY）
        DLNARendererService.startMe(app);
    }

    /** 停止 DLNA 服务 */
    public static synchronized void stopDLNAService(Context context){
        if (context == null) return;
        try {
            context.getApplicationContext().stopService(
                new android.content.Intent(context.getApplicationContext(),
                    cn.cloudhs.tvremoteime.upnp.DLNARendererService.class));
        } catch (Throwable ignore) {}
    }
}
