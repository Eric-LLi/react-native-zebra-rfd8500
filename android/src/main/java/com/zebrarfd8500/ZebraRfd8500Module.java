package com.zebrarfd8500;

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
import java.util.Objects;

public class ZebraRfd8500Module extends ReactContextBaseJavaModule implements LifecycleEventListener, Readers.RFIDReaderEventHandler, RfidEventsListener {

	private final ReactApplicationContext reactContext;

	private final String LOG = "ZEBRA";
	private final String READER_STATUS = "READER_STATUS";
	private final String TRIGGER_STATUS = "TRIGGER_STATUS";
	private final String WRITE_TAG_STATUS = "WRITE_TAG_STATUS";
	private final String TAG = "TAG";
	private final String LOCATE_TAG = "LOCATE_TAG";
	private int MAX_POWER = 270;

	private static Readers readers;
	private static ReaderDevice readerDevice;
	private static RFIDReader reader;
	private static ArrayList<String> cacheTags = new ArrayList<>();

	private static boolean isSingleRead = false;

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
		doConnect();
	}

	@Override
	public void onHostPause() {
		doDisconnect();
	}

	@Override
	public void onHostDestroy() {
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
				Log.i("RFID", "Tag ID = " + EPC);

				if (isSingleRead) {
					if (rssi > -40) {
						sendEvent(TAG, EPC);
					}
				} else {
					if (addTagToList(EPC)) {
						sendEvent(TAG, EPC);
					}
				}

				if (myTag.isContainsLocationInfo()) {
					short dist = myTag.LocationInfo.getRelativeDistance();
					Log.d(LOG, "Tag relative distance " + dist);
				}
			}
		}
	}

	@Override
	public void eventStatusNotify(RfidStatusEvents rfidStatusEvents) {
		if (rfidStatusEvents.StatusEventData.getStatusEventType() == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
			if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent() == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
				read();
			} else if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent() == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
				cancel();
			}

			WritableMap map = Arguments.createMap();
			map.putBoolean("status", rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent() == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED);
			sendEvent(TRIGGER_STATUS, map);
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
		if (reader != null && reader.isConnected()) {
			doDisconnect();

			dispose();
		}

		promise.resolve(true);
	}

	@ReactMethod
	public void connect(String name, Promise promise) {
		try {
			if (reader != null && reader.isConnected()) {
				doDisconnect();
			}

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

			String error = doConnect();
			boolean status = false;
			if (error == null) {
				status = true;
			}

			WritableMap map = Arguments.createMap();
			map.putBoolean("status", status);
			map.putString("error", error);
			sendEvent(READER_STATUS, map);
		} catch (Exception error) {
			promise.reject(error);
		}
	}

	@ReactMethod
	public void getDeviceDetails(Promise promise) {
		if (reader != null && reader.isConnected()) {
			try {
				Antennas.AntennaRfConfig antennaRFConfig = reader.Config.Antennas.getAntennaRfConfig(1);
				long test = antennaRFConfig.getTari();
				int power = antennaRFConfig.getTransmitPowerIndex();
				long test3 = antennaRFConfig.getrfModeTableIndex();

				WritableMap map = Arguments.createMap();
				map.putString("name", readerDevice.getName());
				map.putString("mac", readerDevice.getAddress());
				map.putInt("power", power / 10);

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
		cacheTags = new ArrayList<>();
	}

	@ReactMethod
	public void setSingleRead(boolean state) {
		Log.d(LOG, "setSingleRead");

		isSingleRead = state;
	}

	@ReactMethod
	public void setPower(int power, Promise promise) {
		if (reader != null && reader.isConnected()) {
			Antennas.AntennaRfConfig antennaRfConfig = null;
			try {
				antennaRfConfig = reader.Config.Antennas
						.getAntennaRfConfig(1);

				antennaRfConfig.setTransmitPowerIndex(power * 10);
				reader.Config.Antennas.setAntennaRfConfig(1, antennaRfConfig);

				promise.resolve(true);
			} catch (InvalidUsageException e) {
				e.printStackTrace();
			} catch (OperationFailureException e) {
				e.printStackTrace();
				promise.reject(e);
			}
		}

		promise.reject(LOG, "Fail to change power");
	}

	@ReactMethod
	public void programTag(String oldTag, String newTag, Promise promise) {
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
					map.putBoolean("status", error != null);
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
		if (reader != null && reader.isConnected()) {
			reader.Events.setTagReadEvent(enable);
		}

		promise.resolve(true);
	}

	private void init() {
		if (readers == null) {
			readers = new Readers(this.reactContext, ENUM_TRANSPORT.BLUETOOTH);
			readers.attach(this);
		}
	}

	private void dispose() {
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

	private String doConnect() {
		if (reader != null) {
			Log.d(LOG, "connect " + reader.getHostName());
			try {
				if (!reader.isConnected()) {
					// Establish connection to the RFID Reader
					reader.connect();
					ConfigureReader();
					return null;
				}
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
								Log.i("RFID", "Region set to " + regionInfo.getName());
							}
						}
					} catch (InvalidUsageException | OperationFailureException invalidUsageException) {
						invalidUsageException.printStackTrace();
					}
				} else {
					return e.getResults().toString();
				}
			} catch (Exception e) {
				e.printStackTrace();
				return e.getMessage();
			}
		}

		return "Connection failed";
	}

	private void doDisconnect() {
		Log.d(LOG, "disconnect " + reader);

		if (reader != null && reader.isConnected()) {
			try {
				reader.Events.removeEventsListener(this);

				reader.disconnect();
			} catch (InvalidUsageException | OperationFailureException e) {
				e.printStackTrace();
			}
		}
	}

	private void ConfigureReader() throws Exception {
		Log.d("ConfigureReader", "ConfigureReader " + reader.getHostName());
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
			reader.Config.setBeeperVolume(BEEPER_VOLUME.QUIET_BEEP);

			// set trigger mode as rfid so scanner beam will not come
			reader.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true);
			// set start and stop triggers
			reader.Config.setStartTrigger(triggerInfo.StartTrigger);
			reader.Config.setStopTrigger(triggerInfo.StopTrigger);
			//set DPO enable
			reader.Config.setDPOState(DYNAMIC_POWER_OPTIMIZATION.ENABLE);
			// power levels are index based so maximum power supported get the last one
			MAX_POWER = reader.ReaderCapabilities.getTransmitPowerLevelValues().length - 1;
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

	private boolean addTagToList(String strEPC) {
		if (strEPC != null) {
			if (!checkIsExisted(strEPC)) {
				cacheTags.add(strEPC);
				return true;
			}
		}
		return false;
	}

	private boolean checkIsExisted(String strEPC) {
		for (int i = 0; i < cacheTags.size(); i++) {
			String tag = cacheTags.get(i);
			if (strEPC != null && strEPC.equals(tag)) {
				return true;
			}
		}
		return false;
	}
}
