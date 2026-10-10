package cn.cloudhs.tvremoteime;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import cn.cloudhs.tvremoteime.adb.AdbHelper;

/**
 */

public class IMEServiceBroadCastReceiver extends BroadcastReceiver {
    private final String TAG = "IMEServiceBCR";
    private final String ACTION_BOOT = "android.intent.action.BOOT_COMPLETED";
    private final String MEDIA_MOUNTED = "android.intent.action.MEDIA_MOUNTED";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "receive msg:" + intent.getAction());
       if (ACTION_BOOT.equals(intent.getAction()) ||
               MEDIA_MOUNTED.equals(intent.getAction())) {
           if(!Environment.isDefaultIME(context)) {
               Log.d(TAG, "startService.....");
               // Android 5.0+ 禁止隐式 Intent 启动 Service，改为显式 ComponentName
               Intent svc = new Intent();
               svc.setComponent(new ComponentName(context, IMEService.class));
               context.startService(svc);
               if(AdbHelper.getInstance() == null) AdbHelper.createInstance();
           }
       }
    }
}
