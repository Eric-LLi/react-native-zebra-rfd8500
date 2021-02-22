#import "ZebraRfd8500.h"
#import <React/RCTLog.h>

#define ZT_MAX_RETRY                          2
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
    id <srfidISdkApi> m_RfidSdkApi;
    
    srfidReaderInfo *m_readerInfo;
    NSMutableArray *cacheTags;
    BOOL isSingleRead;
}
@end

@implementation ZebraRfd8500

- (NSArray<NSString *> *)supportedEvents
{
    return @[READER_STATUS, TRIGGER_STATUS, WRITE_TAG_STATUS, TAG, LOCATE_TAG];
}

// Will be called when this module's first listener is added.
-(void)startObserving
{
    hasListeners = YES;
    // Set up any upstream listeners or background tasks as necessary
}

// Will be called when this module's last listener is removed, or on dealloc.
-(void)stopObserving
{
    hasListeners = NO;
    // Remove upstream listeners, stop unnecessary background tasks
}

RCT_EXPORT_MODULE()

RCT_EXPORT_METHOD(isConnected: (RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)
{
    if(m_readerInfo != nil)
    {
        resolve(@([m_readerInfo isActive]));
    }
    
    resolve(@NO);
}

RCT_EXPORT_METHOD(connect: (NSString *)name resover:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)
{
    if(m_RfidSdkApi == nil)
    {
        [self initializeRfidSdk];
    }
    
    if(![m_readerInfo isActive])
    {
        NSMutableArray *readers = [self getActualDeviceList];
        
        if(readers.count == 0)
        {
            reject(ERROR, @"Reader Conncetion fail...", nil);
        }
        else
        {
            for (srfidReaderInfo *reader in readers)
            {
                if([[reader getReaderName] isEqualToString:name])
                {
                    /* establish logical communication session */
                    NSString* error = [self doConnect: [reader getReaderID]];
                    if(error != nil)
                    {
                        reject(ERROR, error, nil);
                    }
                }
            }
        }
    }
    else
    {
        resolve(@YES);
    }
}

RCT_EXPORT_METHOD(disconnect: (RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)
{
    [self clear];
    [self doDisconnect: [m_readerInfo getReaderID]];
    
    resolve(@YES);
}

RCT_EXPORT_METHOD(clear)
{
    [cacheTags removeAllObjects];
}

RCT_EXPORT_METHOD(getDevices: (RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)
{
    @try
    {
        if(m_RfidSdkApi == nil)
        {
            [self initializeRfidSdk];
        }
        
        NSMutableArray *readers = [self getActualDeviceList];
        NSMutableArray *list = [[NSMutableArray alloc] init];
        for (srfidReaderInfo *reader in readers)
        {
            [list addObject: @{@"name": [reader getReaderName], @"mac": @""}];
        }
        
        resolve(list);
    }
    @catch (NSException *exception)
    {
        reject(ERROR, exception.reason, nil);
    }
}

RCT_EXPORT_METHOD(getDeviceDetails: (RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)
{
    @try
    {
        /* allocate object for storage of capabilities information */
        srfidReaderCapabilitiesInfo *info = [self getReaderCapabilitiesInfo];
        
        srfidAntennaConfiguration *antennaConfig = [self getAntennaConfiguration];
        
        NSDictionary *device = @{@"name": [m_readerInfo getReaderName], @"mac": [info getBDAddress], @"antennaLevel": @([antennaConfig getPower] / 10)};
        
        resolve(device);
    }
    @catch (NSException *exception)
    {
        reject(ERROR, exception.reason, nil);
    }
}

RCT_EXPORT_METHOD(setAntennaLevel: (int) antennaLevel resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)
{
    @try
    {
        srfidAntennaConfiguration *antennaConfig = [self getAntennaConfiguration];
        
        [antennaConfig setPower: antennaLevel * 10];
        
        [self setAntennaConfiguration: antennaConfig];
        
        resolve(@YES);
    }
    @catch (NSException *exception)
    {
        reject(ERROR, [exception reason], nil);
    }
}

RCT_EXPORT_METHOD(setSingleRead: (BOOL) enable resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)
{
    isSingleRead = enable;
}

RCT_EXPORT_METHOD(programTag: (NSString*) oldTag  anewTag:(NSString*) newTag resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)
{
    //
}

RCT_EXPORT_METHOD(setEnabled : (BOOL) enable resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)
{
    //
}

#pragma mark - private functions

-(void) defaultConfiguration
{
    /* default configuration */
    
    /* Disable batch mode */
    [self setBatchModeConfig: SRFID_BATCHMODECONFIG_DISABLE];
    
    /* Disable beeper */
    [self setBeeperConfig: SRFID_BEEPERCONFIG_HIGH];
    
    /* Set DPO configuration */
    srfidDynamicPowerConfig *dpoConfig = [self getDpoConfiguration];
    [dpoConfig setDynamicPowerOptimizationEnabled:YES];
    [self setDpoConfiguration:dpoConfig];
    
    /* Set antenna configuration */
    srfidAntennaConfiguration *antennaConfig = [self getAntennaConfiguration];
    [antennaConfig setTari:0];
    [antennaConfig setLinkProfileIdx:0];
    [self setAntennaConfiguration:antennaConfig];
    
    /* Set singulation configuration */
    srfidSingulationConfig *singulationConfig = [self getSingulationConfiguration];
    [singulationConfig setSession:SRFID_SESSION_S0];
    [singulationConfig setSlFlag:SRFID_SLFLAG_ALL];
    [singulationConfig setInventoryState:SRFID_INVENTORYSTATE_A];
    [self setSingulationConfiguration:singulationConfig];
    
    /* Set trigger configuration */
    srfidStartTriggerConfig *startConfig = [self getStartTriggerConfiguration];
    [startConfig setStartOnHandheldTrigger:YES];
    [startConfig setTriggerType:SRFID_TRIGGERTYPE_PRESS];
    [startConfig setRepeatMonitoring:NO];
    [startConfig setStartDelay:0];
    [self setStartTriggerConfiguration:startConfig];
    
    /* configure stop triggers parameters to stop on physical trigger release or on 25 sec timeout*/
    srfidStopTriggerConfig *stopConfig = [self getStopTriggerConfiguration];
    [stopConfig setStopOnHandheldTrigger:YES];
    [stopConfig setTriggerType:SRFID_TRIGGERTYPE_RELEASE];
    [stopConfig setStopOnTimeout:YES];
    [stopConfig setStopTimout:(25*1000)];
    [self setStopTriggerConfiguration:stopConfig];
    
    /* Set tag report configuration */
    /* configure report parameters to report RSSI only*/
    srfidTagReportConfig *tagReportConfig = [self getTagReportConfiguration];
    [tagReportConfig setIncRSSI:YES];
    [tagReportConfig setIncPC:NO];
    [tagReportConfig setIncPhase:NO];
    [tagReportConfig setIncChannelIdx:NO];
    [tagReportConfig setIncTagSeenCount:NO];
    [tagReportConfig setIncFirstSeenTime:NO];
    [tagReportConfig setIncLastSeenTime:NO];
    [self setTagReportConfiguration:tagReportConfig];
    
    /* delete all prefilters */
    NSMutableArray *prefilters = [self getPrefilters];
    [prefilters removeAllObjects];
    [self setPrefilters:prefilters];
    
    RCTLogInfo(@"%@Default Configuration Finished...", LOG);
}

- (void) initializeRfidSdk
{
    m_readerInfo = [[srfidReaderInfo alloc] init];
    [m_readerInfo setActive:NO];
    cacheTags = [[NSMutableArray alloc] init];
    isSingleRead = NO;
    
    m_RfidSdkApi = [srfidSdkFactory createRfidSdkApiInstance];
    [m_RfidSdkApi srfidSetDelegate:self];
    
    /* getting SDK version string */
    NSString *sdk_version = [m_RfidSdkApi srfidGetSdkVersion];
    RCTLogInfo(@"%@Zebra SDK version: %@\n", LOG, sdk_version);
    
    NSUserDefaults *settings = [NSUserDefaults standardUserDefaults];
    
    int op_mode = (int)[settings integerForKey: @"ZtSymbolRfidAppCfgOpMode"];
    if (op_mode == 0)
    {
        /* no value => setup default values */
        op_mode = SRFID_OPMODE_MFI;
        [settings setInteger:op_mode forKey: @"ZtSymbolRfidAppCfgOpMode"];
    }
    
    /* subscribe for tag data and operation status related events */
    [m_RfidSdkApi srfidSubsribeForEvents:(SRFID_EVENT_MASK_READ |
                                          SRFID_EVENT_MASK_STATUS)];
    
    /* subscribe for battery and hand-held trigger related events */
    [m_RfidSdkApi srfidSubsribeForEvents:(SRFID_EVENT_MASK_BATTERY |
                                          SRFID_EVENT_MASK_TRIGGER)];
    
    /* configuring SDK to communicate with RFID readers in BT MFi mode */
    [m_RfidSdkApi srfidSetOperationalMode:op_mode];
    
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
    
    RCTLogInfo(@"%@Initialize Listeners Finished\n", LOG);
}

#pragma mark - device management

- (NSMutableArray*)getActualDeviceList
{
    NSMutableArray *available = [[NSMutableArray alloc] init];
    NSMutableArray *active = [[NSMutableArray alloc] init];
    NSMutableArray *readers = [[NSMutableArray alloc] init];
    NSString* error_response = nil;
    
    if (m_RfidSdkApi != nil)
    {
        for (int i = 0; i < ZT_MAX_RETRY; i++) {
            if ([m_RfidSdkApi srfidGetAvailableReadersList:&available] == SRFID_RESULT_FAILURE)
            {
                error_response = @"Connection failed";
            }
            
            [m_RfidSdkApi srfidGetActiveReadersList:&active];
            
            /* nrv364: due to auto-reconnect option some available scanners may have
             changed to active and thus the same scanner has appeared in two lists */
            for (srfidReaderInfo *act in active)
            {
                for (srfidReaderInfo *av in available)
                {
                    if ([av getReaderID] == [act getReaderID])
                    {
                        [available removeObject:av];
                        break;
                    }
                }
            }
        }
        
        /* merge active and available readers to a single list */
        [readers removeAllObjects];
        [readers addObjectsFromArray:active];
        [readers addObjectsFromArray:available];
    }
    else
    {
        error_response = @"Connection failed";
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
    
    return readers;
}

- (NSString* )doConnect:(int)reader_id
{
    NSString* error_response = nil;
    
    if (m_RfidSdkApi != nil)
    {
        SRFID_RESULT conn_result = [m_RfidSdkApi srfidEstablishCommunicationSession:reader_id];
        /*Setting batch mode to default after connect and will be set back if and when event is received*/
        
        if (SRFID_RESULT_SUCCESS != conn_result)
        {
            error_response = @"Connection failed";
        }
    }
    
    return error_response;
}

- (void)doDisconnect:(int)reader_id
{
    if (m_RfidSdkApi != nil)
    {
        [m_RfidSdkApi srfidTerminateCommunicationSession:reader_id];
        
        //
    }
}

#pragma mark - settings request

- (void)setBeeperConfig:(SRFID_BEEPERCONFIG) beeperConfig
{
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    NSString *error_response = nil;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidSetBeeperConfig:[m_readerInfo getReaderID] aBeeperConfig: beeperConfig aStatusMessage: &error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE))
        {
            break;
        }
    }
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Beeper configuration has been set\n", LOG);
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
}

- (void)setBatchModeConfig:(SRFID_BATCHMODECONFIG) batchConfig
{
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    NSString *error_response = nil;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidSetBatchModeConfig:[m_readerInfo getReaderID] aBatchModeConfig: batchConfig aStatusMessage: &error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE))
        {
            break;
        }
    }
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Batch mode configuration has been set\n", LOG);
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
}

- (srfidAntennaConfiguration*)getAntennaConfiguration
{
    srfidAntennaConfiguration *antenaCofiguration = [[srfidAntennaConfiguration alloc] init];
    NSString *error_response = nil;
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidGetAntennaConfiguration:[m_readerInfo getReaderID] aAntennaConfiguration:&antenaCofiguration aStatusMessage:&error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE)) {
            break;
        }
    }
    
    // we check if the power level from a device match the app power level
    // if not we set the nearest value to the device
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Get Reader Antenna Successfully\n", LOG);
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
    
    return antenaCofiguration;
}

- (void)setAntennaConfiguration: (srfidAntennaConfiguration*) antennaConfig
{
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    NSString *error_response = nil;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidSetAntennaConfiguration:[m_readerInfo getReaderID] aAntennaConfiguration:antennaConfig aStatusMessage:&error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE)) {
            break;
        }
    }
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Set Reader Antenna Successfully\n", LOG);
        
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
}

- (srfidReaderCapabilitiesInfo*)getReaderCapabilitiesInfo
{
    srfidReaderCapabilitiesInfo *info = [[srfidReaderCapabilitiesInfo alloc] init];
    NSString *error_response = nil;
    
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidGetReaderCapabilitiesInfo:[m_readerInfo getReaderID] aReaderCapabilitiesInfo:&info aStatusMessage:&error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE))
        {
            break;
        }
    }
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Get Reader CapabilitiesInfo Successfully\n", LOG);
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
    
    return info;
}

- (srfidSingulationConfig*)getSingulationConfiguration
{
    srfidSingulationConfig *singulationCofiguration = [[srfidSingulationConfig alloc] init];
    NSString *error_response = nil;
    
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidGetSingulationConfiguration:[m_readerInfo getReaderID] aSingulationConfig:&singulationCofiguration aStatusMessage:&error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE)) {
            break;
        }
    }
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Get SingulationConfig Successfully\n", LOG);
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
    
    return singulationCofiguration;
}

- (void)setSingulationConfiguration:(srfidSingulationConfig*)singulationConfiguration
{
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    NSString *error_response = nil;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidSetSingulationConfiguration:[m_readerInfo getReaderID]aSingulationConfig:singulationConfiguration aStatusMessage:&error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE)) {
            break;
        }
    }
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Set SingulationConfig Successfully\n", LOG);
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
}

- (srfidDynamicPowerConfig*)getDpoConfiguration
{
    srfidDynamicPowerConfig *dpoConfig = [[srfidDynamicPowerConfig alloc] init];
    NSString *error_response = nil;
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidGetDpoConfiguration:[m_readerInfo getReaderID] aDpoConfiguration:&dpoConfig aStatusMessage:&error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE)) {
            break;
        }
    }
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Get DPO Successfully\n", LOG);
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
    return dpoConfig;
}

- (void)setDpoConfiguration:(srfidDynamicPowerConfig* )dpoConfiguration
{
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    NSString *error_response = nil;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidSetDpoConfiguration:[m_readerInfo getReaderID] aDpoConfiguration:dpoConfiguration aStatusMessage:&error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE)) {
            break;
        }
    }
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Set DPO Successfully\n", LOG);
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
}

- (srfidStartTriggerConfig*)getStartTriggerConfiguration
{
    srfidStartTriggerConfig *config = [[srfidStartTriggerConfig alloc] init];
    NSString *error_response = nil;
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidGetStartTriggerConfiguration:[m_readerInfo getReaderID] aStartTriggeConfig:&config aStatusMessage:&error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE)) {
            break;
        }
    }
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Get StartTriggerConfiguration Successfully\n", LOG);
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
    return config;
}

- (void)setStartTriggerConfiguration:(srfidStartTriggerConfig*)triggerConfig
{
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    NSString *error_response = nil;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidSetStartTriggerConfiguration:[m_readerInfo getReaderID] aStartTriggeConfig:triggerConfig aStatusMessage:&error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE)) {
            break;
        }
    }
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Set StartTriggerConfiguration Successfully\n", LOG);
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
}

- (srfidStopTriggerConfig*)getStopTriggerConfiguration
{
    srfidStopTriggerConfig *config = [[srfidStopTriggerConfig alloc]init];
    NSString *error_response = nil;
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidGetStopTriggerConfiguration:[m_readerInfo getReaderID] aStopTriggeConfig:&config aStatusMessage:&error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE)) {
            break;
        }
    }
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Get StartTriggerConfiguration Successfully\n", LOG);
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
    return config;
}

- (void)setStopTriggerConfiguration:(srfidStopTriggerConfig*)triggerConfig
{
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    NSString *error_response = nil;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidSetStopTriggerConfiguration:[m_readerInfo getReaderID] aStopTriggeConfig:triggerConfig aStatusMessage:&error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE)) {
            break;
        }
    }
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Set StopTriggerConfiguration Successfully\n", LOG);
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
}

- (srfidTagReportConfig*)getTagReportConfiguration
{
    srfidTagReportConfig *reportCofiguration = [[srfidTagReportConfig alloc]init];
    NSString *error_response = nil;
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidGetTagReportConfiguration:[m_readerInfo getReaderID] aTagReportConfig:&reportCofiguration aStatusMessage:&error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE)) {
            break;
        }
    }
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Get TagReportConfiguration Successfully\n", LOG);
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
    return reportCofiguration;
}

- (void)setTagReportConfiguration:(srfidTagReportConfig*)reportConfig
{
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    NSString *error_response = nil;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidSetTagReportConfiguration:[m_readerInfo getReaderID]  aTagReportConfig:reportConfig aStatusMessage:&error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE)) {
            break;
        }
    }
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Set TagReportConfiguration Successfully\n", LOG);
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
}

- (NSMutableArray*)getPrefilters
{
    NSMutableArray *prefilters = [[NSMutableArray alloc] init];
    NSString *error_response = nil;
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidGetPreFilters:[m_readerInfo getReaderID] aPreFilters:&prefilters aStatusMessage:&error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE)) {
            break;
        }
    }
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Get Prefilters Successfully\n", LOG);
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
    
    return prefilters;
}

- (void)setPrefilters:(NSMutableArray*)prefilters
{
    NSString *error_response = nil;
    SRFID_RESULT srfid_result = SRFID_RESULT_FAILURE;
    
    for(int i = 0; i < ZT_MAX_RETRY; i++)
    {
        srfid_result = [m_RfidSdkApi srfidSetPreFilters:[m_readerInfo getReaderID] aPreFilters:prefilters aStatusMessage:&error_response];
        
        if ((srfid_result != SRFID_RESULT_RESPONSE_TIMEOUT) && (srfid_result != SRFID_RESULT_FAILURE)) {
            break;
        }
    }
    
    if (srfid_result == SRFID_RESULT_SUCCESS)
    {
        error_response = nil;
        RCTLogInfo(@"%@Set Prefilters Successfully\n", LOG);
    }
    else if(srfid_result == SRFID_RESULT_RESPONSE_ERROR)
    {
        RCTLogInfo(@"%@Error response from RFID reader: %@\n", LOG, error_response);
    }
    else if(srfid_result == SRFID_RESULT_FAILURE || srfid_result == SRFID_RESULT_RESPONSE_TIMEOUT)
    {
        RCTLogInfo(@"%@Problem with reader connection", LOG);
    }
    
    if(error_response != nil)
    {
        @throw([NSException exceptionWithName: ERROR reason: error_response userInfo: nil]);
    }
}

#pragma mark - delegate protocol implementation
/* ###################################################################### */
/* ########## IRfidSdkApiDelegate Protocol implementation ############### */
/* ###################################################################### */

- (void)srfidEventCommunicationSessionEstablished:(srfidReaderInfo *)activeReader
{
    m_readerInfo = activeReader;
    
    /* establish an ASCII protocol level connection */
    NSString *password = @"";
    SRFID_RESULT result = [m_RfidSdkApi srfidEstablishAsciiConnection:[m_readerInfo getReaderID] aPassword:password];
    
    NSString* error = nil;
    if (SRFID_RESULT_SUCCESS == result)
    {
        [self defaultConfiguration];
        
        RCTLogInfo(@"%@%@ has connected\n", LOG, [activeReader getReaderName]);
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
        [self sendEventWithName: READER_STATUS body:@{@"status": error == nil ? @YES : @NO, @"error": error == nil ? @"" : error}];
    }
}

- (void)srfidEventCommunicationSessionTerminated:(int)readerID
{
    [m_readerInfo setActive: NO];
    RCTLogInfo(@"%@RFID reader has disconnected: ID = %d\n", LOG, readerID);
    
    if (hasListeners)
    {
        // Only send events if anyone is listening
        [self sendEventWithName: READER_STATUS body:@{@"status": @NO}];
    }
}

- (void)srfidEventReaderAppeared:(srfidReaderInfo *)availableReader
{
    RCTLogInfo(@"%@RFID reader has appeared: ID = %d name = %@\n", LOG, [availableReader getReaderID], [availableReader getReaderName]);
}

- (void)srfidEventReaderDisappeared:(int)readerID
{
    RCTLogInfo(@"%@RFID reader has disappeared: ID = %d\n", LOG, readerID);
}

- (void)srfidEventBatteryNotity:(int)readerID aBatteryEvent:(srfidBatteryEvent *)batteryEvent
{
    RCTLogInfo(@"%@batteryEvent: level = [%d] charging = [%d] cause = (%@)\n", LOG, [batteryEvent getPowerLevel], [batteryEvent getIsCharging], [batteryEvent getEventCause]);
}

- (void)srfidEventProximityNotify:(int)readerID aProximityPercent:(int)proximityPercent
{
    //    RCTLogInfo(@"%@srfidEventProximityNotify: %d\n", LOG, proximityPercent);
    
    if (hasListeners)
    {
        // Only send events if anyone is listening
        [self sendEventWithName: LOCATE_TAG body:@{@"distance": @(proximityPercent)}];
    }
}

- (void)srfidEventReadNotify:(int)readerID aTagData:(srfidTagData *)tagData
{
    int rssi = [tagData getPeakRSSI];
    
    if (hasListeners)
    {
        // Only send events if anyone is listening
        if(isSingleRead)
        {
            if(rssi > -40)
            {
                [self sendEventWithName: TAG body:@{@"tag": tagData.getTagId}];
            }
        }
        else
        {
            if(([self addTagToList:[tagData getTagId]]))
            {
                [self sendEventWithName: TAG body:@{@"tag": tagData.getTagId}];
            }
        }
    }
}

- (void)srfidEventStatusNotify:(int)readerID aEvent:(SRFID_EVENT_STATUS)event aNotification:(id)notificationData
{
    RCTLogInfo(@"%@eventStatusNotify: %@\n", LOG, [self stringOfRfidStatusEvent:event]);
}

- (void)srfidEventTriggerNotify:(int)readerID aTriggerEvent:(SRFID_TRIGGEREVENT)triggerEvent
{
    if(triggerEvent == SRFID_TRIGGEREVENT_PRESSED)
    {
        RCTLogInfo(@"%@srfidEventTriggerNotify Pressed", LOG);
    }
    else
    {
        RCTLogInfo(@"%@srfidEventTriggerNotify Released", LOG);
    }
    
    if (hasListeners)
    {
        // Only send events if anyone is listening
        [self sendEventWithName: TRIGGER_STATUS body:@{@"status": triggerEvent == SRFID_TRIGGEREVENT_PRESSED ? @YES : @NO}];
    }
}

#pragma mark - String values return

- (BOOL) addTagToList: (NSString*) strEPC
{
    if(strEPC != nil){
        if(![cacheTags containsObject:strEPC])
        {
            [cacheTags addObject:strEPC];
            return true;
        }
    }
    
    return false;
}

- (NSString*)stringOfRfidStatusEvent:(SRFID_EVENT_STATUS)event
{
    if (SRFID_EVENT_STATUS_OPERATION_START == event)
    {
        return @"EVENT_OPERATION_START";
    }
    else if (SRFID_EVENT_STATUS_OPERATION_STOP == event)
    {
        return @"EVENT_OPERATION_STOP";
    }
    else if (SRFID_EVENT_STATUS_OPERATION_BATCHMODE == event)
    {
        return @"EVENT_BATCH_MODE";
    }
    else if (SRFID_EVENT_STATUS_OPERATION_END_SUMMARY == event)
    {
        return @"EVENT_OPERATION_END_SUMMARY";
    }
    else if (SRFID_EVENT_STATUS_TEMPERATURE == event)
    {
        return @"EVENT_TEMPERATURE";
    }
    else if (SRFID_EVENT_STATUS_POWER == event)
    {
        return @"EVENT_POWER";
    }
    else if (SRFID_EVENT_STATUS_DATABASE == event)
    {
        return @"EVENT_DATABASE";
    }
    
    return @"EVENT_UNKNOWN";
}

- (NSString*)stringOfRfidMemoryBank:(SRFID_MEMORYBANK)mem_bank
{
    if (SRFID_MEMORYBANK_EPC == mem_bank)
    {
        return @"EPC";
    }
    else if (SRFID_MEMORYBANK_RESV == mem_bank)
    {
        return @"RESV";
    }
    else if (SRFID_MEMORYBANK_TID == mem_bank)
    {
        return @"TID";
    }
    else if (SRFID_MEMORYBANK_USER == mem_bank)
    {
        return @"USER";
    }
    
    return @"None";
}

- (NSString*)stringOfRfidSlFlag:(SRFID_SLFLAG)sl_flag
{
    switch (sl_flag)
    {
        case SRFID_SLFLAG_ASSERTED:
            return @"ASSERTED";
        case SRFID_SLFLAG_DEASSERTED:
            return @"DEASSERTED";
        case SRFID_SLFLAG_ALL:
            return @"ALL";
    }
    
    return @"Unknown";
}

- (NSString*)stringOfRfidSession:(SRFID_SESSION)session
{
    switch (session)
    {
        case SRFID_SESSION_S1:
            return @"S1";
        case SRFID_SESSION_S2:
            return @"S2";
        case SRFID_SESSION_S3:
            return @"S3";
        case SRFID_SESSION_S0:
            return @"S0";
    }
    
    return @"Unknown";
}

- (NSString*)stringOfRfidInventoryState:(SRFID_INVENTORYSTATE)state
{
    switch (state)
    {
        case SRFID_INVENTORYSTATE_A:
            return @"STATE A";
        case SRFID_INVENTORYSTATE_B:
            return @"STATE B";
        case SRFID_INVENTORYSTATE_AB_FLIP:
            return @"STATE AB FLIP";
    }
    
    return @"Unknown";
}

@end
