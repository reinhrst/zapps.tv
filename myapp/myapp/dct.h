//
//  dct.h
//  myapp
//
//  Created by Reinoud Elhorst on 17-03-12.
//  Copyright (c) 2012 Claude ICT. All rights reserved.
//

#ifndef myapp_dct_h
#define myapp_dct_h

#define DCT_DEBUG 0

#import <Accelerate/Accelerate.h>
#import <math.h>

struct dctII_setup_data {
    FFTSetupD fft_setup;
    double* dct_correction_factors;
    vDSP_Length fft_log2length;
};
typedef struct dctII_setup_data dctII_setup_data;
typedef double* Data_window;
typedef double* Fingerprint_energy_diff_levels;
typedef uint Fingerprint;


typedef int* Bucketinfo;

dctII_setup_data dctII_setup(vDSP_Length dct_log2length, FFTRadix radix);

/**
 
 */
void dctII_1D(dctII_setup_data setup, double* ioData);

Data_window create_hann_window (vDSP_Length log2length);
void apply_window(double* ioData, const Data_window window, vDSP_Length log2length);
Bucketinfo setup_buckets(float lowest_freq, float highest_freq, int nrbuckets, int frame_length, float sample_frequency);
Fingerprint_energy_diff_levels calculate_energylevels(const double* samples, vDSP_Length log2length, Data_window window, dctII_setup_data dct_setup_data, Bucketinfo bucketinfo, int nrbuckets);
Fingerprint calculate_fingerprint(Fingerprint_energy_diff_levels old_energy, Fingerprint_energy_diff_levels new_energy, int nrbuckets);
#endif
