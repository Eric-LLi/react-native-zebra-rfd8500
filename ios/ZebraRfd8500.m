#import "ZebraRfd8500.h"
#import <React/RCTLog.h>
//#import "RfidSdkFactory.h"

#define LOG @"LOG"
#define READER_STATUS @"READER_STATUS"
#define TRIGGER_STATUS @"TRIGGER_STATUS"
#define WRITE_TAG_STATUS @"WRITE_TAG_STATUS"
#define TAG @"TAG"
#define LOCATE_TAG @"LOCATE_TAG"
static int MAX_POWER = 270;

@implementation ZebraRfd8500 {
    bool hasListeners;
}

// Will be called when this module's first listener is added.
-(void)startObserving {
    hasListeners = YES;
    // Set up any upstream listeners or background tasks as necessary
}

// Will be called when this module's last listener is removed, or on dealloc.
-(void)stopObserving {
    hasListeners = NO;
    // Remove upstream listeners, stop unnecessary background tasks
}

//static id <srfidISdkApi> m_RfidSdkApi;

RCT_EXPORT_MODULE()

//RCT_EXPORT_METHOD(getDevices:
//                  (RCTPromiseResolveBlock)resolve
//                  rejecter:(RCTPromiseRejectBlock)reject)
//{
//    /* allocate an array for storage of list of available RFID readers */
//    NSMutableArray *available_readers = [[NSMutableArray alloc] init];
//    /* allocate an array for storage of list of active RFID readers */
//    NSMutableArray *active_readers = [[NSMutableArray alloc] init];
//
//    if (m_RfidSdkApi != nil)
//    {
//        if ([m_RfidSdkApi srfidGetAvailableReadersList:&available_readers] == SRFID_RESULT_FAILURE)
//        {
//            reject(@"ERROR", @"Searhing for available readers has failed", nil);
//        }
//
//        [m_RfidSdkApi srfidGetActiveReadersList:&active_readers];
//
//        /* nrv364: due to auto-reconnect option some available scanners may have
//         changed to active and thus the same scanner has appeared in two lists */
//        for (srfidReaderInfo *act in active_readers)
//        {
//            for (srfidReaderInfo *av in available_readers)
//            {
//                if ([av getReaderID] == [act getReaderID])
//                {
//                    [available_readers removeObject:av];
//                    break;
//                }
//            }
//        }
//
//        /* merge active and available readers to a single list */
//        NSMutableArray *readers = [[NSMutableArray alloc] init];
//        [readers addObjectsFromArray:active_readers];
//        [readers addObjectsFromArray:available_readers];
//
//        NSMutableArray *list = [[NSMutableArray alloc] init];
//        for (srfidReaderInfo *reader in readers) {
//            [list addObject: @{@"name": reader.getReaderName, @"mac": @""}];
//        }
//
//        resolve(list);
//    }
//}
//
//- (void) initializeRfidSdkWithAppSettings
//{
//    /* variable to store single shared instance of API object */
//    m_RfidSdkApi = [srfidSdkFactory createRfidSdkApiInstance];
//    [m_RfidSdkApi srfidSetDelegate:self];
//
//    /* getting SDK version string */
//    NSString *sdk_version = [m_RfidSdkApi srfidGetSdkVersion];
//    RCTLogInfo(@"Zebra SDK version: %@\n", sdk_version);
//
//    /* subscribe for tag data and operation status related events */
//    [m_RfidSdkApi srfidSubsribeForEvents:(SRFID_EVENT_MASK_READ |
//                                          SRFID_EVENT_MASK_STATUS)];
//    /* subscribe for battery and hand-held trigger related events */
//    [m_RfidSdkApi srfidSubsribeForEvents:(SRFID_EVENT_MASK_BATTERY |
//                                          SRFID_EVENT_MASK_TRIGGER)];
//    /* configuring SDK to communicate with RFID readers in BT LE mode */
//    [m_RfidSdkApi srfidSetOperationalMode:SRFID_OPMODE_BTLE];
//    /* subscribe for connectivity related events */
//    [m_RfidSdkApi srfidSubsribeForEvents:(SRFID_EVENT_READER_APPEARANCE |
//                                          SRFID_EVENT_READER_DISAPPEARANCE)];
//}
//
//- (void)srfidEventBatteryNotity:(int)readerID aBatteryEvent:(srfidBatteryEvent *)batteryEvent
//{
//    RCTLogInfo(@"srfidEventBatteryNotity: %d\n", batteryEvent.getPowerLevel);
//}
//
//- (void)srfidEventCommunicationSessionEstablished:(srfidReaderInfo *)activeReader
//{
//    RCTLogInfo(@"srfidEventCommunicationSessionEstablished: %@\n", activeReader.getReaderName);
//}
//
//- (void)srfidEventCommunicationSessionTerminated:(int)readerID
//{
//    RCTLogInfo(@"srfidEventCommunicationSessionTerminated: %d\n", readerID);
//}
//
//- (void)srfidEventProximityNotify:(int)readerID aProximityPercent:(int)proximityPercent
//{
//    RCTLogInfo(@"srfidEventProximityNotify: %d\n", proximityPercent);
//}
//
//- (void)srfidEventReadNotify:(int)readerID aTagData:(srfidTagData *)tagData
//{
//    RCTLogInfo(@"srfidEventReadNotify: %@\n", tagData.getTagId);
//}
//
//- (void)srfidEventReaderAppeared:(srfidReaderInfo *)availableReader
//{
//    RCTLogInfo(@"srfidEventReaderAppeared: %@\n", availableReader.getReaderName);
//}
//
//- (void)srfidEventReaderDisappeared:(int)readerID
//{
//    RCTLogInfo(@"srfidEventReaderDisappeared: %d\n", readerID);
//}
//
//- (void)srfidEventStatusNotify:(int)readerID aEvent:(SRFID_EVENT_STATUS)event aNotification:(id)notificationData
//{
//    RCTLogInfo(@"srfidEventStatusNotify: %@\n", notificationData);
//}
//
//- (void)srfidEventTriggerNotify:(int)readerID aTriggerEvent:(SRFID_TRIGGEREVENT)triggerEvent
//{
//    RCTLogInfo(@"srfidEventTriggerNotify: %u\n", triggerEvent);
//}

@end
