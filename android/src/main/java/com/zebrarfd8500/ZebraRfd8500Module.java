package com.zebrarfd8500;

import androidx.annotation.Nullable;

import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.ArrayList;

public class ZebraRfd8500Module extends ReactContextBaseJavaModule implements LifecycleEventListener {

	private final ReactApplicationContext reactContext;
	private final String READER_STATUS = "READER_STATUS";
	private final String TRIGGER_STATUS = "TRIGGER_STATUS";
	private final String WRITE_TAG_STATUS = "WRITE_TAG_STATUS";
	private final String TAG = "TAG";
	private final String LOCATE_TAG = "LOCATE_TAG";
	private static ArrayList cacheTags = new ArrayList<>();

	public ZebraRfd8500Module(ReactApplicationContext reactContext) {
		super(reactContext);
		this.reactContext = reactContext;
		this.reactContext.addLifecycleEventListener(this);
	}

	private void sendEvent(String eventName, @Nullable WritableMap params) {
		this.reactContext
				.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
				.emit(eventName, params);
	}

	private void sendEvent(String eventName, String msg) {
		this.reactContext
				.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
				.emit(eventName, msg);
	}

	@Override
	public String getName() {
		return "ZebraRfd8500";
	}

	@Override
	public void onHostResume() {

	}

	@Override
	public void onHostPause() {

	}

	@Override
	public void onHostDestroy() {

	}

	@ReactMethod
	public void isConnected(Promise promise) {
		promise.resolve(false);
	}

	@ReactMethod
	public void clear(Promise promise) {
		cacheTags = new ArrayList<>();
		promise.resolve(true);
	}
}
