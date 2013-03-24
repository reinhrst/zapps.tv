//
//  ViewController.h
//  myapp
//
//  Created by Reinoud Elhorst on 14-03-12.
//  Copyright (c) 2012 Claude ICT. All rights reserved.
//

#import <UIKit/UIKit.h>
#import <AVFoundation/AVFoundation.h>
#import <AudioToolbox/AudioConverter.h>
#import "dct.h"

#define NO_MORE_AUDIO_TO_RETURN 85
#define FRAME_LOG2LENGTH 11
#define FRAME_LENGTH (1 << FRAME_LOG2LENGTH)
#define FINGERPRINT_INTERVAL 64
#define SAMPLE_FREQUENCY 5500.0
#define LOWEST_FREQUENCY 318
#define HIGHEST_FREQUENCY 2000
#define FREQUENCY_BUCKETS 33

#define ZIPPO_HOST @"192.168.178.34"
#define ZIPPO_PORT 7000
#define ZIPPO_PROTOCOL_VERSION 2
#define APP_VERSION 1
#define ZIPPO_METHOD_NUMBER 1
#define ZIPPO_FINGERPRINT_INTERVAL 10

typedef double converted_sample;


NSData* intToDataBE(uint32_t in);
NSData* longToDataBE(uint64_t in);
NSData* handset_id();
uint64_t timestamp();

@interface ViewController : UIViewController
 <NSStreamDelegate, AVCaptureAudioDataOutputSampleBufferDelegate>
{
    dctII_setup_data dct_setup_data;
    Data_window hann_window;
    Bucketinfo buckets;
    Fingerprint_energy_diff_levels last_energy;

    
    AVCaptureDevice *audioCaptureDevice;
    AVCaptureSession *captureSession;
    AVCaptureDeviceInput *audioInput;
    AVCaptureAudioDataOutput *audioOutput;
    dispatch_queue_t sound_input_queue;
    AudioConverterRef *downSampler;
    converted_sample* sample_buffer;
    SInt32 sample_buffer_content_size;
    NSMutableData* tosend;
    NSMutableArray* fingerprints_to_send;
    uint64_t current_timestamp;
    NSInputStream *inputStream;
    NSOutputStream *outputStream;
    
}

- (void)captureOutput:(AVCaptureOutput *)captureOutput didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer fromConnection:(AVCaptureConnection *)connection;
- (void)handleNewUnconvertedSoundData;
- (void) handleConvertedSoundData:(converted_sample*) new_samples withLength:(int) new_samples_left;
- (void)foundFingerprint:(Fingerprint)fingerprint;

- (void)setUpZippoConnection;
- (void)sendData;
- (void)stream:(NSStream *)stream handleEvent:(NSStreamEvent)eventCode;

@end