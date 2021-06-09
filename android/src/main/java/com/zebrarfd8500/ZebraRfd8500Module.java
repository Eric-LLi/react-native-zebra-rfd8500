package com.zebrarfd8500;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.zebra.rfid.api3.ACCESS_OPERATION_CODE;
import com.zebra.rfid.api3.ACCESS_OPERATION_STATUS;
import com.zebra.rfid.api3.Antennas;
import com.zebra.rfid.api3.BATCH_MODE;
import com.zebra.rfid.api3.BEEPER_VOLUME;
import com.zebra.rfid.api3.DYNAMIC_POWER_OPTIMIZATION;
import com.zebra.rfid.api3.ENUM_TRANSPORT;
import com.zebra.rfid.api3.ENUM_TRIGGER_MODE;
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE;
import com.zebra.rfid.api3.INVENTORY_STATE;
import com.zebra.rfid.api3.InvalidUsageException;
import com.zebra.rfid.api3.MEMORY_BANK;
import com.zebra.rfid.api3.OperationFailureException;
import com.zebra.rfid.api3.RFIDReader;
import com.zebra.rfid.api3.RFIDResults;
import com.zebra.rfid.api3.ReaderDevice;
import com.zebra.rfid.api3.Readers;
import com.zebra.rfid.api3.Readers.RFIDReaderEventHandler;
import com.zebra.rfid.api3.RegionInfo;
import com.zebra.rfid.api3.RegulatoryConfig;
import com.zebra.rfid.api3.RfidEventsListener;
import com.zebra.rfid.api3.RfidReadEvents;
import com.zebra.rfid.api3.RfidStatusEvents;
import com.zebra.rfid.api3.SESSION;
import com.zebra.rfid.api3.SL_FLAG;
import com.zebra.rfid.api3.START_TRIGGER_TYPE;
import com.zebra.rfid.api3.STATUS_EVENT_TYPE;
import com.zebra.rfid.api3.STOP_TRIGGER_TYPE;
import com.zebra.rfid.api3.SupportedRegions;
import com.zebra.rfid.api3.TagAccess;
import com.zebra.rfid.api3.TagData;
import com.zebra.rfid.api3.TriggerInfo;
import com.zebra.scannercontrol.DCSSDKDefs;
import com.zebra.scannercontrol.DCSScannerInfo;
import com.zebra.scannercontrol.FirmwareUpdateEvent;
import com.zebra.scannercontrol.IDcsSdkApiDelegate;
import com.zebra.scannercontrol.SDKHandler;

import java.util.ArrayList;

public class ZebraRfd8500Module extends ReactContextBaseJavaModule implements LifecycleEventListener {

    private final ReactApplicationContext reactContext;

    private final String LOG = "[RFD8500]";
    private final String READER_STATUS = "READER_STATUS";
    private final String TRIGGER_STATUS = "TRIGGER_STATUS";
    private final String WRITE_TAG_STATUS = "WRITE_TAG_STATUS";
    private final String BATTERY_STATUS = "BATTERY_STATUS";
    private final String TAG = "TAG";
    private final String TAGS = "TAGS";
    private final String BARCODE = "BARCODE";
    private final String LOCATE_TAG = "LOCATE_TAG";

    private Readers readers;
    private static ReaderDevice readerDevice;
    private static RFIDReader reader;
    private static final ArrayList<String> cacheTags = new ArrayList<>();
    private static boolean isSingleRead = false;
    private static boolean isReadingBarcode = false;

    private static boolean isLocatingTag = false;
    private static boolean isLocateMode = false;
    private static String locateTag = null;

    //Barcode
    private static SDKHandler sdkHandler = null;
    private static DCSScannerInfo scanner = null;
    private static int notifications_mask = 0;

    public ZebraRfd8500Module(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        this.reactContext.addLifecycleEventListener(this);
    }

    private void sendEvent(String eventName, WritableMap params) {
        this.reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }

    private void sendEvent(String eventName, String msg) {
        this.reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, msg);
    }

    private void sendEvent(String eventName, WritableArray array) {
        this.reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, array);
    }

    @Override
    public String getName() {
        return "ZebraRfd8500";
    }

    @Override
    public void onHostResume() {
        //
    }

    @Override
    public void onHostPause() {
        //
    }

    @Override
    public void onHostDestroy() {
        doDisconnect();
    }

    @ReactMethod
    public void isConnected(Promise promise) {
        Log.d(LOG, "isConnected");

        if (reader != null) {
            promise.resolve(reader.isConnected());
        } else {
            promise.resolve(false);
        }
    }

    @ReactMethod
    public void getDevices(Promise promise) {
        Log.d(LOG, "getDevices");

        try {
            if (readers == null || sdkHandler == null) {
                init();
            }

            ArrayList<ReaderDevice> devices = readers.GetAvailableRFIDReaderList();

            WritableArray deviceList = Arguments.createArray();

            for (ReaderDevice device : devices) {
                WritableMap map = Arguments.createMap();
                map.putString("name", device.getName());
                map.putString("mac", device.getAddress());
                deviceList.pushMap(map);
            }

            promise.resolve(deviceList);
        } catch (Exception err) {
            promise.reject(err);
        }
    }

    @ReactMethod
    public void disconnect(Promise promise) {
        Log.d(LOG, "disconnect");

        try {
            doDisconnect();

            promise.resolve(true);
        } catch (Exception err) {
            promise.reject(err);
        }
    }

    @ReactMethod
    public void reconnect() {
        doConnect();
    }

    @ReactMethod
    public void connect(String name, String mac, Promise promise) {
        Log.d(LOG, "connect");

        try {
            if ((reader != null && reader.isConnected()) || (scanner != null && scanner.isActive())) {
                doDisconnect();
            }

            if (readers == null || sdkHandler == null) {
                init();
            }

            if (reader == null) {
                ArrayList<ReaderDevice> devices = readers.GetAvailableRFIDReaderList();
                if (devices.size() == 1) {
                    readerDevice = devices.get(0);
                    reader = devices.get(0).getRFIDReader();
                } else {
                    for (ReaderDevice device : devices) {
                        if (device.getName().equals(name) || device.getAddress().equals(mac)) {
                            readerDevice = device;
                            reader = device.getRFIDReader();
                        }
                    }
                }
            }

            if (scanner == null) {
                ArrayList<DCSScannerInfo> scanners = new ArrayList<>();
                sdkHandler.dcssdkGetAvailableScannersList(scanners);
                if (scanners.size() == 1) {
                    scanner = scanners.get(0);
                } else {
                    for (DCSScannerInfo item : scanners) {
                        if (item.getScannerName().equals(name)) {
                            scanner = item;
                        }
                    }
                }
            }


            doConnect();

            promise.resolve(true);
        } catch (Exception error) {
            promise.reject(error);
        }
    }

    @ReactMethod
    public void getDeviceDetails(Promise promise) {
        Log.d(LOG, "getDeviceDetails");
        try {
            if (reader != null && reader.isConnected()) {
                reader.Config.getDeviceStatus(true, false, false);

                Antennas.AntennaRfConfig antennaRFConfig = reader.Config.Antennas.getAntennaRfConfig(1);
                int antennaLevel = antennaRFConfig.getTransmitPowerIndex();

                WritableMap map = Arguments.createMap();
                map.putString("name", readerDevice.getName());
                map.putString("mac", readerDevice.getAddress());
                map.putInt("antennaLevel", antennaLevel / 10);

                promise.resolve(map);
            } else {
                throw new Exception("Reader is not connected");
            }
        } catch (Exception err) {
            promise.reject(err);
        }
    }

    @ReactMethod
    public void clear() {
        Log.d(LOG, "clear");

        cacheTags.clear();
    }

    @ReactMethod
    public void setSingleRead(boolean enable) {
        Log.d(LOG, "setSingleRead");

        isSingleRead = enable;
    }

    @ReactMethod
    public void setAntennaLevel(int antennaLevel, Promise promise) {
        Log.d(LOG, "setAntennaLevel");

        try {
            if (reader != null && reader.isConnected()) {
                Antennas.AntennaRfConfig antennaRfConfig = null;

                antennaRfConfig = reader.Config.Antennas
                        .getAntennaRfConfig(1);

                antennaRfConfig.setTransmitPowerIndex(antennaLevel * 10);

                reader.Config.Antennas.setAntennaRfConfig(1, antennaRfConfig);

                promise.resolve(true);
            } else {
                throw new Exception("Reader is not connected");
            }
        } catch (Exception err) {
            promise.reject(err);
        }
    }

    @ReactMethod
    public void programTag(String oldTag, String newTag, Promise promise) {
        Log.d(LOG, "programTag");
        try {
            if (reader != null && reader.isConnected()) {
                if (oldTag != null && newTag != null) {

                    reader.Config.setDPOState(DYNAMIC_POWER_OPTIMIZATION.DISABLE);

                    TagAccess tagAccess = new TagAccess();
                    TagAccess.WriteAccessParams writeAccessParams = tagAccess.new WriteAccessParams();

                    writeAccessParams.setAccessPassword(Long.decode("0X" + "0"));
                    writeAccessParams.setMemoryBank(MEMORY_BANK.MEMORY_BANK_EPC);
                    writeAccessParams.setOffset(2);
                    writeAccessParams.setWriteData(newTag);
                    writeAccessParams.setWriteDataLength(newTag.length() / 4);

                    reader.Actions.TagAccess.writeWait(oldTag, writeAccessParams, null, null);

                    reader.Config.setDPOState(DYNAMIC_POWER_OPTIMIZATION.ENABLE);

                    promise.resolve(true);

                    WritableMap map = Arguments.createMap();
                    map.putBoolean("status", true);
                    map.putString("error", null);
                    sendEvent(WRITE_TAG_STATUS, map);
                }
            } else {
                throw new Exception("Reader is not connected");
            }
        } catch (InvalidUsageException err) {
            WritableMap map = Arguments.createMap();
            map.putBoolean("status", false);
            map.putString("error", err.getInfo());
            sendEvent(WRITE_TAG_STATUS, map);
        } catch (OperationFailureException err) {
            WritableMap map = Arguments.createMap();
            map.putBoolean("status", false);
            map.putString("error", err.getResults().toString());
            sendEvent(WRITE_TAG_STATUS, map);
        } catch (Exception err) {
            promise.reject(err);
        }
    }

    @ReactMethod
    public void setEnabled(boolean enable, Promise promise) {
        Log.d(LOG, "setEnabled");
        isReadingBarcode = !enable;
        try {
            if (reader != null && reader.isConnected()) {
                Thread.sleep(50);

                reader.Config.setTriggerMode(enable ? ENUM_TRIGGER_MODE.RFID_MODE :
                        ENUM_TRIGGER_MODE.BARCODE_MODE, true);

                promise.resolve(true);
            } else {
                throw new Exception("Reader is not connected");
            }
        } catch (Exception err) {
            promise.reject(err);
        }
    }

    @ReactMethod
    public void enableLocateTag(boolean enable, String tag, Promise promise) {
        try {
            if (reader != null && reader.isConnected()) {
                isLocateMode = enable;

                locateTag = tag;

                if (!enable) {
                    ConfigureReader();
                } else {
                    ConfigureLocateMode();
                }

                promise.resolve(true);
            } else {
                throw new Exception("Reader is not connected");
            }
        } catch (Exception err) {
            promise.reject(err);
        }
    }

    @ReactMethod
    public void softReadCancel(boolean enable, Promise promise) {
        try {
            if (enable) {
                read();
            } else {
                cancel();
            }
        } catch (Exception err) {
            promise.reject(err);
        }
    }

    private final RfidEventsListener rEventListener = new RfidEventsListener() {
        @Override
        public void eventReadNotify(RfidReadEvents rfidReadEvents) {
            // Recommended to use new method getReadTagsEx for better performance in case of large tag population
            TagData[] myTags = reader.Actions.getReadTags(100);

            if (myTags != null && myTags.length > 0) {
                WritableArray array = Arguments.createArray();

                for (TagData myTag : myTags) {
                    Log.d("RFID", "Tag ID = " + myTag.getTagID());
                    int rssi = myTag.getPeakRSSI();
                    String EPC = myTag.getTagID();

                    if (myTag.getOpCode() == ACCESS_OPERATION_CODE.ACCESS_OPERATION_READ &&
                            myTag.getOpStatus() == ACCESS_OPERATION_STATUS.ACCESS_SUCCESS) {
                        if (myTag.getMemoryBankData().length() > 0) {
                            Log.d(LOG, " Mem Bank Data " + myTag.getMemoryBankData());
                        }
                    }

                    if (myTag.isContainsLocationInfo()) {
                        short dist = myTag.LocationInfo.getRelativeDistance();
                        Log.d(LOG, "Tag relative distance " + dist);

                        WritableMap event = Arguments.createMap();
                        event.putInt("distance", dist);
                        sendEvent(LOCATE_TAG, event);
                    }

                    if (isSingleRead) {
                        if (rssi > -50) {
                            if (addTagToList(EPC) && cacheTags.size() == 1) {
                                cancel();

                                sendEvent(TAG, EPC);
                            }
                        }
                    } else {
                        if (addTagToList(EPC)) {
                            array.pushString(EPC);
//                            sendEvent(TAG, EPC);
                        }
                    }
                }

                if (array.size() > 0)
                    sendEvent(TAGS, array);
            }
        }

        @Override
        public void eventStatusNotify(RfidStatusEvents rfidStatusEvents) {
            Log.d(LOG, "eventStatusNotify: " + rfidStatusEvents.StatusEventData.getStatusEventType());
            if (rfidStatusEvents.StatusEventData.getStatusEventType() == STATUS_EVENT_TYPE.BATTERY_EVENT) {
                WritableMap map = Arguments.createMap();
                map.putInt("level", (int) rfidStatusEvents.StatusEventData.BatteryData.getLevel());
                map.putString("cause", rfidStatusEvents.StatusEventData.BatteryData.getCause());
                sendEvent(BATTERY_STATUS, map);
            } else if (rfidStatusEvents.StatusEventData.getStatusEventType() == STATUS_EVENT_TYPE.POWER_EVENT) {
                Log.d(LOG, "POWER: " + rfidStatusEvents.StatusEventData.PowerData.getPower());
            } else if (rfidStatusEvents.StatusEventData.getStatusEventType() == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
                if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent() == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                    if (isLocateMode) {
                        executeLocateTag(true);
                    } else {
                        read();
                    }
                } else if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent() == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
                    if (isLocateMode) {
                        executeLocateTag(false);
                    } else {
                        cancel();
                    }
                }

                WritableMap map = Arguments.createMap();
                map.putBoolean("status", rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent() == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED);
                sendEvent(TRIGGER_STATUS, map);
            } else if (rfidStatusEvents.StatusEventData.getStatusEventType() == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
                Log.d(LOG, "Reader Disconnected: " + readerDevice.getName());

                WritableMap map = Arguments.createMap();
                map.putBoolean("status", false);
                map.putString("error", null);
                sendEvent(READER_STATUS, map);
            }
        }
    };

    private final RFIDReaderEventHandler rEventHandler = new RFIDReaderEventHandler() {
        @Override
        public void RFIDReaderAppeared(ReaderDevice readerDevice) {
            Log.d(LOG, "Reader Appeared: " + readerDevice.getName());
        }

        @Override
        public void RFIDReaderDisappeared(ReaderDevice readerDevice) {
            Log.d(LOG, "Reader Disappeared: " + readerDevice.getName());
            if (readerDevice.getName().equals(reader.getHostName()))
                doDisconnect();
        }
    };

    private final IDcsSdkApiDelegate bDelegate = new IDcsSdkApiDelegate() {
        @Override
        public void dcssdkEventScannerAppeared(DCSScannerInfo dcsScannerInfo) {
            Log.d(LOG, "Scanner Appeared: " + dcsScannerInfo.getScannerName());
        }

        @Override
        public void dcssdkEventScannerDisappeared(int i) {
            Log.d(LOG, "Scanner Disappeared: " + scanner.getScannerName());
        }

        @Override
        public void dcssdkEventCommunicationSessionEstablished(DCSScannerInfo dcsScannerInfo) {
            Log.d(LOG, "Scanner Connected: " + scanner.getScannerName());
        }

        @Override
        public void dcssdkEventCommunicationSessionTerminated(int i) {
            Log.d(LOG, "Scanner Disconnected: " + scanner.getScannerName());
        }

        @Override
        public void dcssdkEventBarcode(byte[] bytes, int i, int i1) {
            sendEvent(BARCODE, new String(bytes));
        }

        @Override
        public void dcssdkEventImage(byte[] bytes, int i) {

        }

        @Override
        public void dcssdkEventVideo(byte[] bytes, int i) {

        }

        @Override
        public void dcssdkEventBinaryData(byte[] bytes, int i) {

        }

        @Override
        public void dcssdkEventFirmwareUpdate(FirmwareUpdateEvent firmwareUpdateEvent) {

        }

        @Override
        public void dcssdkEventAuxScannerAppeared(DCSScannerInfo dcsScannerInfo, DCSScannerInfo dcsScannerInfo1) {

        }
    };

    BroadcastReceiver bReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Bundle extras = intent.getExtras();
            String actionName = context.getPackageName() + ".ACTION";

            if (action.equals(actionName)) {
                String decodedSource = intent.getStringExtra("com.symbol.datawedge.source");
                String decodedData = intent.getStringExtra("com.symbol.datawedge.data_string");
                String decodedLabelType = intent.getStringExtra("com.symbol.datawedge.label_type");

                if (!decodedData.isEmpty()) {
                    sendEvent(BARCODE, decodedData);
                }
            }
        }
    };

    private void init() {
        Log.d(LOG, "init");

        //RFID
        if (readers == null) {
            readers = new Readers(this.reactContext, ENUM_TRANSPORT.ALL);

            Readers.attach(rEventHandler);
        }

        //Barcode
        if (sdkHandler == null) {
            sdkHandler = new SDKHandler(this.reactContext);

            sdkHandler.dcssdkSetDelegate(bDelegate);

            sdkHandler.dcssdkEnableAvailableScannersDetection(true);
            sdkHandler.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_BT_NORMAL);
            sdkHandler.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_SNAPI);
            sdkHandler.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_BT_LE);
            sdkHandler.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_USB_CDC);

            notifications_mask |= (DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_APPEARANCE.value
                    | DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_DISAPPEARANCE.value);
            notifications_mask |= (DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_ESTABLISHMENT.value
                    | DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_TERMINATION.value);
            notifications_mask |= (DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_BARCODE.value);
        }
    }

    private void dispose() {
        Log.d(LOG, "dispose");

        try {
            if (readers != null) {
                reader = null;
                readers.Dispose();
                readers = null;
                readerDevice = null;
            }

            if (sdkHandler != null) {
                sdkHandler.dcssdkClose();

                scanner = null;
                sdkHandler = null;
                notifications_mask = 0;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void doConnect() {
        try {
            if (reader != null) {
                // Establish connection to the RFID Reader
                reader.connect();

                ConfigureReader();

                Log.d(LOG, "Reader Connected: " + reader.getHostName());
            }

            if (sdkHandler != null && scanner != null && !scanner.isActive()) {
                sdkHandler.dcssdkSubsribeForEvents(notifications_mask);
                sdkHandler.dcssdkEstablishCommunicationSession(scanner.getScannerID());

                String actionName = this.reactContext.getApplicationContext().getPackageName() + ".ACTION";
                IntentFilter filter = new IntentFilter();
                filter.addAction(actionName);
                filter.addCategory(Intent.CATEGORY_DEFAULT);
                this.reactContext.registerReceiver(bReceiver, filter);

                setDataWedgeProfile();
            }

            WritableMap map = Arguments.createMap();
            map.putBoolean("status", true);
            map.putString("error", null);
            sendEvent(READER_STATUS, map);
        } catch (InvalidUsageException err) {
            WritableMap map = Arguments.createMap();
            map.putBoolean("status", false);
            map.putString("error", err.getInfo());
            sendEvent(READER_STATUS, map);
        } catch (OperationFailureException err) {
            if (err.getResults() == RFIDResults.RFID_READER_REGION_NOT_CONFIGURED) {
                setRegion("AUS");

                doConnect();
            } else {
                WritableMap map = Arguments.createMap();
                map.putBoolean("status", false);
                map.putString("error", err.getResults().toString());
                sendEvent(READER_STATUS, map);
            }
        } catch (Exception err) {
            WritableMap map = Arguments.createMap();
            map.putBoolean("status", false);
            map.putString("error", err.getMessage());
            sendEvent(READER_STATUS, map);
        }
    }

    private void doReconnect() {
        Log.d(LOG, "doReconnect " + reader);

        if (reader != null) {
            try {
                reader.reconnect();

                WritableMap map = Arguments.createMap();
                map.putBoolean("status", true);
                map.putString("error", null);
                sendEvent(READER_STATUS, map);
            } catch (OperationFailureException err) {
                WritableMap map = Arguments.createMap();
                map.putBoolean("status", false);
                map.putString("error", err.getResults().toString());
                sendEvent(READER_STATUS, map);
            } catch (InvalidUsageException err) {
                WritableMap map = Arguments.createMap();
                map.putBoolean("status", false);
                map.putString("error", err.getInfo());
                sendEvent(READER_STATUS, map);
            } catch (Exception err) {
                WritableMap map = Arguments.createMap();
                map.putBoolean("status", false);
                map.putString("error", err.getMessage());
                sendEvent(READER_STATUS, map);
            }
        }
    }

    private void doDisconnect() {
        Log.d(LOG, "doDisconnect " + reader);

        try {
            if (reader != null && reader.isConnected()) {
                reader.Events.removeEventsListener(rEventListener);

                reader.disconnect();
            }

            if (sdkHandler != null && scanner != null && scanner.isActive()) {
                sdkHandler.dcssdkUnsubsribeForEvents(notifications_mask);
//                DCSSDKDefs.DCSSDK_RESULT result = DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_FAILURE;
//                DCSSDKDefs.DCSSDK_RESULT result =
                sdkHandler.dcssdkTerminateCommunicationSession(scanner.getScannerID());
            }

            if (readers != null) {
                reader = null;
                readers.Dispose();
                readers = null;
                readerDevice = null;
            }

            try {
                this.reactContext.unregisterReceiver(bReceiver);
            } catch (Exception err) {
                err.printStackTrace();
            }

            if (sdkHandler != null) {
                sdkHandler.dcssdkClose();

                scanner = null;
                sdkHandler = null;
                notifications_mask = 0;
            }

            WritableMap map = Arguments.createMap();
            map.putBoolean("status", false);
            map.putString("error", null);
            sendEvent(READER_STATUS, map);
        } catch (OperationFailureException err) {
            WritableMap map = Arguments.createMap();
            map.putBoolean("status", false);
            map.putString("error", err.getResults().toString());
            sendEvent(READER_STATUS, map);
        } catch (InvalidUsageException err) {
            WritableMap map = Arguments.createMap();
            map.putBoolean("status", false);
            map.putString("error", err.getInfo());
            sendEvent(READER_STATUS, map);
        } catch (Exception err) {
            WritableMap map = Arguments.createMap();
            map.putBoolean("status", false);
            map.putString("error", err.getMessage());
            sendEvent(READER_STATUS, map);
        }
    }

    private void setDataWedgeProfile() {
        String name = this.reactContext.getApplicationContext().getPackageName();
        String actionName = name + ".ACTION";

        Bundle bMain = new Bundle();
        bMain.putString("PROFILE_NAME", "Tracksmart");
        bMain.putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST");
        bMain.putString("PROFILE_ENABLED", "true");

        Bundle bConfig = new Bundle();
        bConfig.putString("PLUGIN_NAME", "BARCODE");
        bConfig.putString("RESET_CONFIG", "true");

        Bundle bConfig2 = new Bundle();
        bConfig2.putString("PLUGIN_NAME", "INTENT");
        bConfig2.putString("RESET_CONFIG", "true");

        Bundle bParams = new Bundle();
        bParams.putString("scanner_input_enabled", "true");
        //Set Barcode to silent mode
        bParams.putString("decode_audio_feedback_uri", "");

        Bundle bParams2 = new Bundle();
        bParams2.putString("intent_output_enabled", "true");
        bParams2.putString("intent_action", actionName);
        bParams2.putString("intent_category", Intent.CATEGORY_DEFAULT);
        bParams2.putString("intent_delivery", "2");

        //PUT bParams into bConfig
        bConfig.putBundle("PARAM_LIST", bParams);
        bConfig2.putBundle("PARAM_LIST", bParams2);

        //PUT bConfig into bMain
        bMain.putBundle("PLUGIN_CONFIG", bConfig);
        bMain.putBundle("PLUGIN_CONFIG", bConfig2);

        Bundle bundleApp1 = new Bundle();
        bundleApp1.putString("PACKAGE_NAME", name);
        bundleApp1.putStringArray("ACTIVITY_LIST", new String[]{"*"});

        bMain.putParcelableArray("APP_LIST", new Bundle[]{bundleApp1});

        Intent i = new Intent();
        i.setAction("com.symbol.datawedge.api.ACTION");
        i.putExtra("com.symbol.datawedge.api.SET_CONFIG", bMain);

        this.reactContext.sendBroadcast(i);
    }

    private void ConfigureLocateMode() throws Exception {
        reader.Config.setBeeperVolume(BEEPER_VOLUME.HIGH_BEEP);
    }

    private void ConfigureReader() throws Exception {

//			reader.Config.resetFactoryDefaults();

        TriggerInfo triggerInfo = new TriggerInfo();
        triggerInfo.StartTrigger.setTriggerType(START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE);
        triggerInfo.StopTrigger.setTriggerType(STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE);

        reader.Events.setReaderDisconnectEvent(true);
        reader.Events.setInventoryStartEvent(true);
        reader.Events.setInventoryStopEvent(true);
        reader.Events.setPowerEvent(true);
        reader.Events.setOperationEndSummaryEvent(true);

        //Battery event
        reader.Events.setBatteryEvent(true);
        // HH event. Control active reader
        reader.Events.setHandheldEvent(true);
        // tag event with tag data
        reader.Events.setTagReadEvent(true);
        reader.Events.setAttachTagDataWithReadEvent(false);

        //Disable batch mode
        reader.Events.setBatchModeEvent(false);
        reader.Config.setBatchMode(BATCH_MODE.DISABLE);

        //Turn Off beeper
        reader.Config.setBeeperVolume(BEEPER_VOLUME.QUIET_BEEP);

        // set trigger mode as rfid so scanner beam will not come
        reader.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true);
        // set start and stop triggers
        reader.Config.setStartTrigger(triggerInfo.StartTrigger);
        reader.Config.setStopTrigger(triggerInfo.StopTrigger);
        //set DPO enable
        reader.Config.setDPOState(DYNAMIC_POWER_OPTIMIZATION.ENABLE);
        // power levels are index based so maximum power supported get the last one
//			MAX_POWER = reader.ReaderCapabilities.getTransmitPowerLevelValues().length - 1;
        // set antenna configuration
        Antennas.AntennaRfConfig config = reader.Config.Antennas.getAntennaRfConfig(1);
//			config.setTransmitPowerIndex(MAX_POWER);
        config.setrfModeTableIndex(0);
        config.setTari(0);
        reader.Config.Antennas.setAntennaRfConfig(1, config);
        // Set the singulation control
        Antennas.SingulationControl s1_singulationControl = reader.Config.Antennas.getSingulationControl(1);
        s1_singulationControl.setSession(SESSION.SESSION_S1);
        s1_singulationControl.Action.setInventoryState(INVENTORY_STATE.INVENTORY_STATE_A);
        s1_singulationControl.Action.setSLFlag(SL_FLAG.SL_ALL);
        reader.Config.Antennas.setSingulationControl(1, s1_singulationControl);
        // delete any prefilters
        reader.Actions.PreFilters.deleteAll();

        // receive events from reader
        reader.Events.addEventsListener(rEventListener);

        reader.Config.getDeviceStatus(true, true, false);
        Log.d("ConfigureReader", "Default ConfigureReader Finished" + reader.getHostName());
    }

    private void setRegion(String region) {
        try {
            if (reader != null) {
                RegulatoryConfig regulatoryConfig = reader.Config.getRegulatoryConfig();

                SupportedRegions regions = reader.ReaderCapabilities.SupportedRegions;

                int len = regions.length();
                for (int i = 0; i < len; i++) {
                    RegionInfo regionInfo = regions.getRegionInfo(i);
                    if (region.equals(regionInfo.getRegionCode())) {
                        regulatoryConfig.setRegion(regionInfo.getRegionCode());
                        reader.Config.setRegulatoryConfig(regulatoryConfig);
                        Log.d("RFID", "Region set to " + regionInfo.getName());
                    }
                }
            }
        } catch (OperationFailureException err) {
            WritableMap map = Arguments.createMap();
            map.putBoolean("status", false);
            map.putString("error", err.getResults().toString());
            sendEvent(READER_STATUS, map);
        } catch (InvalidUsageException err) {
            WritableMap map = Arguments.createMap();
            map.putBoolean("status", false);
            map.putString("error", err.getInfo());
            sendEvent(READER_STATUS, map);
        }
    }

    private void read() {
        if (reader != null && reader.isConnected() && !isReadingBarcode) {
            try {
                reader.Actions.Inventory.perform();
            } catch (InvalidUsageException | OperationFailureException e) {
                e.printStackTrace();
            }
        }
    }

    private void cancel() {
        if (reader != null && reader.isConnected()) {
            try {
                reader.Actions.Inventory.stop();
            } catch (InvalidUsageException | OperationFailureException e) {
                e.printStackTrace();
            }
        }
    }

//    private final Handler mLoopHandler = new Handler(Looper.getMainLooper());

    private void executeLocateTag(boolean isStart) {
        if (reader != null && reader.isConnected()) {

            if (isStart) {
                if (locateTag != null && !isLocatingTag) {
                    isLocatingTag = true;

//                    mLoopHandler.post(new Runnable() {
//                        @Override
//                        public void run() {
//                            mLoopHandler.removeCallbacks(this);
//                            try {
//                                reader.Actions.TagLocationing.Perform(locateTag, null, null);
//                            } catch (InvalidUsageException err) {
//                                WritableMap map = Arguments.createMap();
//                                map.putString("error", err.getInfo());
//                                sendEvent(LOCATE_TAG, map);
//                            } catch (OperationFailureException err) {
//                                WritableMap map = Arguments.createMap();
//                                map.putString("error", err.getResults().toString());
//                                sendEvent(LOCATE_TAG, map);
//                            }
//                        }
//                    });

                    new AsyncTask<Void, Void, Boolean>() {
                        private InvalidUsageException invalidUsageException;
                        private OperationFailureException operationFailureException;

                        @Override
                        protected Boolean doInBackground(Void... voids) {
                            try {
                                reader.Actions.TagLocationing.Perform(locateTag, null, null);
                            } catch (InvalidUsageException e) {
                                e.printStackTrace();
                                invalidUsageException = e;
                            } catch (OperationFailureException e) {
                                e.printStackTrace();
                                operationFailureException = e;
                            }
                            return null;
                        }

                        @Override
                        protected void onPostExecute(Boolean result) {
                            if (invalidUsageException != null) {
                                WritableMap map = Arguments.createMap();
                                map.putString("error", invalidUsageException.getInfo());
                                sendEvent(LOCATE_TAG, map);
                            } else if (operationFailureException != null) {
                                WritableMap map = Arguments.createMap();
                                map.putString("error", operationFailureException.getVendorMessage());
                                sendEvent(LOCATE_TAG, map);
                            }
                        }
                    }.execute();
                }
            } else {
                isLocatingTag = false;

//                mLoopHandler.post(new Runnable() {
//                    @Override
//                    public void run() {
//                        mLoopHandler.removeCallbacks(this);
//                        try {
//                            reader.Actions.TagLocationing.Stop();
//                        } catch (InvalidUsageException err) {
//                            WritableMap map = Arguments.createMap();
//                            map.putString("error", err.getInfo());
//                            sendEvent(LOCATE_TAG, map);
//                        } catch (OperationFailureException err) {
//                            WritableMap map = Arguments.createMap();
//                            map.putString("error", err.getResults().toString());
//                            sendEvent(LOCATE_TAG, map);
//                        }
//                    }
//                });

                new AsyncTask<Void, Void, Boolean>() {
                    private InvalidUsageException invalidUsageException;
                    private OperationFailureException operationFailureException;

                    @Override
                    protected Boolean doInBackground(Void... voids) {
                        try {
                            reader.Actions.TagLocationing.Stop();
                        } catch (InvalidUsageException e) {
                            invalidUsageException = e;
                            e.printStackTrace();
                        } catch (OperationFailureException e) {
                            operationFailureException = e;
                            e.printStackTrace();
                        }
                        return null;
                    }

                    @Override
                    protected void onPostExecute(Boolean result) {
                        if (invalidUsageException != null) {
                            WritableMap map = Arguments.createMap();
                            map.putString("error", invalidUsageException.getInfo());
                            sendEvent(LOCATE_TAG, map);
                        } else if (operationFailureException != null) {
                            WritableMap map = Arguments.createMap();
                            map.putString("error", operationFailureException.getVendorMessage());
                            sendEvent(LOCATE_TAG, map);
                        }
                    }
                }.execute();
            }
        }
    }

    private boolean addTagToList(String strEPC) {
        if (strEPC != null) {
            if (!cacheTags.contains(strEPC)) {
                cacheTags.add(strEPC);
                return true;
            }
        }
        return false;
    }
}
