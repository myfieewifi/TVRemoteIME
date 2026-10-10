package cn.cloudhs.tvremoteime.server;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import cn.cloudhs.tvremoteime.Environment;
import cn.cloudhs.tvremoteime.IMEService;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

/**
 * v1.0 远程服务：HTTP + WebSocket，提供新版前端与触摸控制。
 */
public class RemoteServer extends NanoWSD {

    public interface DataReceiver {
        /**
         * @param keyCode
         * @param keyAction : 0 = keypressed, 1 = keydown, 2 = keyup
         */
        void onKeyEventReceived(String keyCode, int keyAction);
        void onTextReceived(String text);
    }

    public static int serverPort = 9978;
    private boolean isStarted = false;
    private DataReceiver mDataReceiver = null;
    private final Context mContext;
    private final RemoteServerFileManager.Factory fileManagerFactory = new RemoteServerFileManager.Factory();
    private final ArrayList<RequestProcesser> getRequestProcessers = new ArrayList<>();
    private final ArrayList<RequestProcesser> postRequestProcessers = new ArrayList<>();

    private final ArrayList<RemoteWebSocket> webSockets = new ArrayList<>();

    public void setDataReceiver(DataReceiver receiver) { mDataReceiver = receiver; }
    public DataReceiver getDataReceiver() { return mDataReceiver; }
    public boolean isStarting() { return isStarted; }

    public RemoteServer(int port, Context context) {
        super(port);
        mContext = context.getApplicationContext();
        this.addGetRequestProcessers();
        this.addPostRequestProcessers();
    }

    @Override
    public void start(int timeout, boolean daemon) throws IOException {
        isStarted = true;
        setTempFileManagerFactory(fileManagerFactory);
        super.start(timeout, daemon);
    }

    @Override
    public void stop() {
        synchronized (webSockets) {
            for (RemoteWebSocket ws : webSockets) {
                try { ws.close(WebSocketFrame.CloseCode.NormalClosure, "server stop", false); } catch (Exception ignored) {}
            }
            webSockets.clear();
        }
        super.stop();
        isStarted = false;
    }

    Context getServerContext() { return mContext; }

    public static String getLocalIPAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        int ipAddress = 0;
        if (wifiManager != null && wifiManager.getConnectionInfo() != null) {
            ipAddress = wifiManager.getConnectionInfo().getIpAddress();
        }
        if (ipAddress == 0) {
            try {
                Enumeration<NetworkInterface> enumerationNi = NetworkInterface.getNetworkInterfaces();
                while (enumerationNi.hasMoreElements()) {
                    NetworkInterface networkInterface = enumerationNi.nextElement();
                    String interfaceName = networkInterface.getDisplayName();
                    if ("eth0".equals(interfaceName) || "wlan0".equals(interfaceName)) {
                        Enumeration<InetAddress> enumIpAddr = networkInterface.getInetAddresses();
                        while (enumIpAddr.hasMoreElements()) {
                            InetAddress inetAddress = enumIpAddr.nextElement();
                            if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                                return inetAddress.getHostAddress();
                            }
                        }
                    }
                }
            } catch (SocketException e) {
                Log.e(IMEService.TAG, "获取本地IP出错", e);
            }
        } else {
            return String.format("%d.%d.%d.%d", (ipAddress & 0xff), (ipAddress >> 8 & 0xff),
                    (ipAddress >> 16 & 0xff), (ipAddress >> 24 & 0xff));
        }
        return "0.0.0.0";
    }

    public String getServerAddress() { return getServerAddress(mContext); }

    public static String getServerAddress(Context context) {
        String ipAddress = getLocalIPAddress(context);
        if (Environment.needDebug) {
            Environment.debug(IMEService.TAG, "ip-address:" + ipAddress);
        }
        return "http://" + ipAddress + ":" + RemoteServer.serverPort + "/";
    }

    public static NanoHTTPD.Response createPlainTextResponse(NanoHTTPD.Response.IStatus status, String text) {
        return newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, text);
    }

    public static NanoHTTPD.Response createJSONResponse(NanoHTTPD.Response.IStatus status, String text) {
        return newFixedLengthResponse(status, "application/json", text);
    }

    private void addGetRequestProcessers() {
        this.getRequestProcessers.add(new AssetsRequestProcesser(this.mContext));
        this.getRequestProcessers.add(new FileRequestProcesser(this.mContext));
        this.getRequestProcessers.add(new AppIconRequestProcesser(this.mContext));
        this.getRequestProcessers.add(new TVRequestProcesser(this.mContext));
        this.getRequestProcessers.add(new OtherGetRequestProcesser(this.mContext));
    }

    private void addPostRequestProcessers() {
        this.postRequestProcessers.add(new ConfigPostProcesser(this.mContext));
        this.postRequestProcessers.add(new InputRequestProcesser(this.mContext, this));
        this.postRequestProcessers.add(new TouchRequestProcesser(this.mContext, this));
        this.postRequestProcessers.add(new UploadRequestProcesser(this.mContext));
        this.postRequestProcessers.add(new AppRequestProcesser(this.mContext));
        this.postRequestProcessers.add(new PlayRequestProcesser(this.mContext));
        this.postRequestProcessers.add(new FileRequestProcesser(this.mContext));
        this.postRequestProcessers.add(new TVRequestProcesser(this.mContext));
        this.postRequestProcessers.add(new TorrentRequestProcesser(this.mContext));
        this.postRequestProcessers.add(new OtherPostRequestProcesser(this.mContext));
    }

    @Override
    protected NanoHTTPD.Response serveHttp(NanoHTTPD.IHTTPSession session) {
        Log.i(IMEService.TAG, "HTTP: " + session.getMethod() + " " + session.getUri());
        String fileName = session.getUri() == null ? "" : session.getUri().trim();
        int q = fileName.indexOf('?');
        if (q >= 0) fileName = fileName.substring(0, q);

        if (session.getMethod() == NanoHTTPD.Method.GET) {
            for (RequestProcesser p : this.getRequestProcessers) {
                if (p.isRequest(session, fileName)) {
                    return p.doResponse(session, fileName, session.getParms(), null);
                }
            }
        } else if (session.getMethod() == NanoHTTPD.Method.POST) {
            Map<String, String> files = new HashMap<>();
            try {
                session.parseBody(files);
            } catch (IOException ioex) {
                return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                        "SERVER INTERNAL ERROR: IOException: " + ioex.getMessage());
            } catch (NanoHTTPD.ResponseException rex) {
                return createPlainTextResponse(rex.getStatus(), rex.getMessage());
            }
            for (RequestProcesser p : this.postRequestProcessers) {
                if (p.isRequest(session, fileName)) {
                    return p.doResponse(session, fileName, session.getParms(), files);
                }
            }
        }
        // default
        return this.getRequestProcessers.get(0).doResponse(session, "/", session.getParms(), null);
    }

    @Override
    protected WebSocket openWebSocket(NanoHTTPD.IHTTPSession handshake) {
        String uri = handshake.getUri();
        if (!"/ws".equalsIgnoreCase(uri)) return null;
        RemoteWebSocket ws = new RemoteWebSocket(handshake, this);
        synchronized (webSockets) { webSockets.add(ws); }
        return ws;
    }

    void removeWebSocket(RemoteWebSocket ws) {
        synchronized (webSockets) { webSockets.remove(ws); }
    }

    public void broadcast(String text) {
        synchronized (webSockets) {
            for (RemoteWebSocket ws : webSockets) {
                try { ws.send(text); } catch (Exception ignored) {}
            }
        }
    }
}
