package cn.cloudhs.tvremoteime.server;

import android.util.Log;

import org.json.JSONObject;

import cn.cloudhs.tvremoteime.IMEService;
import cn.cloudhs.tvremoteime.input.TouchInjector;
import fi.iki.elonen.NanoWSD;

import java.io.IOException;

/**
 * WebSocket 长连接：接收 JSON 指令并转交给输入法服务 / 触摸注入器。
 *
 * 约定消息格式（client -> server）：
 * {
 *   "type": "key" | "keydown" | "keyup" | "text" | "touch" | "ping",
 *   "code": "KEYCODE_DPAD_UP",          // key 类使用
 *   "text": "abc",                      // text 类使用
 *   "action": "move"|"down"|"up"|...,   // touch 类使用
 *   "dx": 1.2, "dy": -3.4               // touch 类使用
 * }
 */
public class RemoteWebSocket extends NanoWSD.WebSocket {

    private final RemoteServer server;
    private TouchInjector touchInjector;

    public RemoteWebSocket(NanoWSD.IHTTPSession handshakeRequest, RemoteServer server) {
        super(handshakeRequest);
        this.server = server;
    }

    private TouchInjector getTouch() {
        if (touchInjector == null) {
            touchInjector = new TouchInjector(server.getServerContext());
            // 使用持久化的灵敏度（与 /touch HTTP 路径行为一致）
            try {
                float sens = cn.cloudhs.tvremoteime.AppConfig
                        .getInstance(server.getServerContext()).getTouchSensitivity();
                touchInjector.setSensitivity(sens);
            } catch (Throwable ignored) {}
        }
        return touchInjector;
    }

    @Override
    protected void onOpen() {
        Log.i(IMEService.TAG, "WebSocket open: " + getHandshakeRequest().getRemoteIpAddress());
        sendSafe("{\"type\":\"hello\",\"name\":\"TVRemoteIME\"}");
    }

    @Override
    protected void onClose(NanoWSD.WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
        Log.i(IMEService.TAG, "WebSocket close: " + code + " " + reason);
        server.removeWebSocket(this);
    }

    @Override
    protected void onMessage(NanoWSD.WebSocketFrame frame) {
        String payload = frame.getTextPayload();
        if (payload == null) return;
        payload = payload.trim();
        if (payload.isEmpty()) return;
        try {
            JSONObject j = new JSONObject(payload);
            String type = j.optString("type", "");
            RemoteServer.DataReceiver dr = server.getDataReceiver();
            switch (type) {
                case "ping":
                    sendSafe("{\"type\":\"pong\",\"t\":" + System.currentTimeMillis() + "}");
                    break;
                case "key":
                    if (dr != null) dr.onKeyEventReceived(j.optString("code"), IMEService.KEY_ACTION_PRESSED);
                    break;
                case "keydown":
                    if (dr != null) dr.onKeyEventReceived(j.optString("code"), IMEService.KEY_ACTION_DOWN);
                    break;
                case "keyup":
                    if (dr != null) dr.onKeyEventReceived(j.optString("code"), IMEService.KEY_ACTION_UP);
                    break;
                case "text":
                    if (dr != null) dr.onTextReceived(j.optString("text"));
                    break;
                case "touch":
                    String act = j.optString("action", "move");
                    float dx = (float) j.optDouble("dx", 0);
                    float dy = (float) j.optDouble("dy", 0);
                    // 前端把当前灵敏度随事件带上，即时生效（与 HTTP 路径的 sens 参数一致）
                    if (j.has("sens")) {
                        try { getTouch().setSensitivity((float) j.optDouble("sens", 1.6)); } catch (Throwable ignored) {}
                    }
                    if ("hide".equals(act)) {
                        // 前端切换功能区/页面隐藏时请求隐藏光标浮层
                        getTouch().hideCursor();
                        sendSafe("{\"type\":\"ack\",\"ok\":true,\"available\":" + getTouch().isAvailable() + "}");
                        break;
                    }
                    boolean ok = getTouch().sendTouch(act, dx, dy);
                    sendSafe("{\"type\":\"ack\",\"ok\":" + ok + ",\"available\":" + getTouch().isAvailable() + "}");
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            Log.w(IMEService.TAG, "WebSocket 消息解析失败: " + payload, e);
        }
    }

    @Override
    protected void onPong(NanoWSD.WebSocketFrame pong) { }

    @Override
    protected void onException(IOException exception) {
        Log.w(IMEService.TAG, "WebSocket 异常", exception);
        try { close(NanoWSD.WebSocketFrame.CloseCode.InternalServerError, "exception", false); } catch (Exception ignored) {}
        server.removeWebSocket(this);
    }

    private void sendSafe(String text) {
        try { send(text); } catch (Exception ignored) {}
    }
}
