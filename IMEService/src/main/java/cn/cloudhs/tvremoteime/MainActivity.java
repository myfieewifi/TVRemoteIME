package cn.cloudhs.tvremoteime;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.Html;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import cn.cloudhs.tvremoteime.adb.AdbHelper;
import cn.cloudhs.tvremoteime.server.RemoteServer;

/**
 * 云视远程输入法 - 主界面
 * 提供服务地址、输入法状态、快捷操作
 */
public class MainActivity extends android.app.Activity implements View.OnClickListener {

    private ImageView qrCodeImage;
    private TextView tvAddress, tvVersion, tvEnabledStatus, tvDefaultStatus;
    private TextView tvEnabledIcon, tvDefaultIcon;
    private Button btnCopy, btnUseIME, btnSetIME, btnStartService, btnCheckUpdate, btnAbout, btnRootSet, btnAdbGuide;
    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 记录全局 Application Context，供无入参的静态工具方法兜底使用
        Environment.context = getApplicationContext();
        Environment.initToastHandler();
        setContentView(R.layout.activity_main);

        qrCodeImage = (ImageView) findViewById(R.id.qrCode);
        tvAddress = (TextView) findViewById(R.id.tvAddress);
        tvVersion = (TextView) findViewById(R.id.tvVersion);
        tvEnabledStatus = (TextView) findViewById(R.id.tvEnabledStatus);
        tvDefaultStatus = (TextView) findViewById(R.id.tvDefaultStatus);
        tvEnabledIcon = (TextView) findViewById(R.id.tvEnabledIcon);
        tvDefaultIcon = (TextView) findViewById(R.id.tvDefaultIcon);

        btnCopy = (Button) findViewById(R.id.btnCopy);
        btnUseIME = (Button) findViewById(R.id.btnUseIME);
        btnSetIME = (Button) findViewById(R.id.btnSetIME);
        btnStartService = (Button) findViewById(R.id.btnStartService);
        btnCheckUpdate = (Button) findViewById(R.id.btnCheckUpdate);
        btnAbout = (Button) findViewById(R.id.btnAbout);
        btnRootSet = (Button) findViewById(R.id.btnRootSet);
        btnAdbGuide = (Button) findViewById(R.id.btnAdbGuide);

        findViewById(R.id.tvAddress).setOnClickListener(this);
        btnCopy.setOnClickListener(this);
        btnUseIME.setOnClickListener(this);
        btnSetIME.setOnClickListener(this);
        btnStartService.setOnClickListener(this);
        btnCheckUpdate.setOnClickListener(this);
        btnAbout.setOnClickListener(this);
        btnRootSet.setOnClickListener(this);
        btnAdbGuide.setOnClickListener(this);
        findViewById(R.id.rowEnabled).setOnClickListener(this);
        findViewById(R.id.rowDefault).setOnClickListener(this);

        String vn = AppPackagesHelper.getCurrentPackageVersion(this);
        tvVersion.setText("v" + vn);

        refreshAddress();
        refreshStatus();

        // 打开 App 时顺带拉起 DLNA 投屏服务，手机视频 APP 才能搜索到本设备
        try {
            if (AppConfig.getInstance(this).isAutoStartDLNA()) {
                IMEService.ensureDLNAStarted(getApplicationContext());
            }
        } catch (Throwable ignore) {}

        // 触摸板在电视上显示光标需要「显示在其他应用上层」权限
        maybeAskOverlayPermission();
    }

    /**
     * 引导授予悬浮窗权限：触摸板是通过注入事件移动指针的，注入事件不会触发
     * 系统自带的鼠标指针，因此需要本应用自行绘制光标浮层，否则用户只能「盲点」。
     */
    private void maybeAskOverlayPermission() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
            if (Settings.canDrawOverlays(this)) return;
            new AlertDialog.Builder(this)
                    .setTitle("开启触摸光标")
                    .setMessage("为了让触摸板在电视上显示可定位的光标（否则只能盲点），请授予「显示在其他应用上层」权限。\n\n授权后回到本页面即可生效。")
                    .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:" + getPackageName()));
                                startActivity(i);
                            } catch (Throwable t) {
                                Toast.makeText(MainActivity.this, "请手动在系统设置中授予悬浮窗权限",
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    })
                    .setNegativeButton("暂不", null)
                    .show();
        } catch (Throwable ignore) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshAddress() {
        String address = RemoteServer.getServerAddress(this);
        tvAddress.setText(address);
        qrCodeImage.setImageBitmap(QRCodeGen.generateBitmap(address, 240, 240));
    }

    private void refreshStatus() {
        boolean enabled = Environment.isEnableIME(this);
        boolean isDefault = Environment.isDefaultIME(this);
        setStatusRow(tvEnabledIcon, tvEnabledStatus, enabled,
                getString(enabled ? R.string.status_enabled : R.string.status_disabled));
        setStatusRow(tvDefaultIcon, tvDefaultStatus, isDefault,
                getString(isDefault ? R.string.status_default : R.string.status_not_default));
    }

    private void setStatusRow(TextView icon, TextView status, boolean ok, String text) {
        int color = ok ? getResources().getColor(R.color.success)
                       : getResources().getColor(R.color.warning);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        icon.setBackground(bg);
        icon.setText(ok ? "✓" : "!");
        status.setText(text);
        status.setTextColor(color);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btnCopy || id == R.id.tvAddress) {
            copyAddress();
        } else if (id == R.id.btnUseIME || id == R.id.rowEnabled) {
            openInputMethodSettings();
        } else if (id == R.id.btnSetIME || id == R.id.rowDefault) {
            showInputMethodPicker();
        } else if (id == R.id.btnRootSet) {
            doRootSetDefault();
        } else if (id == R.id.btnAdbGuide) {
            showAdbGuide();
        } else if (id == R.id.btnStartService) {
            startAllServices();
        } else if (id == R.id.btnCheckUpdate) {
            Toast.makeText(this, R.string.toast_checking, Toast.LENGTH_SHORT).show();
            new AutoUpdateManager(this).checkManually();
        } else if (id == R.id.btnAbout) {
            showAboutDialog();
        }
        refreshAddress();
        refreshStatus();
    }

    private void startAllServices() {
        Intent svc = new Intent();
        svc.setComponent(new ComponentName(this, IMEService.class));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
        else startService(svc);

        Intent guard = new Intent(this, cn.cloudhs.tvremoteime.service.ForegroundGuardService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(guard);
        else startService(guard);

        if (!Environment.isDefaultIME(this)) {
            try { if (AdbHelper.getInstance() == null) AdbHelper.createInstance(); } catch (Throwable ignore) {}
        }
        Toast.makeText(this, "远程服务与守护进程已启动", Toast.LENGTH_SHORT).show();
    }

    private void copyAddress() {
        String addr = tvAddress.getText().toString();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("addr", addr));
            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void openInputMethodSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Toast.makeText(this, "请在系统输入法设置中启用『云视远程输入法』",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "无法打开输入法设置，请手动在系统设置中开启",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void showInputMethodPicker() {
        if (!Environment.isEnableIME(this)) {
            Toast.makeText(this, "请先在「输入法设置」中启用本输入法", Toast.LENGTH_SHORT).show();
            openInputMethodSettings();
            return;
        }
        try {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
            Toast.makeText(this, "请在弹出的输入法列表中选择「云视远程输入法」",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "无法弹出输入法选择框，请使用 ROOT/ADB 方式切换",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** ROOT 设备：一键设为默认输入法 + 拉起服务保活 */
    private void doRootSetDefault() {
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = RootUtil.isRootAvailable();
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (!ok) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("未检测到 ROOT 权限")
                                    .setMessage("本设备未开启 ROOT 或未授权本应用 su 权限，请使用 ADB 方式或在系统设置中手动切换。")
                                    .setPositiveButton("知道了", null)
                                    .show();
                            return;
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("确认")
                                .setMessage("即将通过 ROOT 命令将「云视远程输入法」设为默认输入法，是否继续？")
                                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        boolean set = RootUtil.setAsDefaultIME(MainActivity.this);
                                        RootUtil.keepServiceAlive(MainActivity.this);
                                        Toast.makeText(MainActivity.this,
                                                set ? "已设置为默认输入法" : "设置失败，请检查 ROOT 授权",
                                                Toast.LENGTH_LONG).show();
                                        refreshStatus();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    /** 非 ROOT 设备：引导用户通过 ADB 命令切换默认输入法 */
    private void showAdbGuide() {
        String pkg = getPackageName();
        String cn = pkg + "/.IMEService";
        String cmd = "adb shell ime enable " + cn + "\n"
                + "adb shell ime set " + cn + "\n\n"
                + "提示：若 adb 不在环境变量中，可使用完整路径或进入平台工具目录后再执行。";
        final String text = "<b>非 ROOT 设备切换默认输入法步骤：</b><br><br>"
                + "1. 先确保已在「输入法设置」中<b>启用</b>云视远程输入法<br>"
                + "2. 在电视上打开「开发者选项」→ 打开「USB 调试」（部分盒子还需打开「网络 ADB 调试」）<br>"
                + "3. 用电脑连接盒子后，在命令行执行：<br><br>"
                + "<font face='monospace' color='#4f6dff'>adb shell ime enable " + cn + "<br>"
                + "adb shell ime set " + cn + "</font><br><br>"
                + "执行成功后本应用即成为默认输入法，重启手机/电视后仍然保持。<br><br>"
                + "💡 本应用内置 ADB 服务：首次启动后如果你通过远程网页访问，也可使用网页上的「切换默认输入法」快捷按钮（需在同一局域网下）。";

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("ADB 切换输入法")
                .setMessage(Html.fromHtml(text))
                .setPositiveButton("复制命令", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("cmd", cmd));
                            Toast.makeText(MainActivity.this, "命令已复制到剪贴板", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("打开开发者选项", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        // 如果未开启开发者选项，直接跳过去；若部分 ROM 没这个页面会跳到设置首页
                        try {
                            Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Throwable t) {
                            Toast.makeText(MainActivity.this, "请手动在「设置-关于-连续点击版本号」开启开发者选项",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNeutralButton("关闭", null)
                .create();
        dlg.show();
    }

    private void showAboutDialog() {
        String vn = AppPackagesHelper.getCurrentPackageVersion(this);
        String msg = "云视远程输入法 v" + vn + "\n\n"
                + "一款面向智能电视/盒子的远程输入法与遥控器\n"
                + "手机浏览器即可控制电视方向键、触摸板、文字输入与投屏\n\n"
                + "© 2026 孟云";
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setIcon(R.mipmap.ic_launcher)
                .setMessage(msg)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .show();
    }
}
