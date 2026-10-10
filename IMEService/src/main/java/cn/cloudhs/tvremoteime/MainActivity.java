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
import android.os.Bundle;
import android.provider.Settings;
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
    private Button btnCopy, btnUseIME, btnSetIME, btnStartService, btnCheckUpdate, btnAbout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 记录全局 Application Context，供无入参的静态工具方法兜底使用
        Environment.context = getApplicationContext();
        setContentView(R.layout.activity_main);

        qrCodeImage      = findViewById(R.id.ivQRCode);
        tvAddress        = findViewById(R.id.tvAddress);
        tvVersion        = findViewById(R.id.tvVersion);
        tvEnabledStatus  = findViewById(R.id.tvEnabledStatus);
        tvDefaultStatus  = findViewById(R.id.tvDefaultStatus);
        tvEnabledIcon    = findViewById(R.id.tvEnabledIcon);
        tvDefaultIcon    = findViewById(R.id.tvDefaultIcon);
        btnCopy          = findViewById(R.id.btnCopy);
        btnUseIME        = findViewById(R.id.btnUseIME);
        btnSetIME        = findViewById(R.id.btnSetIME);
        btnStartService  = findViewById(R.id.btnStartService);
        btnCheckUpdate   = findViewById(R.id.btnCheckUpdate);
        btnAbout         = findViewById(R.id.btnAbout);

        btnCopy.setOnClickListener(this);
        btnUseIME.setOnClickListener(this);
        btnSetIME.setOnClickListener(this);
        btnStartService.setOnClickListener(this);
        btnCheckUpdate.setOnClickListener(this);
        btnAbout.setOnClickListener(this);
        findViewById(R.id.rowEnabled).setOnClickListener(this);
        findViewById(R.id.rowDefault).setOnClickListener(this);

        String vn = AppPackagesHelper.getCurrentPackageVersion(this);
        tvVersion.setText("v" + vn);

        refreshAddress();
        refreshStatus();
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
        } else if (id == R.id.btnStartService) {
            Intent svc = new Intent();
            svc.setComponent(new ComponentName(this, IMEService.class));
            startService(svc);
            Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show();
            if (!Environment.isDefaultIME(this)) {
                if (AdbHelper.getInstance() == null) AdbHelper.createInstance();
            }
            Toast.makeText(this, "远程服务已启动，请稍候访问控制端", Toast.LENGTH_SHORT).show();
        } else if (id == R.id.btnCheckUpdate) {
            Toast.makeText(this, R.string.toast_checking, Toast.LENGTH_SHORT).show();
            new AutoUpdateManager(this).checkManually();
        } else if (id == R.id.btnAbout) {
            showAboutDialog();
        }
        refreshAddress();
        refreshStatus();
    }

    private void copyAddress() {
        String addr = tvAddress.getText().toString();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("server_address", addr));
            Toast.makeText(this, R.string.address_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void openInputMethodSettings() {
        try {
            startActivityForResult(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS), 0);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开输入法设置，请前往系统设置手动开启",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void showInputMethodPicker() {
        if (!Environment.isEnableIME(this)) {
            Toast.makeText(this, "请先启用「云视远程输入法」", Toast.LENGTH_SHORT).show();
            openInputMethodSettings();
            return;
        }
        try {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        } catch (Exception e) {
            Toast.makeText(this, "无法弹出输入法选择框，请在系统设置中手动切换",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void showAboutDialog() {
        String vn = AppPackagesHelper.getCurrentPackageVersion(this);
        String msg = "云视远程输入法 v" + vn + "\n\n"
                + "一款面向智能电视/盒子的远程输入法与遥控器\n"
                + "手机浏览器即可控制电视方向键、触摸板、文字输入与投屏\n\n"
                + "· 重新设计 Web 前端 UI\n"
                + "· 引入 ExoPlayer 新内核\n"
                + "· 新增触摸板控制\n"
                + "· 配置持久化与在线更新";
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
