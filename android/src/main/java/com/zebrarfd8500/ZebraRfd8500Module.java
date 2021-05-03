package com.zebrarfd8500;

import android.os.AsyncTask;
import android.util.Log;
import android.widget.Toast;

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

import java.util.ArrayList;

public class ZebraRfd8500Module extends ReactContextBaseJavaModule implements LifecycleEventListener, Readers.RFIDReaderEventHandler, RfidEventsListener {

    private final ReactApplicationContext reactContext;

    private final String LOG = "[RFD8500]";
    private final String READER_STATUS = "READER_STATUS";
    private final String TRIGGER_STATUS = "TRIGGER_STATUS";
    private final String WRITE_TAG_STATUS = "WRITE_TAG_STATUS";
    private final String BATTERY_STATUS = "BATTERY_STATUS";
    private final String TAG = "TAG";
    private final String LOCATE_TAG = "LOCATE_TAG";

    private static Readers readers;
    private static ReaderDevice readerDevice;
    private static RFIDReader reader;
    private static ArrayList<String> cacheTags = new ArrayList<>();
    private static boolean isSingleRead = false;

    private static boolean isLocatingTag = false;
    private static boolean isLocateMode = false;
    private static String locateTag = null;

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

    @Override
    public String getName() {
        return "ZebraRfd8500";
    }

    @Override
    public void onHostResume() {
//		doConnect();
    }

    @Override
    public void onHostPause() {
//		doDisconnect();
    }

    @Override
    public void onHostDestroy() {
        doDisconnect();

        dispose();
    }

    @Override
    public void RFIDReaderAppeared(ReaderDevice readerDevice) {
        Log.d(LOG, "RFIDReaderAppeared " + readerDevice.getName());
    }

    @Override
    public void RFIDReaderDisappeared(ReaderDevice readerDevice) {
        Log.d(LOG, "RFIDReaderDisappeared " + readerDevice.getName());
        if (readerDevice.getName().equals(reader.getHostName()))
            doDisconnect();
    }

    @Override
    public void eventReadNotify(RfidReadEvents rfidReadEvents) {
        // Recommended to use new method getReadTagsEx for better performance in case of large tag population
        TagData[] myTags = reader.Actions.getReadTags(100);

        if (myTags != null) {
            for (TagData myTag : myTags) {
                Log.d(LOG, "Tag ID " + myTag.getTagID());
                if (myTag.getOpCode() == ACCESS_OPERATION_CODE.ACCESS_OPERATION_READ &&
                        myTag.getOpStatus() == ACCESS_OPERATION_STATUS.ACCESS_SUCCESS) {
                    if (myTag.getMemoryBankData().length() > 0) {
                        Log.d(LOG, " Mem Bank Data " + myTag.getMemoryBankData());
                    }
                }

                int rssi = myTag.getPeakRSSI();
                String EPC = myTag.getTagID();
                Log.d("RFID", "Tag ID = " + EPC);

                if (isSingleRead) {
                    if (rssi > -50) {
                        if (addTagToList(EPC)) {
                            sendEvent(TAG, EPC);
                            cancel();
                        }
                    }
                } else {
                    if (addTagToList(EPC)) {
                        sendEvent(TAG, EPC);
                    }
                }

                if (myTag.isContainsLocationInfo()) {
                    short dist = myTag.LocationInfo.getRelativeDistance();
                    Log.d(LOG, "Tag relative distance " + dist);

                    WritableMap event = Arguments.createMap();
                    event.putInt("distance", dist);
                    sendEvent(LOCATE_TAG, event);
                }
            }
        }
    }

    @Override
    public void eventStatusNotify(RfidStatusEvents rfidStatusEvents) {
        if (rfidStatusEvents.StatusEventData.getStatusEventType() == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
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
        }

        if (rfidStatusEvents.StatusEventData.getStatusEventType() == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
            WritableMap map = Arguments.createMap();
            map.putBoolean("status", false);
            sendEvent(READER_STATUS, map);
        }

        if (rfidStatusEvents.StatusEventData.getStatusEventType() == STATUS_EVENT_TYPE.BATTERY_EVENT) {
            WritableMap map = Arguments.createMap();
            map.putInt("level", (int) rfidStatusEvents.StatusEventData.BatteryData.getLevel());
            map.putString("cause", rfidStatusEvents.StatusEventData.BatteryData.getCause());
            sendEvent(BATTERY_STATUS, map);
        }

        if (rfidStatusEvents.StatusEventData.getStatusEventType() == STATUS_EVENT_TYPE.TEMPERATURE_ALARM_EVENT) {
//            temperature = rfidStatusEvents.StatusEventData.TemperatureAlarmData.getCurrentTemperature();
        }
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
            if (readers == null) {
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
        } catch (InvalidUsageException e) {
            promise.reject(e);
        }
    }

    @ReactMethod
    public void disconnect(Promise promise) {
        Log.d(LOG, "disconnect");

        if (reader != null && reader.isConnected()) {
            doDisconnect();
        }

        promise.resolve(true);
    }

    @ReactMethod
    public void reconnect() {
        doConnect();
    }

    @ReactMethod
    public void connect(String name, Promise promise) {
        Log.d(LOG, "connect");

        try {
            if (readers == null) {
                init();
            }

            ArrayList<ReaderDevice> devices = readers.GetAvailableRFIDReaderList();

            for (ReaderDevice device : devices) {
                if (device.getName().equals(name)) {
                    readerDevice = device;
                    reader = device.getRFIDReader();
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

        if (reader != null && reader.isConnected()) {
            try {
                reader.Config.getDeviceStatus(true, false, false);

                Antennas.AntennaRfConfig antennaRFConfig = reader.Config.Antennas.getAntennaRfConfig(1);
                int antennaLevel = antennaRFConfig.getTransmitPowerIndex();

                WritableMap map = Arguments.createMap();
                map.putString("name", readerDevice.getName());
                map.putString("mac", readerDevice.getAddress());
                map.putInt("antennaLevel", antennaLevel / 10);

                promise.resolve(map);
            } catch (InvalidUsageException e) {
                e.printStackTrace();
            } catch (OperationFailureException e) {
                e.printStackTrace();
                promise.reject(e);
            }
        }

        promise.reject(LOG, "Fail to device details");
    }

    @ReactMethod
    public void clear() {
        Log.d(LOG, "clear");

        cacheTags = new ArrayList<>();
    }

    @ReactMethod
    public void setSingleRead(boolean enable) {
        Log.d(LOG, "setSingleRead");

        isSingleRead = enable;
    }

    @ReactMethod
    public void setAntennaLevel(int antennaLevel, Promise promise) {
        Log.d(LOG, "setAntennaLevel");

        if (reader != null && reader.isConnected()) {
            Antennas.AntennaRfConfig antennaRfConfig = null;
            try {
                antennaRfConfig = reader.Config.Antennas
                        .getAntennaRfConfig(1);

                antennaRfConfig.setTransmitPowerIndex(antennaLevel * 10);
                reader.Config.Antennas.setAntennaRfConfig(1, antennaRfConfig);

                promise.resolve(true);
            } catch (InvalidUsageException e) {
                e.printStackTrace();
            } catch (OperationFailureException e) {
                e.printStackTrace();
                promise.reject(e);
            }
        }

        promise.reject(LOG, "Fail to change antenna level");
    }

    @ReactMethod
    public void programTag(String oldTag, String newTag, Promise promise) {
        Log.d(LOG, "programTag");

        if (reader != null && reader.isConnected()) {
            if (oldTag != null && newTag != null) {
                try {
                    reader.Config.setDPOState(DYNAMIC_POWER_OPTIMIZATION.DISABLE);

                    TagAccess tagAccess = new TagAccess();
                    final TagAccess.WriteAccessParams writeAccessParams = tagAccess.new WriteAccessParams();

                    writeAccessParams.setAccessPassword(Long.decode("0X" + "0"));
                    writeAccessParams.setMemoryBank(MEMORY_BANK.MEMORY_BANK_EPC);
                    writeAccessParams.setOffset(2);
                    writeAccessParams.setWriteData(newTag);
                    writeAccessParams.setWriteDataLength(newTag.length() / 4);

                    String error = null;
                    try {
                        reader.Actions.TagAccess.writeWait(oldTag, writeAccessParams, null, null);
                    } catch (InvalidUsageException e) {
                        e.printStackTrace();
                        error = e.getInfo();
                    } catch (OperationFailureException e) {
                        e.printStackTrace();
                        error = e.getVendorMessage();
                    } catch (Exception e) {
                        error = e.getMessage();
                    }

                    WritableMap map = Arguments.createMap();
                    map.putBoolean("status", error == null);
                    map.putString("error", error);
                    sendEvent(WRITE_TAG_STATUS, map);

                    promise.resolve(true);
                } catch (InvalidUsageException | OperationFailureException e) {
                    e.printStackTrace();
                }
            }
        }

        promise.reject(LOG, "Fail to program tag");
    }

    @ReactMethod
    public void setEnabled(boolean enable, Promise promise) {
        Log.d(LOG, "setEnabled");

        try {
            if (reader != null && reader.isConnected()) {
//				reader.Events.setTagReadEvent(enable);
//				reader.Events.setInventoryStartEvent(enable);
//				reader.Events.setInventoryStartEvent(enable);

                reader.Config.setTriggerMode(enable ? ENUM_TRIGGER_MODE.RFID_MODE :
                        ENUM_TRIGGER_MODE.BARCODE_MODE, true);
            }

            promise.resolve(true);
        } catch (Exception err) {
            promise.reject(err);
        }

    }

    @ReactMethod
    public void enableLocateTag(boolean enable, String tag, Promise promise) {
        try {
            isLocateMode = enable;

            locateTag = tag;

            if (!enable) {
                ConfigureReader();
            }

            promise.resolve(true);
        } catch (Exception err) {
            promise.reject(err);
        }

    }

    private void init() {
        Log.d(LOG, "init");

        if (readers == null) {
            readers = new Readers(this.reactContext, ENUM_TRANSPORT.BLUETOOTH);
            readers.attach(this);
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
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void doConnect() {
        if (reader != null && !reader.isConnected()) {

            String error = "Connection failed";
            try {
                // Establish connection to the RFID Reader
                reader.connect();
                ConfigureReader();

                Log.d(LOG, reader.getHostName() + " is connected");
                error = null;

            } catch (InvalidUsageException e) {
                e.printStackTrace();
            } catch (OperationFailureException e) {
                e.printStackTrace();
                if (e.getResults() == RFIDResults.RFID_READER_REGION_NOT_CONFIGURED) {
                    try {
                        RegulatoryConfig regulatoryConfig = reader.Config.getRegulatoryConfig();

                        SupportedRegions regions = reader.ReaderCapabilities.SupportedRegions;
                        int len = regions.length();
                        for (int i = 0; i < len; i++) {
                            RegionInfo regionInfo = regions.getRegionInfo(i);
                            if ("AUS".equals(regionInfo.getRegionCode())) {
                                regulatoryConfig.setRegion(regionInfo.getRegionCode());
                                reader.Config.setRegulatoryConfig(regulatoryConfig);
                                Log.d("RFID", "Region set to " + regionInfo.getName());
                            }
                        }
                    } catch (InvalidUsageException | OperationFailureException invalidUsageException) {
                        invalidUsageException.printStackTrace();
                    }
                } else {
                    error = e.getResults().toString();
                }
            } catch (Exception e) {
                e.printStackTrace();
                error = e.getMessage();
            }

            WritableMap map = Arguments.createMap();
            map.putBoolean("status", error == null);
            map.putString("error", error);
            sendEvent(READER_STATUS, map);
//
//            new Thread(new Runnable() {
//                @Override
//                public void run() {
//                    String error = "Connection failed";
//                    try {
//                        // Establish connection to the RFID Reader
//                        reader.connect();
//                        ConfigureReader();
//
//                        Log.d(LOG, reader.getHostName() + " is connected");
//                        error = null;
//
//                    } catch (InvalidUsageException e) {
//                        e.printStackTrace();
//                    } catch (OperationFailureException e) {
//                        e.printStackTrace();
//                        if (e.getResults() == RFIDResults.RFID_READER_REGION_NOT_CONFIGURED) {
//                            try {
//                                RegulatoryConfig regulatoryConfig = reader.Config.getRegulatoryConfig();
//
//                                SupportedRegions regions = reader.ReaderCapabilities.SupportedRegions;
//                                int len = regions.length();
//                                for (int i = 0; i < len; i++) {
//                                    RegionInfo regionInfo = regions.getRegionInfo(i);
//                                    if ("AUS".equals(regionInfo.getRegionCode())) {
//                                        regulatoryConfig.setRegion(regionInfo.getRegionCode());
//                                        reader.Config.setRegulatoryConfig(regulatoryConfig);
//                                        Log.d("RFID", "Region set to " + regionInfo.getName());
//                                    }
//                                }
//                            } catch (InvalidUsageException | OperationFailureException invalidUsageException) {
//                                invalidUsageException.printStackTrace();
//                            }
//                        } else {
//                            error = e.getResults().toString();
//                        }
//                    } catch (Exception e) {
//                        e.printStackTrace();
//                        error = e.getMessage();
//                    }
//
//                    WritableMap map = Arguments.createMap();
//                    map.putBoolean("status", error == null);
//                    map.putString("error", error);
//                    sendEvent(READER_STATUS, map);
//                }
//            }).start();
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
            } catch (InvalidUsageException | OperationFailureException e) {
                WritableMap map = Arguments.createMap();
                map.putBoolean("status", false);
                map.putString("error", e.getMessage());
                sendEvent(READER_STATUS, map);
            }
        }
    }

    private void doDisconnect() {
        Log.d(LOG, "doDisconnect " + reader);

        if (reader != null && reader.isConnected()) {
            try {
                reader.Events.removeEventsListener(this);

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String error = null;
                        try {
                            reader.disconnect();
                            Log.d(LOG, reader.getHostName() + "is disconnected ");

                        } catch (InvalidUsageException | OperationFailureException e) {
                            error = e.getMessage();
                        }

                        WritableMap map = Arguments.createMap();
                        map.putBoolean("status", false);
                        map.putString("error", error);
                        sendEvent(READER_STATUS, map);
                    }
                }).start();

            } catch (InvalidUsageException | OperationFailureException e) {
                WritableMap map = Arguments.createMap();
                map.putBoolean("status", false);
                map.putString("error", e.getMessage());
                sendEvent(READER_STATUS, map);
            }
        }
    }

    private void ConfigureReader() throws Exception {

        if (reader.isConnected()) {
//			reader.Config.resetFactoryDefaults();

            TriggerInfo triggerInfo = new TriggerInfo();
            triggerInfo.StartTrigger.setTriggerType(START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE);
            triggerInfo.StopTrigger.setTriggerType(STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE);

            // receive events from reader
            reader.Events.addEventsListener(this);

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
            reader.Config.setBeeperVolume(BEEPER_VOLUME.HIGH_BEEP);

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
            s1_singulationControl.setSession(SESSION.SESSION_S0);
            s1_singulationControl.Action.setInventoryState(INVENTORY_STATE.INVENTORY_STATE_A);
            s1_singulationControl.Action.setSLFlag(SL_FLAG.SL_ALL);
            reader.Config.Antennas.setSingulationControl(1, s1_singulationControl);
            // delete any prefilters
            reader.Actions.PreFilters.deleteAll();

            reader.Config.getDeviceStatus(true, false, false);
            Log.d("ConfigureReader", "Default ConfigureReader Finished" + reader.getHostName());
        }
    }

    private void read() {
        if (reader != null && reader.isConnected()) {
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

    private void executeLocateTag(boolean isStart) {
        if (reader != null && reader.isConnected()) {
            if (isStart) {
                if (locateTag != null && !isLocatingTag) {
                    isLocatingTag = true;

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
