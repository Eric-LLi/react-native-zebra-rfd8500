#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

#import "./include/RfidSdkApi.h"
#import "./include/RfidSdkFactory.h"
#import "./include/RfidReaderInfo.h"

@interface ZebraRfd8500 : RCTEventEmitter <RCTBridgeModule, srfidISdkApiDelegate>

@end
