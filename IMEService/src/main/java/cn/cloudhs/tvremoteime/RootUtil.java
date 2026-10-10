package cn.cloudhs.tvremoteime;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;

/**
 * ROOT 工具：检查设备是否已 root、以 root 执行命令、自动将本应用设为默认输入法
 * 仅当设备已被用户主动 root 且授权 su 才生效；不做任何提权漏洞利用。
 */
public class RootUtil {
    private static final String TAG = "RootUtil";

    /**
     * 检查设备是否已 root（不是请求权限，只是"能拿到 su"）
     */
    public static boolean isRootAvailable() {
        try {
            Process p = Runtime.getRuntime().exec("su -c id");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String s = r.readLine();
            p.waitFor();
            p.destroy();
            return s != null && s.contains("uid=0");
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 以 root 执行多条命令，成功返回 true
     */
    public static boolean execAsRoot(String... cmds) {
        Process p = null;
        DataOutputStream os = null;
        try {
            p = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(p.getOutputStream());
            for (String cmd : cmds) {
                os.writeBytes(cmd + "\n");
                os.flush();
            }
            os.writeBytes("exit\n");
            os.flush();
            int rc = p.waitFor();
            return rc == 0;
        } catch (Throwable e) {
            Log.e(TAG, "execAsRoot failed", e);
            return false;
        } finally {
            try { if (os != null) os.close(); } catch (Exception ignore) {}
            if (p != null) p.destroy();
        }
    }

    /**
     * 将云视远程输入法设置为默认输入法（需要 root 权限）
     * 写法兼容 Android 5+ 的 Secure Settings。
     */
    public static boolean setAsDefaultIME(Context ctx) {
        String pkg = ctx.getPackageName();
        // 查找自身 IME 的 ComponentName（AndroidManifest 里为 IMEService/.IMEService）
        String cn = pkg + "/.IMEService";
        try {
            cn = ctx.getPackageName() + "/" + IMEService.class.getName().replace(ctx.getPackageName(),"");
        } catch (Throwable ignore) {}
        // 1) 先启用该 IME（加到 enabled_input_methods 列表中）
        // 2) 设为 default_input_method
        return execAsRoot(
                "settings put secure enabled_input_methods " + cn,
                "settings put secure default_input_method " + cn,
                "ime enable " + cn,
                "ime set " + cn
        );
    }

    /**
     * 通过 root 启动前台服务、设置自启（开机后拉起服务）—— 仅做保活
     */
    public static boolean keepServiceAlive(Context ctx) {
        String pkg = ctx.getPackageName();
        // 触发启动（即便被系统杀了）
        return execAsRoot(
                "am startservice -n " + pkg + "/.IMEService 2>/dev/null",
                "am start-service " + pkg + "/.service.ForegroundGuardService 2>/dev/null"
        );
    }
}
