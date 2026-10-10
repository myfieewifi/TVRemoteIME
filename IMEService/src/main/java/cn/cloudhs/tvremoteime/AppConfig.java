package cn.cloudhs.tvremoteime;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 云视远程输入法 - 全局配置持久化
 * 使用 SharedPreferences 存储用户设置，修改后立即生效
 */
public class AppConfig {
    private static final String PREF_NAME = "app_config";
    private static AppConfig instance;

    // 配置 key
    private static final String KEY_SERVER_PORT = "server_port";
    private static final String KEY_AUTO_START_DLNA = "auto_start_dlna";
    private static final String KEY_CHECK_UPDATE = "check_update_on_start";
    private static final String KEY_TOUCH_SENSITIVITY = "touch_sensitivity";
    private static final String KEY_DISMISSED_UPDATE = "dismissed_update_code";
    private static final String KEY_DLNA_NAME_SUFFIX = "dlna_name_suffix";
    private static final String KEY_PLAYER_UA = "player_user_agent";
    private static final String KEY_PLAYER_ENGINE = "player_engine";

    // 默认值
    public static final int DEFAULT_PORT = 9978;
    public static final float DEFAULT_TOUCH_SENSITIVITY = 1.2f;
    /** 默认播放器 UA：懒加载，避免类初始化时 Environment.context 尚未赋值导致 NPE */
    public static String getDefaultPlayerUserAgent() {
        String ver = AppPackagesHelper.getCurrentPackageVersion(
                Environment.context != null ? Environment.context : null);
        return "TVRemoteIME/" + (ver == null || ver.isEmpty() ? "1.0" : ver);
    }
    /**
     * 播放器内核：
     *   exo = ExoPlayer（默认，硬件解码，CPU/内存占用低，全架构可用）
     *   ijk = IJKPlayer（FFmpeg 软件解码，老设备/冷门格式兜底，仅 armeabi-v7a 包内含其 so）
     */
    public static final String ENGINE_EXO = "exo";
    public static final String ENGINE_IJK = "ijk";
    public static final String DEFAULT_PLAYER_ENGINE = ENGINE_EXO;

    private final SharedPreferences sp;

    private AppConfig(Context context) {
        sp = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized AppConfig getInstance(Context context) {
        if (instance == null) {
            instance = new AppConfig(context);
        }
        return instance;
    }

    /** HTTP 服务端口 */
    public int getServerPort() {
        return sp.getInt(KEY_SERVER_PORT, DEFAULT_PORT);
    }

    public void setServerPort(int port) {
        sp.edit().putInt(KEY_SERVER_PORT, port).apply();
    }

    /** 启动时是否自动开启 DLNA 投屏服务 */
    public boolean isAutoStartDLNA() {
        return sp.getBoolean(KEY_AUTO_START_DLNA, false);
    }

    public void setAutoStartDLNA(boolean auto) {
        sp.edit().putBoolean(KEY_AUTO_START_DLNA, auto).apply();
    }

    /** 启动时是否检查更新 */
    public boolean isCheckUpdateOnStart() {
        return sp.getBoolean(KEY_CHECK_UPDATE, true);
    }

    public void setCheckUpdateOnStart(boolean check) {
        sp.edit().putBoolean(KEY_CHECK_UPDATE, check).apply();
    }

    /** 触摸板灵敏度（0.6 ~ 3.0） */
    public float getTouchSensitivity() {
        return sp.getFloat(KEY_TOUCH_SENSITIVITY, DEFAULT_TOUCH_SENSITIVITY);
    }

    public void setTouchSensitivity(float sensitivity) {
        sp.edit().putFloat(KEY_TOUCH_SENSITIVITY,
                Math.max(0.6f, Math.min(3.0f, sensitivity))).apply();
    }

    /** 已忽略的更新版本号（每个新版本只提示一次） */
    public int getDismissedUpdateCode() {
        return sp.getInt(KEY_DISMISSED_UPDATE, 0);
    }

    public void setDismissedUpdateCode(int versionCode) {
        sp.edit().putInt(KEY_DISMISSED_UPDATE, versionCode).apply();
    }

    /** DLNA 设备名后缀（如"客厅"） */
    public String getDLNANameSuffix() {
        return sp.getString(KEY_DLNA_NAME_SUFFIX, "");
    }

    public void setDLNANameSuffix(String suffix) {
        sp.edit().putString(KEY_DLNA_NAME_SUFFIX,
                suffix == null ? "" : suffix.trim()).apply();
    }

    /** 播放器 UserAgent（投屏/直链播放使用） */
    public String getPlayerUserAgent() {
        String ua = sp.getString(KEY_PLAYER_UA, "");
        return (ua == null || ua.trim().isEmpty()) ? getDefaultPlayerUserAgent() : ua.trim();
    }

    public void setPlayerUserAgent(String ua) {
        if (ua == null || ua.trim().isEmpty()) {
            sp.edit().remove(KEY_PLAYER_UA).apply();
        } else {
            sp.edit().putString(KEY_PLAYER_UA, ua.trim()).apply();
        }
    }

    /** 默认播放器内核：exo / ijk */
    public String getPlayerEngine() {
        String engine = sp.getString(KEY_PLAYER_ENGINE, DEFAULT_PLAYER_ENGINE);
        if (engine == null) return DEFAULT_PLAYER_ENGINE;
        engine = engine.trim().toLowerCase();
        return ENGINE_IJK.equals(engine) ? ENGINE_IJK : ENGINE_EXO;
    }

    public void setPlayerEngine(String engine) {
        String value = (engine == null) ? "" : engine.trim().toLowerCase();
        if (!ENGINE_IJK.equals(value)) value = ENGINE_EXO;
        sp.edit().putString(KEY_PLAYER_ENGINE, value).apply();
    }

    /** 序列化为 JSON，供 Web 前端 /api/config 读取 */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("serverPort", getServerPort());
            json.put("autoStartDLNA", isAutoStartDLNA());
            json.put("checkUpdateOnStart", isCheckUpdateOnStart());
            json.put("touchSensitivity", getTouchSensitivity());
            json.put("dlnaNameSuffix", getDLNANameSuffix());
            json.put("playerUserAgent", getPlayerUserAgent());
            json.put("defaultPlayerUserAgent", getDefaultPlayerUserAgent());
            json.put("playerEngine", getPlayerEngine());
            json.put("defaultPlayerEngine", DEFAULT_PLAYER_ENGINE);
        } catch (JSONException ignored) {}
        return json;
    }

    /** 从 JSON 对象批量应用配置（POST /api/config 使用） */
    public void applyFromJson(JSONObject json) throws JSONException {
        SharedPreferences.Editor editor = sp.edit();
        if (json.has("serverPort")) {
            editor.putInt(KEY_SERVER_PORT, json.getInt("serverPort"));
        }
        if (json.has("autoStartDLNA")) {
            editor.putBoolean(KEY_AUTO_START_DLNA,
                    json.getBoolean("autoStartDLNA"));
        }
        if (json.has("checkUpdateOnStart")) {
            editor.putBoolean(KEY_CHECK_UPDATE,
                    json.getBoolean("checkUpdateOnStart"));
        }
        if (json.has("touchSensitivity")) {
            editor.putFloat(KEY_TOUCH_SENSITIVITY,
                    (float) json.getDouble("touchSensitivity"));
        }
        if (json.has("dlnaNameSuffix")) {
            editor.putString(KEY_DLNA_NAME_SUFFIX,
                    json.getString("dlnaNameSuffix"));
        }
        if (json.has("playerUserAgent")) {
            String ua = json.getString("playerUserAgent");
            if (ua == null || ua.trim().isEmpty()) {
                editor.remove(KEY_PLAYER_UA);
            } else {
                editor.putString(KEY_PLAYER_UA, ua.trim());
            }
        }
        if (json.has("playerEngine")) {
            String engine = json.getString("playerEngine");
            engine = (engine == null) ? "" : engine.trim().toLowerCase();
            if (!ENGINE_IJK.equals(engine)) engine = ENGINE_EXO;
            editor.putString(KEY_PLAYER_ENGINE, engine);
        }
        editor.apply();
    }
}
