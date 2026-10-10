package cn.cloudhs.tvremoteime;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import cn.cloudhs.tvremoteime.adb.AdbHelper;
import cn.cloudhs.tvremoteime.server.RemoteServer;
import cn.cloudhs.tvremoteime.server.RemoteServerFileManager;

public class IMEService extends InputMethodService implements View.OnClickListener{
	public static String TAG = "TVRemoteIME";
	public static String ACTION = "cn.cloudhs.tvremoteime";

	private boolean capsOn = false;
	private ImageButton btnCaps = null;
	private View focusedView = null;
	private RelativeLayout mInputView = null;
	private boolean hideWindowByKey = false;

	private View helpDialog = null;
	private ImageView qrCodeImage = null;
	private TextView  addressView = null;

	private RemoteServer mServer = null;
	private LinearLayout qweLine = null;
	private LinearLayout asdLine = null;
	private LinearLayout zxcLine = null;

	private static final int SERVER_START_ERROR = 901;
	private static final int ERROR = 999;
	private static final int TOAST_MESSAGE = 1000;

	public static final int KEY_ACTION_PRESSED = 0;
	public static final int KEY_ACTION_DOWN = 1;
	public static final int KEY_ACTION_UP = 2;

	final Handler handler = new Handler(Looper.getMainLooper());

	// 全局事件注入（反射 InputManager 用于弹框/非输入焦点场景的兜底）
	private static Object sGlobalIM;
	private static Method sInjectInputEvent;
	private static boolean sInjectAvailable = false;

	static {
		try {
			Class<?> clz = Class.forName("android.hardware.input.InputManager");
			Method getInst = clz.getMethod("getInstance");
			sGlobalIM = getInst.invoke(null);
			sInjectInputEvent = clz.getMethod("injectInputEvent",
					Class.forName("android.view.InputEvent"), int.class);
			sInjectAvailable = true;
		} catch (Throwable ignore) {}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		// 记录全局 Application Context，供无入参的静态工具方法兜底使用
		Environment.context = getApplicationContext();
		Environment.initToastHandler();
		RemoteServerFileManager.resetBaseDir(this);
		// 从持久化配置读取端口
		RemoteServer.serverPort = AppConfig.getInstance(this).getServerPort();
		startRemoteServer();
		// 启动前台守护服务（常驻通知，防系统杀后台）
		try {
			Intent guard = new Intent(this, cn.cloudhs.tvremoteime.service.ForegroundGuardService.class);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(guard);
			else startService(guard);
		} catch (Throwable ignore) {}
		// DLNA 投屏服务（DMR）需要持续在线，手机视频 APP 才能搜索到本设备并投屏；
		// 默认随输入法启动（autoStartDLNA，默认开启），可在配置中关闭
		try {
			if (AppConfig.getInstance(this).isAutoStartDLNA()) {
				ensureDLNAStarted(getApplicationContext());
			}
		} catch (Throwable ignore) {}
		// 【内存优化】更新检测延迟30秒再执行，每个版本只弹一次提示
		final AutoUpdateManager updater = new AutoUpdateManager(IMEService.this);
		handler.postDelayed(new Runnable() {
			@Override public void run() {
				updater.checkOnStartDelayed();
			}
		}, 30_000);
	}

	/** 外部（HTTP请求）按需启动 DLNA 服务，不用时不占内存 */
	public static void ensureDLNAStarted(Context ctx) {
		if (!sDLNAStarted) {
			synchronized (IMEService.class) {
				if (!sDLNAStarted) {
					DLNAUtils.startDLNAService(ctx.getApplicationContext());
					sDLNAStarted = true;
				}
			}
		}
	}
	private static volatile boolean sDLNAStarted = false;

	/**
	 * 内存压力回调：输入法是常驻进程，系统内存吃紧时主动释放可重建的资源，
	 * 降低被系统直接回收（导致输入法失效）的概率。
	 */
	@Override
	public void onTrimMemory(int level) {
		super.onTrimMemory(level);
		if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
			cn.cloudhs.tvremoteime.input.TouchInjector.release();
		}
	}

	@Override
    public View onCreateInputView()  {
		if(Environment.needDebug){ Environment.debug(TAG, "onCreateInputView."); }
    	mInputView = (RelativeLayout)getLayoutInflater().inflate(R.layout.keyboard, null);
		capsOn = true;
		btnCaps = mInputView.findViewById(R.id.btnCaps);
		qweLine = mInputView.findViewById(R.id.qweLine);
		asdLine = mInputView.findViewById(R.id.asdLine);
		zxcLine = mInputView.findViewById(R.id.zxcLine);
		helpDialog = mInputView.findViewById(R.id.helpDialog);
		qrCodeImage = helpDialog.findViewById(R.id.ivQRCode);
		addressView = helpDialog.findViewById(R.id.tvAddress);
		toggleCapsState(true);
        return mInputView; 
    }

	@Override
	public View onCreateCandidatesView() { return null; }

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		onStart(intent, startId);
		return START_STICKY;
	}

	@Override
	public void onStartInput(EditorInfo attribute, boolean restarting) { super.onStartInput(attribute, restarting); }
	@Override
	public void onFinishInputView(boolean finishingInput) { super.onFinishInputView(finishingInput); }
	@Override
	public void onFinishCandidatesView(boolean finishingInput) { super.onFinishCandidatesView(finishingInput); }

	@Override
	public boolean onEvaluateInputViewShown() {
		super.onEvaluateInputViewShown();
		if(hideWindowByKey){ hideWindowByKey = false; return false; }
		EditorInfo editorInfo = getCurrentInputEditorInfo();
		return !(editorInfo == null || editorInfo.inputType == EditorInfo.TYPE_NULL);
	}

	@Override
	public boolean onEvaluateFullscreenMode() { return false; }

	private boolean isSendToAdbService(Object data){
		if(AdbHelper.initService(getApplicationContext())){
			AdbHelper.getInstance().sendData(data);
			return true;
		}
		return false;
	}

	private void startRemoteServer(){
		do {
			mServer = new RemoteServer(RemoteServer.serverPort, this);
			mServer.setDataReceiver(new RemoteServer.DataReceiver() {
				@Override
				public void onKeyEventReceived(String keyCode, final int keyAction) {
					if(keyCode == null) return;
					if("cls".equalsIgnoreCase(keyCode)){
						InputConnection ic = getCurrentInputConnection();
						if(ic != null) ic.deleteSurroundingText(Integer.MAX_VALUE, Integer.MAX_VALUE);
						return;
					}
					final int kc = KeyEvent.keyCodeFromString(keyCode);
					if(kc == KeyEvent.KEYCODE_UNKNOWN) return;

					// ===== 关键修复：方向键/确认/返回 在任何场景都能生效 =====
					// 弹框/Dialog/菜单/系统弹窗等场景 InputConnection 可能拿不到，必须用全局焦点或注入
					final boolean isNavKey = KeyEventUtils.isNavigationKey(kc);

					handler.post(new Runnable() {
						@Override
						public void run() {
							if (mInputView != null && KeyEventUtils.isKeyboardFocusEvent(kc) && mInputView.isShown()) {
								// 软键盘自身的方向导航（按钮焦点切换）
								if (keyAction == KEY_ACTION_PRESSED || keyAction == KEY_ACTION_DOWN) {
									if (!handleKeyboardFocusEvent(kc)) {
										dispatchKeyToCurrentFocus(kc, keyAction);
									}
								}
							} else if (isNavKey) {
								// 导航键（上下左右/确认/返回/主页）：全局派发，弹框/菜单/Dialog 都能响应
								dispatchKeyToCurrentFocus(kc, keyAction);
							} else {
								// 普通字符/文本键
								dispatchViaInputConnection(kc, keyAction);
							}
						}
					});
				}

				@Override
				public void onTextReceived(String text) {
					if (text != null) {
						if(!isSendToAdbService(text)) commitText(text);
					}
				}
			});
			try {
				mServer.start();
				Environment.toastInHandler(this, getString(R.string.app_name) + " 远程服务已启动");
				Log.i(TAG, "远程服务创建成功！port=" + RemoteServer.serverPort);
				break;
			}catch (IOException ex){
				Log.e(TAG, "建立输入HTTP服务时出错", ex);
				RemoteServer.serverPort ++;
				mServer.stop();
			}
		}while (RemoteServer.serverPort < 9999);
	}

	/** 查找当前窗口（含 Dialog/弹框）拥有焦点的 View */
	private View getCurrentFocusedView() {
		// 1) 从 InputMethodService 的 window 找
		View v = getWindow().getCurrentFocus();
		if (v != null) return v;
		// 2) 尝试从当前顶层 window 找
		try {
			WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
			// 通过反射拿全局所有窗口比较麻烦，走 ActivityThread 找 top activity
			Class<?> atClass = Class.forName("android.app.ActivityThread");
			java.lang.reflect.Method currentAT = atClass.getDeclaredMethod("currentActivityThread");
			currentAT.setAccessible(true);
			Object at = currentAT.invoke(null);
			java.lang.reflect.Method getTop = atClass.getDeclaredMethod("getTopActivity");
			getTop.setAccessible(true);
			Activity act = (Activity) getTop.invoke(at);
			if (act != null) {
				Window w = act.getWindow();
				if (w != null) {
					View f = w.getCurrentFocus();
					if (f != null) return f;
					if (w.getDecorView() != null) return w.getDecorView();
				}
			}
		} catch (Throwable ignore) {}
		return null;
	}

	/** 把方向键/确认/返回/主页 派发给当前焦点 View（解决弹框失效问题） */
	private void dispatchKeyToCurrentFocus(int keyCode, int keyAction) {
		if (keyCode == KeyEvent.KEYCODE_HOME) {
			Intent i = new Intent(Intent.ACTION_MAIN);
			i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			i.addCategory(Intent.CATEGORY_HOME);
			startActivity(i);
			return;
		}
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			// 返回键优先走系统行为；直接用 sendDownUpKeyEvents
			if (keyAction == KEY_ACTION_PRESSED) sendDownUpKeyEvents(KeyEvent.KEYCODE_BACK);
			return;
		}

		long now = SystemClock.uptimeMillis();

		// 策略1：如果有当前焦点 View，直接调用它的 dispatchKeyEvent
		View focus = getCurrentFocusedView();
		if (focus != null) {
			try {
				switch (keyAction) {
					case KEY_ACTION_PRESSED: {
						KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0,
								0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
								KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_KEEP_TOUCH_MODE,
								InputDevice.SOURCE_KEYBOARD);
						KeyEvent up   = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0,
								0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
								KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_KEEP_TOUCH_MODE,
								InputDevice.SOURCE_KEYBOARD);
						boolean consumed = focus.dispatchKeyEvent(down) && focus.dispatchKeyEvent(up);
						if (consumed) return;
						break;
					}
					case KEY_ACTION_DOWN: {
						KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0,
								0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
								KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_KEEP_TOUCH_MODE,
								InputDevice.SOURCE_KEYBOARD);
						if (focus.dispatchKeyEvent(down)) return;
						break;
					}
					case KEY_ACTION_UP: {
						KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0,
								0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
								KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_KEEP_TOUCH_MODE,
								InputDevice.SOURCE_KEYBOARD);
						if (focus.dispatchKeyEvent(up)) return;
						break;
					}
				}
			} catch (Throwable t) {
				Log.w(TAG, "dispatchKeyEvent to focus view 失败", t);
			}
		}

		// 策略2：兜底通过 InputManager 注入全局按键（适配系统弹框/Recents/全局菜单）
		injectGlobalKey(keyCode, keyAction);
	}

	private void injectGlobalKey(int keyCode, int keyAction) {
		if (!sInjectAvailable || sInjectInputEvent == null || sGlobalIM == null) {
			// 无反射能力时退回到输入法的 sendDownUpKeyEvents（仅在 IME 有焦点时生效）
			if (keyAction == KEY_ACTION_PRESSED) sendDownUpKeyEvents(keyCode);
			return;
		}
		long now = SystemClock.uptimeMillis();
		try {
			int meta = 0;
			int src = InputDevice.SOURCE_KEYBOARD;
			if (keyAction == KEY_ACTION_DOWN || keyAction == KEY_ACTION_PRESSED) {
				KeyEvent ev = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta,
						KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, src);
				sInjectInputEvent.invoke(sGlobalIM, ev, 0); // ASYNC=0
			}
			if (keyAction == KEY_ACTION_UP || keyAction == KEY_ACTION_PRESSED) {
				KeyEvent ev = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta,
						KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, src);
				sInjectInputEvent.invoke(sGlobalIM, ev, 0);
			}
		} catch (Throwable t) {
			Log.w(TAG, "全局注入失败，退回 sendDownUpKeyEvents", t);
			if (keyAction == KEY_ACTION_PRESSED) sendDownUpKeyEvents(keyCode);
		}
	}

	private void dispatchViaInputConnection(int kc, int keyAction) {
		long eventTime = SystemClock.uptimeMillis();
		InputConnection ic = getCurrentInputConnection();
		switch (keyAction) {
			case KEY_ACTION_PRESSED:
				if(!isSendToAdbService(kc)) sendDownUpKeyEvents(kc);
				break;
			case KEY_ACTION_DOWN:
				if(!isSendToAdbService(kc) && ic != null) {
					ic.sendKeyEvent(new KeyEvent(eventTime, eventTime,
							KeyEvent.ACTION_DOWN, kc, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
							KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
				} else if (!isSendToAdbService(kc)) {
					injectGlobalKey(kc, KEY_ACTION_DOWN);
				}
				break;
			case KEY_ACTION_UP:
				if(ic != null) {
					ic.sendKeyEvent(new KeyEvent(eventTime, eventTime,
						KeyEvent.ACTION_UP, kc, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
						KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
				} else {
					injectGlobalKey(kc, KEY_ACTION_UP);
				}
				break;
		}
	}

	private boolean commitText(String text){
		InputConnection ic = getCurrentInputConnection();
		if (ic == null) return false;
		if(Environment.needDebug) Environment.debug(TAG, "commitText:" + text);
		boolean flag;
		if(text.length() > 1 && ic.beginBatchEdit()){
			flag = ic.commitText(text, 1);
			ic.endBatchEdit();
		}else{
			flag = ic.commitText(text, 1);
		}
		return flag;
	}

	private void sendKeyCode(int keyCode){
		if(keyCode == KeyEvent.KEYCODE_HOME){
			Intent i = new Intent(Intent.ACTION_MAIN);
			i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			i.addCategory(Intent.CATEGORY_HOME);
			this.startActivity(i);
		}else {
			sendDownUpKeyEvents(keyCode);
		}
	}
    
    public void onDestroy() {
		if (mServer != null && mServer.isStarting()){
            Log.i(TAG, "远程输入服务已停止！");
			mServer.stop();
		}
		DLNAUtils.stopDLNAService();
		AdbHelper.stopService();
		Environment.toastInHandler(this, getString(R.string.app_name) + "服务已停止");
    	super.onDestroy();    	
    }

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if(handleKeyboardFocusEvent(keyCode)) return true;
		if(keyCode == KeyEvent.KEYCODE_CAPS_LOCK) capsOn = !capsOn;
		if ((keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9)) {
			if(commitText(String.valueOf(keyCode - KeyEvent.KEYCODE_0))) return true;
		} else if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
			if (commitText(String.valueOf((char) ((capsOn ? 65 : 97) + keyCode - KeyEvent.KEYCODE_A))))
				return true;
		}
		return super.onKeyDown(keyCode, event);
	}

	private boolean handleKeyboardFocusEvent(int keyCode){
		if(mInputView != null) {
			switch (keyCode) {
				case KeyEvent.KEYCODE_DPAD_UP:
				case KeyEvent.KEYCODE_DPAD_DOWN:
				case KeyEvent.KEYCODE_DPAD_LEFT:
				case KeyEvent.KEYCODE_DPAD_RIGHT:
					if(mInputView.isShown()) {
						requestNextButtonFocus(keyCode);
						return true;
					}
					break;
				case KeyEvent.KEYCODE_ENTER:
				case KeyEvent.KEYCODE_DPAD_CENTER:
					if (mInputView.isShown() && focusedView != null) {
						clickButtonByKey(focusedView);
						return true;
					}
					break;
				case KeyEvent.KEYCODE_CAPS_LOCK:
					toggleCapsState(true);
					return true;
				case KeyEvent.KEYCODE_ESCAPE:
				case KeyEvent.KEYCODE_BACK:
					if (mInputView.isShown()){
						if(helpDialog != null && helpDialog.isShown()){
							helpDialog.setVisibility(View.GONE);
						}else {
							this.finishInput();
						}
						return true;
					}
					break;
			}
		}
		return false;
	}

	private void requestNextButtonFocus(int keyCode){
		if(focusedView == null){
			focusedView =  ((LinearLayout)mInputView.getChildAt(0)).getChildAt(0);
		}else {
			LinearLayout container = (LinearLayout)focusedView.getParent();
			int rootInde = mInputView.indexOfChild(container);
			int index = container.indexOfChild(focusedView);
			boolean isLasted = container.getChildCount() == (index + 1);
			switch (keyCode) {
				case KeyEvent.KEYCODE_DPAD_UP:
					rootInde --;
					if(rootInde < 0) rootInde = mInputView.getChildCount() - 2;
					container = (LinearLayout)mInputView.getChildAt(rootInde);
					if(index >= container.getChildCount()) index = isLasted ? container.getChildCount() - 1 : 0;
					break;
				case KeyEvent.KEYCODE_DPAD_DOWN:
					rootInde ++;
					if(rootInde >= (mInputView.getChildCount() - 1)) rootInde = 0;
					container = (LinearLayout)mInputView.getChildAt(rootInde);
					if(index >= container.getChildCount()) index = isLasted ? container.getChildCount() - 1 :  0;
					break;
				case KeyEvent.KEYCODE_DPAD_LEFT:
					index --;
					if(index < 0){
						rootInde --;
						if(rootInde < 0) rootInde = mInputView.getChildCount() - 2;
						container = (LinearLayout)mInputView.getChildAt(rootInde);
						index = container.getChildCount() - 1;
					}
					break;
				case KeyEvent.KEYCODE_DPAD_RIGHT:
					index ++;
					if(index >= container.getChildCount()){
						rootInde ++;
						if(rootInde >= (mInputView.getChildCount() - 1)) rootInde = 0;
						container = (LinearLayout)mInputView.getChildAt(rootInde);
						index = 0;
					}
					break;
			}
			focusedView = container.getChildAt(index);
		}
		focusedView.requestFocus();
		focusedView.requestFocusFromTouch();
	}

	private void finishInput(){
		this.onFinishInput();
		this.hideWindow();
	}

	private void toggleCapsState(boolean showHelp){
		if(btnCaps == null || qweLine == null || asdLine == null || zxcLine == null) return;
		capsOn = !capsOn;
		btnCaps.setSelected(capsOn);
		String[] letters = {"QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"};
		LinearLayout[] lines = {qweLine, asdLine, zxcLine};
		if(!capsOn){
			letters[0] = letters[0].toLowerCase();
			letters[1] = letters[1].toLowerCase();
			letters[2] = letters[2].toLowerCase();
		}
		for(int i=0; i<3; i++){
			for(int j=0; j<lines[i].getChildCount(); j++){
				View btn = lines[i].getChildAt(j);
				if(btn instanceof Button){
					String t = letters[i].substring(j, j+1);
					((Button) btn).setText(t);
				}
			}
		}
		if(showHelp && helpDialog != null){
			if(mServer != null && mServer.isStarting()){
				String addr = mServer.getServerAddress();
				if(addressView != null) addressView.setText(addr);
				if(qrCodeImage != null){
					try{ qrCodeImage.setImageBitmap(QRCodeGen.generateBitmap(addr, 320, 320)); }catch(Exception ignored){}
				}
			}
			helpDialog.setVisibility(View.VISIBLE);
		}
	}

	private void clickButtonByKey(View btn){
		if(btn != null) btn.performClick();
	}

	@Override
	public void onClick(View v) {
		if(v == btnCaps){ toggleCapsState(false); return; }
		if(v instanceof Button){
			CharSequence text = ((Button)v).getText();
			if(text != null && text.length() > 0){
				commitText(text.toString());
			}
		}else if(v instanceof ImageButton){
			Object tag = v.getTag();
			if("shift".equals(tag)){ toggleCapsState(false); return; }
			if("delete".equals(tag)){
				InputConnection ic = getCurrentInputConnection();
				if(ic != null) ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
				return;
			}
			if("enter".equals(tag)){
				sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
				return;
			}
			if("space".equals(tag)){ commitText(" "); return; }
			if("net".equals(tag)){ /* 打开网址/网络占位 */ return; }
		}
	}
}
