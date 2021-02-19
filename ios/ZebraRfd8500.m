#import "ZebraRfd8500.h"
#import <React/RCTLog.h>

#define ERROR @"ERROR"
#define LOG @"[RFD8500] "
#define READER_STATUS @"READER_STATUS"
#define TRIGGER_STATUS @"TRIGGER_STATUS"
#define WRITE_TAG_STATUS @"WRITE_TAG_STATUS"
#define TAG @"TAG"
#define LOCATE_TAG @"LOCATE_TAG"

@interface ZebraRfd8500()
{
    bool hasListeners;
    int MAX_POWER;
    id <srfidISdkApi> m_RfidSdkApi;
    
    srfidReaderInfo *m_readerInfo;
}
@end

@implementation ZebraRfd8500

- (NSArray<NSString *> *)supportedEvents
{
    return @[READER_STATUS, TRIGGER_STATUS, WRITE_TAG_STATUS, TAG, LOCATE_TAG];
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

RCT_EXPORT_MODULE()

RCT_EXPORT_METHOD(isConnected:
                  (RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
    if(m_readerInfo != nil)
    {
        resolve(@([m_readerInfo isActive]));
    }
    
    resolve(@NO);
}

RCT_EXPORT_METHOD(connect: (NSString *)name
                  resover:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
    NSMutableArray *available_readers = [[NSMutableArray alloc] init];
    [m_RfidSdkApi srfidGetAvailableReadersList:&available_readers];
    
    for (srfidReaderInfo *reader in available_readers)
    {
        if([[reader getReaderName] isEqualToString:name])
        {
            m_readerInfo = reader;
            
            /* establish logical communication session */
            NSString* error = [self connect: [m_readerInfo getReaderID]];
            if(error != nil)
            {
                reject(ERROR, error, nil);
            }
        }
    }
}

RCT_EXPORT_METHOD(disconnect:
                  (RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
    //
}

RCT_EXPORT_METHOD(clear)
{
    //
}

RCT_EXPORT_METHOD(getDevices:
                  (RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
    /* allocate an array for storage of list of available RFID readers */
    NSMutableArray *available_readers = [[NSMutableArray alloc] init];
    /* allocate an array for storage of list of active RFID readers */
    NSMutableArray *active_readers = [[NSMutableArray alloc] init];
    
    if ([m_RfidSdkApi srfidGetAvailableReadersList:&available_readers] == SRFID_RESULT_FAILURE)
    {
        reject(ERROR, @"Searhing for available readers has failed", nil);
    }
    
    [m_RfidSdkApi srfidGetActiveReadersList:&active_readers];
    
    /* nrv364: due to auto-reconnect option some available scanners may have
     changed to active and thus the same scanner has appeared in two lists */
    for (srfidReaderInfo *act in active_readers)
    {
        for (srfidReaderInfo *av in available_readers)
        {
            if ([av getReaderID] == [act getReaderID])
            {
                [available_readers removeObject:av];
                break;
            }
        }
    }
    
    /* merge active and available readers to a single list */
    NSMutableArray *readers = [[NSMutableArray alloc] init];
    [readers addObjectsFromArray:active_readers];
    [readers addObjectsFromArray:available_readers];
    
    NSMutableArray *list = [[NSMutableArray alloc] init];
    for (srfidReaderInfo *reader in readers)
    {
        [list addObject: @{@"name": reader.getReaderName, @"mac": @""}];
    }
    
    resolve(list);
}

RCT_EXPORT_METHOD(getDeviceDetails)
{
    //
}

-(id)init
{
    self = [super init];
    RCTLogInfo(@"%@init...", LOG);
    
    if(self != nil && m_RfidSdkApi == nil){
        m_readerInfo = [[srfidReaderInfo alloc] init];
        
        MAX_POWER = 270;
        
        [self initializeRfidSdk];
    }
    
    return self;
}

- (void)dealloc
{
    RCTLogInfo(@"%@dealloc...", LOG);
}

- (void) initializeRfidSdk
{
    m_RfidSdkApi = [srfidSdkFactory createRfidSdkApiInstance];
    [m_RfidSdkApi srfidSetDelegate:self];
    
    /* getting SDK version string */
    NSString *sdk_version = [m_RfidSdkApi srfidGetSdkVersion];
    RCTLogInfo(@"%@Zebra SDK version: %@\n", LOG, sdk_version);
    
    [self initializeListeners];
}

- (void) initializeListeners
{
    /* subscribe for tag data and operation status related events */
    [m_RfidSdkApi srfidSubsribeForEvents:(SRFID_EVENT_MASK_READ |
                                          SRFID_EVENT_MASK_STATUS)];
    
    /* subscribe for battery and hand-held trigger related events */
    [m_RfidSdkApi srfidSubsribeForEvents:(SRFID_EVENT_MASK_BATTERY |
                                          SRFID_EVENT_MASK_TRIGGER)];
    
    /* configuring SDK to communicate with RFID readers in BT MFi mode */
    [m_RfidSdkApi srfidSetOperationalMode:SRFID_OPMODE_MFI];
    
    /* subscribe for connectivity related events */
    [m_RfidSdkApi srfidSubsribeForEvents:(SRFID_EVENT_READER_APPEARANCE |
                                          SRFID_EVENT_READER_DISAPPEARANCE)];
    
    /* configuring SDK to detect appearance and disappearance of available RFID readers */
    [m_RfidSdkApi srfidEnableAvailableReadersDetection:YES];
    
    /* subscribe for connectivity related events */
    [m_RfidSdkApi srfidSubsribeForEvents:(SRFID_EVENT_SESSION_ESTABLISHMENT |
                                          SRFID_EVENT_SESSION_TERMINATION)];
    
    /* enable automatic communication session reestablishment */
    [m_RfidSdkApi srfidEnableAutomaticSessionReestablishment:YES];
    
    /* subscribe for tag locationing related events */
    [m_RfidSdkApi srfidSubsribeForEvents:SRFID_EVENT_MASK_PROXIMITY];
    
    RCTLogInfo(@"%@initializeListeners finished\n", LOG);
}

- (NSString* )connect:(int)reader_id
{
    NSString* error = @"Connection failed";
    
    if (m_RfidSdkApi != nil)
    {
        SRFID_RESULT conn_result = [m_RfidSdkApi srfidEstablishCommunicationSession:reader_id];
        /*Setting batch mode to default after connect and will be set back if and when event is received*/
        //        [m_ActiveReader setBatchModeStatus:NO];
        if (SRFID_RESULT_SUCCESS != conn_result)
        {
            error = nil;
        }
    }
    
    return error;
}

- (void)disconnect:(int)reader_id
{
    if (m_RfidSdkApi != nil)
    {
        [m_RfidSdkApi srfidTerminateCommunicationSession:reader_id];
    }
}

- (void)srfidEventBatteryNotity:(int)readerID aBatteryEvent:(srfidBatteryEvent *)batteryEvent
{
    RCTLogInfo(@"%@srfidEventBatteryNotity: %d\n", LOG, batteryEvent.getPowerLevel);
}

- (void)srfidEventCommunicationSessionEstablished:(srfidReaderInfo *)activeReader
{
    RCTLogInfo(@"%@%@ has connected\n", LOG, [activeReader getReaderName]);
    m_readerInfo = activeReader;
    [m_readerInfo setActive:YES];
    
    /* establish an ASCII protocol level connection */
    NSString *password = @"";
    SRFID_RESULT result = [m_RfidSdkApi srfidEstablishAsciiConnection:[m_readerInfo getReaderID] aPassword:password];
    
    NSString* error = nil;
    if (SRFID_RESULT_SUCCESS == result)
    {
        //
    }
    else if (SRFID_RESULT_WRONG_ASCII_PASSWORD == result)
    {
        error = @"Incorrect ASCII connection password\n";
    }
    else
    {
        error = @"Failed to establish ASCII connection\n";
    }
    
    if (hasListeners)
    {
        // Only send events if anyone is listening
        [self sendEventWithName: READER_STATUS body:@{@"status": error == nil ? @YES : @NO, error: error}];
    }
}

- (void)srfidEventCommunicationSessionTerminated:(int)readerID
{
    RCTLogInfo(@"%@RFID reader has disconnected: ID = %d\n", LOG, readerID);
}

- (void)srfidEventProximityNotify:(int)readerID aProximityPercent:(int)proximityPercent
{
    RCTLogInfo(@"%@srfidEventProximityNotify: %d\n", LOG, proximityPercent);
}

- (void)srfidEventReadNotify:(int)readerID aTagData:(srfidTagData *)tagData
{
    RCTLogInfo(@"%@srfidEventReadNotify: %@\n", LOG, tagData.getTagId);
}

- (void)srfidEventReaderAppeared:(srfidReaderInfo *)availableReader
{
    RCTLogInfo(@"%@RFID reader has appeared: ID = %d name = %@\n", LOG, [availableReader getReaderID], [availableReader getReaderName]);
}

- (void)srfidEventReaderDisappeared:(int)readerID
{
    RCTLogInfo(@"%@RFID reader has disappeared: ID = %d\n", LOG, readerID);
}

- (void)srfidEventStatusNotify:(int)readerID aEvent:(SRFID_EVENT_STATUS)event aNotification:(id)notificationData
{
    RCTLogInfo(@"%@srfidEventStatusNotify: %@\n", LOG, notificationData);
}

- (void)srfidEventTriggerNotify:(int)readerID aTriggerEvent:(SRFID_TRIGGEREVENT)triggerEvent
{
    RCTLogInfo(@"%@srfidEventTriggerNotify: %u\n", LOG, triggerEvent);
}

@end
