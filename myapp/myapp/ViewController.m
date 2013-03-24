//
//  ViewController.m
//  myapp
//
//  Created by Reinoud Elhorst on 14-03-12.
//  Copyright (c) 2012 Claude ICT. All rights reserved.
//

#import "ViewController.h"
#import "test_data.h"

NSData* intToDataBE(uint32_t in) {
    uint correctint = CFSwapInt32HostToBig(in);
    return [NSData dataWithBytes:&correctint length:4];
}

NSData* longToDataBE(uint64_t in) {
    uint64_t correctlong = CFSwapInt64HostToBig(in);
    return [NSData dataWithBytes:&correctlong length:8];
}

NSData* handset_id() {
    char* data = "33333333333333333333333333333333";
    return [NSData dataWithBytes:data length:32];
}

uint64_t timestamp() {
    NSTimeInterval unix_timestamp = [[NSDate date] timeIntervalSince1970];
    return (uint64_t) round(unix_timestamp * SAMPLE_FREQUENCY / FINGERPRINT_INTERVAL);
}

@interface ViewController ()

@end

NSMutableData *unconverted_sound_samples; // place it here so the C function can get to it! TODO: pass around through userData

@implementation ViewController


- (void)viewDidLoad
{
    [super viewDidLoad];
   
    hann_window = create_hann_window(FRAME_LOG2LENGTH);
    dct_setup_data = dctII_setup(FRAME_LOG2LENGTH, FFT_RADIX2);
    buckets = setup_buckets(LOWEST_FREQUENCY, HIGHEST_FREQUENCY, FREQUENCY_BUCKETS, FRAME_LENGTH, SAMPLE_FREQUENCY);
    last_energy = NULL;
    
    sound_input_queue = dispatch_queue_create("tv.zapps.myapp.sound_input_queue", NULL);
    dispatch_retain(sound_input_queue);
    
    unconverted_sound_samples = [[NSMutableData alloc] init];
    sample_buffer = (converted_sample *) malloc(sizeof(converted_sample) * FRAME_LENGTH);
    sample_buffer_content_size = 0;
    
    tosend = [NSMutableData dataWithCapacity:0xFFFF];
    [tosend appendData:longToDataBE(ZIPPO_PROTOCOL_VERSION)];
    [tosend appendData:handset_id()];
    [tosend appendData:intToDataBE(APP_VERSION)];
    current_timestamp = timestamp();
    fingerprints_to_send = [NSMutableArray arrayWithCapacity:ZIPPO_FINGERPRINT_INTERVAL];
    
    [self setUpZippoConnection];

    NSError *error = nil;
    AVAudioSession *audioSession = [AVAudioSession sharedInstance];
    [audioSession setCategory:AVAudioSessionCategoryRecord error:&error];
    if (error){
        NSLog(@"%@", error);
    }
    
    [audioSession setActive:YES error: &error];
    if (error){
        NSLog(@"%@", error);
    }
    
    NSLog(@"NrChannels %d", [audioSession currentHardwareInputNumberOfChannels]);
    
    if (1) {
    
    AudioStreamBasicDescription inputDescription;
    UInt32 BytesPerSample = 2; //16bit, not sure where to get this from AvAudioSession
    memset(&inputDescription, 0, sizeof(AudioStreamBasicDescription));
    inputDescription.mFormatID = kAudioFormatLinearPCM;
    inputDescription.mSampleRate = [audioSession currentHardwareSampleRate];
    inputDescription.mFormatFlags = kAudioFormatFlagsCanonical;
    inputDescription.mBytesPerPacket = BytesPerSample * [audioSession currentHardwareInputNumberOfChannels];
    inputDescription.mFramesPerPacket = 1;
    inputDescription.mBytesPerFrame = BytesPerSample * [audioSession currentHardwareInputNumberOfChannels];
    inputDescription.mChannelsPerFrame = [audioSession currentHardwareInputNumberOfChannels];
    inputDescription.mBitsPerChannel = BytesPerSample * 8; //8 bits per byte
    
    AudioStreamBasicDescription outputDescription;
    UInt32 OutputBytesPerSample = sizeof(converted_sample);
    UInt32 OutputChannels = 1;
    memset(&outputDescription, 0, sizeof(AudioStreamBasicDescription));
    outputDescription.mFormatID = kAudioFormatLinearPCM;
    outputDescription.mSampleRate = 5500;
    outputDescription.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagsNativeEndian;
    outputDescription.mBytesPerPacket = OutputBytesPerSample * OutputChannels;
    outputDescription.mFramesPerPacket = 1;
    outputDescription.mBytesPerFrame = OutputBytesPerSample * OutputChannels;
    outputDescription.mChannelsPerFrame = OutputChannels;
    outputDescription.mBitsPerChannel = OutputBytesPerSample * 8;
    
    downSampler = malloc(sizeof(AudioConverterRef));
    
    AudioConverterNew(&inputDescription, &outputDescription, downSampler);
    
    audioCaptureDevice = [AVCaptureDevice defaultDeviceWithMediaType:AVMediaTypeAudio];
    captureSession = [[AVCaptureSession alloc] init];
    audioInput = [AVCaptureDeviceInput deviceInputWithDevice:audioCaptureDevice error:&error];
    if (error){
        NSLog(@"%@", error);
    }
    
    [captureSession addInput:audioInput];
    audioOutput = [[AVCaptureAudioDataOutput alloc] init];
    [audioOutput setSampleBufferDelegate:self queue:sound_input_queue];
    [captureSession addOutput:audioOutput];
    [captureSession startRunning];
        
    } else {
        converted_sample* samples = (converted_sample*) get_test_samples();
        if(DCT_DEBUG) {
            printf("samples:");
            for (int i=0; i<10; i++) {
                printf("%e ", samples[i]);
            }
            printf("\n");
        }
        [self handleConvertedSoundData: samples withLength:256*64+2048];
    }
    
}

- (void)captureOutput:(AVCaptureOutput *)captureOutput didOutputSampleBuffer:(CMSampleBufferRef)sampleBufferRef fromConnection:(AVCaptureConnection *)connection {
    
        CMBlockBufferRef blockBufferRef = CMSampleBufferGetDataBuffer(sampleBufferRef);
    
        size_t length = CMBlockBufferGetDataLength(blockBufferRef);
    
        NSMutableData * data = [NSMutableData dataWithLength:length];
        CMBlockBufferCopyDataBytes(blockBufferRef, 0, length, data.mutableBytes);
    

        dispatch_async(dispatch_get_main_queue(), ^{ //NB since I will be inserting data to the "tosend" data structure here, this needs to run on the same thread as the NSStream delegate
            [unconverted_sound_samples appendData:data]; //NB: only access/edit this thing via this queue
            [self handleNewUnconvertedSoundData];
        });
    
}

OSStatus MyAudioConverterComplexInputDataProc (
                                             AudioConverterRef             inAudioConverter,
                                             UInt32                        *ioNumberDataPackets,
                                             AudioBufferList               *ioData,
                                             AudioStreamPacketDescription  **outDataPacketDescription,
                                             void                          *userdata
                                             ) {
    UInt32 packets_requested = *ioNumberDataPackets;
    UInt32 input_bytes_available = [unconverted_sound_samples length];
    UInt32 packets_available = input_bytes_available/2;
    UInt32 packets_to_serve = MIN(packets_requested, packets_available);
    UInt32 bytes_to_serve = packets_to_serve*2;
    
    *ioNumberDataPackets = packets_to_serve;
    if (packets_to_serve == 0) {
        return NO_MORE_AUDIO_TO_RETURN;
    }
    
    ioData->mBuffers[0].mData = calloc(packets_to_serve,2);
    ioData->mBuffers[0].mDataByteSize = packets_to_serve*2;
    memcpy(ioData->mBuffers[0].mData, [unconverted_sound_samples mutableBytes], bytes_to_serve);


    unconverted_sound_samples = [[NSMutableData alloc] initWithBytes:([unconverted_sound_samples mutableBytes]+bytes_to_serve) length:([unconverted_sound_samples length] - bytes_to_serve)];
          
    return 0;
}

-(void) handleNewUnconvertedSoundData{
    UInt32 outputDataPacketSize = 0xFFFF; //give us as many samples as available!
    UInt32 outputBytesPerFrame = (sizeof(converted_sample));

    AudioBufferList *converted_audio = malloc(sizeof(AudioBufferList));
    converted_audio->mNumberBuffers = 1;
    converted_audio->mBuffers[0].mData = calloc(outputDataPacketSize, outputBytesPerFrame);
    converted_audio->mBuffers[0].mDataByteSize = outputDataPacketSize * outputBytesPerFrame;
    converted_audio->mBuffers[0].mNumberChannels = 1;

    AudioStreamPacketDescription* outPacketDescription = NULL;    
    
    OSStatus status = AudioConverterFillComplexBuffer(
                                             *downSampler,
                                             MyAudioConverterComplexInputDataProc,
                                             nil,
                                             &outputDataPacketSize,
                                             converted_audio,
                                             outPacketDescription
                                             );
    if (status != NO_MORE_AUDIO_TO_RETURN) {
        NSLog(@"Returnvalue was %ld, what gives?", status);
    }
    
    
    
    int new_samples_count = converted_audio->mBuffers[0].mDataByteSize / sizeof(converted_sample);
    converted_sample* new_samples = (converted_sample*) converted_audio->mBuffers[0].mData;
    
    [self handleConvertedSoundData: new_samples withLength:new_samples_count];

    free (converted_audio->mBuffers[0].mData);
    free (converted_audio);
}

-(void) handleConvertedSoundData:(converted_sample*) new_samples withLength:(int) new_samples_left {
    
    while (new_samples_left > 0) {
        int nr_samples_to_copy = MIN(FRAME_LENGTH -sample_buffer_content_size, new_samples_left);
        memcpy(sample_buffer+sample_buffer_content_size, new_samples, nr_samples_to_copy * sizeof(converted_sample));
        new_samples_left -= nr_samples_to_copy;
        new_samples += nr_samples_to_copy;
        sample_buffer_content_size += nr_samples_to_copy;
        
        
        if (sample_buffer_content_size == FRAME_LENGTH) {
            Fingerprint_energy_diff_levels new_energy = calculate_energylevels(sample_buffer, FRAME_LOG2LENGTH, hann_window, dct_setup_data, buckets, FREQUENCY_BUCKETS);
            if (last_energy) {
                Fingerprint myprint = calculate_fingerprint(last_energy, new_energy, FREQUENCY_BUCKETS);
                [self foundFingerprint:myprint];
            }
            free(last_energy);
            last_energy = new_energy;
            sample_buffer_content_size -= FINGERPRINT_INTERVAL;
            memmove(sample_buffer, sample_buffer+FINGERPRINT_INTERVAL, sample_buffer_content_size*sizeof(converted_sample));
        }
    }    
    
/*    NSString *samplestring = [NSString string];
    
    for (int i = 0; i < new_samples_left ; i ++) {
        double left = *new_samples++; 
        if (ABS(left) > 0.05)
            samplestring = [samplestring stringByAppendingFormat:@" %6f", left];
    }
    NSLog(@"%@", samplestring);
 */
}   

- (void)foundFingerprint:(Fingerprint)fingerprint {
    [fingerprints_to_send addObject:[NSNumber numberWithUnsignedInt:fingerprint]];
    
    if ([fingerprints_to_send count] == ZIPPO_FINGERPRINT_INTERVAL) {
        NSLog(@"Sending %d fingerprints with timestamp %llu", ZIPPO_FINGERPRINT_INTERVAL, current_timestamp);
        Byte nr_fingerprints_to_send[] = {ZIPPO_FINGERPRINT_INTERVAL};
        [tosend appendData:intToDataBE(ZIPPO_METHOD_NUMBER)];
        [tosend appendBytes:nr_fingerprints_to_send length:1];
        [tosend appendData:longToDataBE(current_timestamp)];
        current_timestamp +=  ZIPPO_FINGERPRINT_INTERVAL;
        
        for (int i=0;i<ZIPPO_FINGERPRINT_INTERVAL;i++) {
            [tosend appendData:intToDataBE([(NSNumber*)[fingerprints_to_send objectAtIndex:i] unsignedIntValue])];
        }
        [fingerprints_to_send removeAllObjects];
    
        if ([outputStream hasSpaceAvailable]) {
            [self sendData];
        }
    }

    char* fp = malloc(33*sizeof(char));
    for (int i=0;i<32;i++) {
        fp[i] = (fingerprint & (1 << i)) ? 'X' : ' ';
    }
    fp[32] = '\0';
//    NSLog(@"print: %s (%u)", fp, fingerprint);
}

- (void)setUpZippoConnection {
    CFReadStreamRef readStream;
    CFWriteStreamRef writeStream;
    CFStreamCreatePairWithSocketToHost(NULL, (CFStringRef)ZIPPO_HOST, ZIPPO_PORT, &readStream, &writeStream);
    inputStream = (__bridge NSInputStream *)readStream;
    outputStream = (__bridge NSOutputStream *)writeStream;
    [inputStream setDelegate:self];
    [outputStream setDelegate:self];
    [inputStream scheduleInRunLoop:[NSRunLoop currentRunLoop] forMode:NSDefaultRunLoopMode];
    [outputStream scheduleInRunLoop:[NSRunLoop currentRunLoop] forMode:NSDefaultRunLoopMode];
    [inputStream open];
    [outputStream open];
}

- (void) sendData {
    
    NSLog(@"Writing");
    int data_length_sent = [outputStream write:(UInt8*)[tosend mutableBytes] maxLength:[tosend length]];
    for (int i=0;i<data_length_sent;i++) {
        printf("%02x ", ((UInt8*)[tosend mutableBytes])[i]);
    }
    printf("\n");
    NSLog(@"Written %d bytes", data_length_sent);
    int newlength = [tosend length] - data_length_sent;
    memmove([tosend mutableBytes], ([tosend mutableBytes]+data_length_sent), newlength);
    [tosend setLength: newlength];
}

- (void)stream:(NSStream *)stream handleEvent:(NSStreamEvent)eventCode {
    switch(eventCode) {
        case NSStreamEventOpenCompleted:
        {
            NSLog(@"Yes, I'm open: %@", stream);
            break;
        }
        case NSStreamEventHasBytesAvailable:
        {
            NSLog(@"Reading");
            while ([(NSInputStream*) stream hasBytesAvailable]) {
                Byte read = 0;
                [(NSInputStream *) stream read:&read maxLength:1];
                NSLog(@"Data received: %d", read);
            }
            break;
            
        }
        case NSStreamEventHasSpaceAvailable:
        {
            if ([tosend length] > 0) {
                [self sendData];
            }
            break;
        }
    }
}

- (void)viewDidUnload
{
    [super viewDidUnload];
    // Release any retained subviews of the main view.
}

- (BOOL)shouldAutorotateToInterfaceOrientation:(UIInterfaceOrientation)interfaceOrientation
{
    if ([[UIDevice currentDevice] userInterfaceIdiom] == UIUserInterfaceIdiomPhone) {
        return (interfaceOrientation != UIInterfaceOrientationPortraitUpsideDown);
    } else {
        return YES;
    }
}

@end
