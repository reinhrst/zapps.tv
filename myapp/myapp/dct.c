//
//  dct.c
//  myapp
//
//  Created by Reinoud Elhorst on 17-03-12.
//  Copyright (c) 2012 Claude ICT. All rights reserved.
//

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include "dct.h"

dctII_setup_data dctII_setup(vDSP_Length dct_log2length, FFTRadix radix) {
    vDSP_Length fft_log2length = dct_log2length + 1;
    dctII_setup_data result;
    result.fft_setup = vDSP_create_fftsetupD( fft_log2length, radix);
    
    int dct_length = 1 << dct_log2length;
    int fft_length = 1 << fft_log2length;
    result.dct_correction_factors = (double*) malloc(sizeof(double) * fft_length);
    for (int i=0;i<dct_length;i++) {
        result.dct_correction_factors[2*i] = cos((M_PI*(dct_length-0.5)*i)/dct_length)/sqrt(dct_length*8.0);
        result.dct_correction_factors[2*i+1] = sin((M_PI*(dct_length-0.5)*i)/dct_length)/sqrt(dct_length*8.0);
    }
    result.dct_correction_factors[0] = result.dct_correction_factors[0]/sqrt(2.0);
    result.dct_correction_factors[1] = result.dct_correction_factors[1]/sqrt(2.0); //although this one is always 0 :)
    result.fft_log2length =  fft_log2length;
    return result;
}

/**
 
 */
void dctII_1D(dctII_setup_data setup, double* ioData) {
    
    vDSP_Length fft_length = 1 << setup.fft_log2length;
    vDSP_Length dct_length = fft_length/2;

    double* mirroreddata = malloc(sizeof(double)*fft_length);
    for (int i=0;i<dct_length;i++) {
        mirroreddata[i] = ioData[dct_length-i-1];
    }
    for (int i=dct_length; i<fft_length; i++) {
        mirroreddata[i] = ioData[i-dct_length];
    }
    
    DSPDoubleSplitComplex* complexData = (DSPDoubleSplitComplex*)calloc(1, sizeof(DSPDoubleSplitComplex));
    
    complexData->realp = malloc(sizeof(double) * dct_length);
    complexData->imagp = malloc(sizeof(double) * dct_length);
    
    vDSP_ctozD((DSPDoubleComplex*) mirroreddata, (vDSP_Stride) 2, complexData, (vDSP_Stride) 1, dct_length);
    
    vDSP_fft_zripD( setup.fft_setup, complexData, (vDSP_Stride) 1, setup.fft_log2length, kFFTDirection_Forward);
    
    vDSP_ztocD(complexData, (vDSP_Stride) 1, (DSPDoubleComplex *) mirroreddata, (vDSP_Stride) 2, dct_length);
    
    vDSP_vmulD(mirroreddata, (vDSP_Stride) 1, setup.dct_correction_factors, (vDSP_Stride) 1, mirroreddata, (vDSP_Stride) 1, fft_length);
    
    for (int i=0; i<dct_length; i++){
        ioData[i] = mirroreddata[2*i]-mirroreddata[2*i+1];
    }

    if(DCT_DEBUG) {
        printf("dcted:");
        for (int i=0; i<10; i++) {
            printf("%e ", ioData[i]);
        }
        printf("\n");
    }
    free(complexData->realp);
    free(complexData->imagp);
    free(complexData);
    free(mirroreddata);
}

Data_window create_hann_window (vDSP_Length log2length) {
    vDSP_Length length = 1 << log2length;
    Data_window result = malloc(sizeof(double) * length);
    for (int i=0; i<length;i++) {
        result[i] = 0.5*(1-cos(2*M_PI*((double)i/length)));
    }
    if(DCT_DEBUG) {
        printf("hann:");
        for (int i=0; i<10; i++) {
            printf("%e ", result[i]);
        }
        printf("\n");
    }
    return result;
}

void apply_window(double* ioData, const Data_window window, vDSP_Length log2length)
{
    vDSP_Length length = 1 << log2length;
    vDSP_vmulD(ioData, (vDSP_Stride) 1, window, (vDSP_Stride) 1, ioData, (vDSP_Stride) 1, length);
}

Bucketinfo setup_buckets(float lowest_freq, float highest_freq, int nrbuckets, int frame_length, float sample_frequency) {
    Bucketinfo buckets = malloc((nrbuckets+1)*sizeof(int));
    double log_low = log(lowest_freq);
    double log_high = log(highest_freq);
    for (int i=0; i<nrbuckets+1; i++) {
        buckets[i] = (int) round(2 * powf(M_E, log_low+(log_high-log_low)/nrbuckets*i) * frame_length / sample_frequency);
    }
    return buckets;
}


Fingerprint_energy_diff_levels calculate_energylevels(const double* samples, vDSP_Length log2length, Data_window window, dctII_setup_data dct_setup_data, Bucketinfo bucketinfo, int nrbuckets)
{
    vDSP_Length length = 1 << log2length;

    if(DCT_DEBUG) {
        printf("input:");
        for (int i=0; i<65; i++) {
            printf("%e ", samples[i]);
        }
        printf("\n");
        printf("input-end:");
        for (int i=length-80; i<length; i++) {
            printf("%e ", samples[i]);
        }
        printf("\n");
    }
    
    Fingerprint_energy_diff_levels energy = calloc(nrbuckets-1, sizeof(double));
    double* working_samples = malloc(length * sizeof(double));
    memcpy(working_samples, samples, length*sizeof(double));
    apply_window(working_samples, window, log2length);
    dctII_1D(dct_setup_data, working_samples);
    
    for (int i=0; i<nrbuckets; i++) {
        for (int j=bucketinfo[i];j<bucketinfo[i+1];j++) {
            if (i != nrbuckets - 1) {
                energy[i] += (double) working_samples[j]*working_samples[j];
            }
            if (i != 0) {
                energy[i-1] -= working_samples[j]*working_samples[j];
            }
        }
    }
    if(DCT_DEBUG) {
        printf("energy-diff:");
        for (int i=0; i<10; i++) {
            printf("%e ", energy[i]);
        }
        printf("\n");
    }
    free(working_samples);
    return energy;
}


Fingerprint calculate_fingerprint(Fingerprint_energy_diff_levels old_energy, Fingerprint_energy_diff_levels new_energy, int nrbuckets) {
    Fingerprint result = 0;
    for (int i=0;i<nrbuckets-1;i++) {
        if (new_energy[i] - old_energy[i] > 0) {
            result |= (1 << i);
        }
    }
    return result;
}









