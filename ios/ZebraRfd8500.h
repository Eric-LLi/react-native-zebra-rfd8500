#import <React/RCTBridgeModule.h>
//#import "RfidSdkApi.h"
#import <React/RCTEventEmitter.h>
#import "./include/SbtSdkFactory.h"

//@protocol zt_IRfidAppEngineDevListDelegate <NSObject>
//- (BOOL)deviceListHasBeenUpdated;
//@end
//
//@protocol zt_IRfidAppEngineTriggerEventDelegate <NSObject>
//- (BOOL)onNewTriggerEvent:(BOOL)pressed;
//@end
//
//@protocol zt_IRfidAppEngineBatteryEventDelegate <NSObject>
//- (BOOL)onNewBatteryEvent;
//@end

//@interface ZebraRfd8500 : RCTEventEmitter <RCTBridgeModule, srfidISdkApiDelegate>
@interface ZebraRfd8500 : RCTEventEmitter <RCTBridgeModule>

@end
