/*
 * (C) Copyright IBM Corp. 2005, 2008
 * All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
/*
 * Native implementation of the direct ALSA audio
 * access.
 *
 * @author Florian Bomers
 */

#include <malloc.h>
#include <stdio.h>
#define ALSA_PCM_NEW_HW_PARAMS_API
#include <alsa/asoundlib.h>
#include "directalsa.h"

/*
 * $$fb: callback does not work reliably at period sizes < 2ms
 * #define USE_CALLBACK
 */

/* do not report an underrun unless at least this number of
 * frames are successfully written to the device
 */
#define MIN_FRAMES_BEFORE_REPORTED_UNDERRUNS (2 * da->periodSize)

#ifdef DEBUG
#define DBG(a) printf("ALSA direct lib: %s\n", a); fflush(stdout)
#define DBG1(format, a) printf("ALSA direct lib: "format, a); fflush(stdout)
#define DBG2(format, a, b) printf("ALSA direct lib: "format, a, b); fflush(stdout)
#define DBG3(format, a, b, c) printf("ALSA direct lib: "format, a, b, c); fflush(stdout)
#else
#define DBG(a)
#define DBG1(format, a)
#define DBG2(format, a, b)
#define DBG3(format, a, b, c)
#endif

#define ACHECK(code) if (ret >= 0) { ret = code; if (ret < 0) DBG1("FAILED CODE: %s\n", #code); }


// define an integer with the size of a pointer to work around compiler warnings
#ifdef SYSTEM64BIT
#define INT_PTR jlong
#else
#define INT_PTR jint
#endif


typedef struct {
	snd_pcm_t *alsaHandle;
  	snd_pcm_hw_params_t *params;
  	int sampleBitType; // the actually used pcm format, one of the BIT_TYPE_* flags
	int frameSize; // bytes per sample
	jlong writtenFrames;
	jlong currentPeriod;
	snd_pcm_uframes_t periodSize;
	snd_async_handler_t* asyncHandler; /* what do we need this for? */
} DirectAlsaHandle;

void debugError(char* method, int errorCode) {
	if (errorCode < 0) {
		DBG2("%s: %s\n", method, snd_strerror(errorCode));
	}
}


int isSystemBigEndian() {
	char test[4];

	test[0] = 0; test[1] = 0; test[2] = 0; test[3] = 1;
	if (*((int*) test) == 1) {
		return 1;
	}
	return 0;
}


/*
 * convert the Java sample bit type to the ALSA encoding code.
 */
snd_pcm_format_t getAlsaEncoding(int sampleWidth, int packed24bit, int bigEndian) {
	if (bigEndian) {
		switch (sampleWidth) {
		case 8: return SND_PCM_FORMAT_U8;
		case 16: return SND_PCM_FORMAT_S16_BE;
		case 24: return packed24bit?SND_PCM_FORMAT_S24_3BE:SND_PCM_FORMAT_S24_BE;
		case 32: return SND_PCM_FORMAT_S32_BE;
		}
	} else {
		switch (sampleWidth) {
		case 8: return SND_PCM_FORMAT_U8;
		case 16: return SND_PCM_FORMAT_S16_LE;
		case 24: return packed24bit?SND_PCM_FORMAT_S24_3LE:SND_PCM_FORMAT_S24_LE;
		case 32: return SND_PCM_FORMAT_S32_LE;
		}
	}
	return SND_PCM_FORMAT_UNKNOWN;
}

/*
 * Retrieve the combination of BIT_TYPE_* constants for a given alsa format
 */
int getJavaBitType(snd_pcm_format_t alsaFormat) {
	switch (alsaFormat) {
		// not supported
		//case SND_PCM_FORMAT_U8:
	case SND_PCM_FORMAT_S16_BE:
		return com_ibm_realtime_synth_modules_DirectAudioSink_BIT_TYPE_16_BIT
			| com_ibm_realtime_synth_modules_DirectAudioSink_BIT_TYPE_BIG_ENDIAN_FLAG;
	case SND_PCM_FORMAT_S24_3BE:
		return com_ibm_realtime_synth_modules_DirectAudioSink_BIT_TYPE_24_BIT3
			| com_ibm_realtime_synth_modules_DirectAudioSink_BIT_TYPE_BIG_ENDIAN_FLAG;
	case SND_PCM_FORMAT_S24_BE:
		return com_ibm_realtime_synth_modules_DirectAudioSink_BIT_TYPE_24_BIT4
			| com_ibm_realtime_synth_modules_DirectAudioSink_BIT_TYPE_BIG_ENDIAN_FLAG;
	case SND_PCM_FORMAT_S32_BE:
		return com_ibm_realtime_synth_modules_DirectAudioSink_BIT_TYPE_32_BIT
			| com_ibm_realtime_synth_modules_DirectAudioSink_BIT_TYPE_BIG_ENDIAN_FLAG;
	case SND_PCM_FORMAT_S16_LE:
		return com_ibm_realtime_synth_modules_DirectAudioSink_BIT_TYPE_16_BIT;
	case SND_PCM_FORMAT_S24_3LE:
		return com_ibm_realtime_synth_modules_DirectAudioSink_BIT_TYPE_24_BIT3;
	case SND_PCM_FORMAT_S24_LE:
		return com_ibm_realtime_synth_modules_DirectAudioSink_BIT_TYPE_24_BIT4;
	case SND_PCM_FORMAT_S32_LE:
		return com_ibm_realtime_synth_modules_DirectAudioSink_BIT_TYPE_32_BIT;
	default:
		break;
	}
	DBG1("Unknown alsa format: %d\n", (int) alsaFormat);
	return 0;
}



/*
 * Test the open device for available formats
 */
int retrieveAlsaFormat(DirectAlsaHandle* da, int bitsPerSample, int channels, snd_pcm_format_t* alsaFormat) {
	int packed24bit;
	int endian;
	int ret = 0;

	for (packed24bit = 0; packed24bit <= 1; packed24bit++) {
		for (endian = 0; endian <= 1; endian++) {
			(*alsaFormat) = getAlsaEncoding(bitsPerSample, packed24bit, endian);
			if ((*alsaFormat) != SND_PCM_FORMAT_UNKNOWN) {
				ACHECK(snd_pcm_hw_params_test_format(da->alsaHandle, da->params, *alsaFormat));
				if (ret >= 0) {
					// format is suported
					da->sampleBitType = getJavaBitType(*alsaFormat);
					if (bitsPerSample > 16 && bitsPerSample < 32 && packed24bit) {
						da->frameSize = 4 * channels;
					} else {
						da->frameSize = ((bitsPerSample + 7) / 8) * channels;
					}
					return 0;
				}
			}
		}
	}
	DBG("Unknown sample size in bits.");
	return -1;
}

#ifdef DEBUG
void printHardwareParams(DirectAlsaHandle* da) {
  	snd_pcm_hw_params_t *params;
	int ret = 0;
	unsigned int value = 0;
	int dir = 0;
	snd_pcm_uframes_t frames = 0;

	ACHECK(snd_pcm_hw_params_malloc(&params));
	ACHECK(snd_pcm_hw_params_current(da->alsaHandle, params));

	DBG("Hardware configuration:");
	DBG1("double buffering start/stop: %d\n", snd_pcm_hw_params_is_double (params));
	DBG1("double buffering data: %d\n",snd_pcm_hw_params_is_batch (params) );
	DBG1("block transfer: %d\n", snd_pcm_hw_params_is_block_transfer (params));
	DBG1("overrange detection supported: %d\n", snd_pcm_hw_params_can_overrange (params));
	DBG1("bits per sample: %d\n", snd_pcm_hw_params_get_sbits (params));
	DBG1("FIFO size: %d\n",snd_pcm_hw_params_get_fifo_size (params) );

	ACHECK(snd_pcm_hw_params_get_channels (params, &value));
	DBG1("channels: %d\n", value);
	ACHECK(snd_pcm_hw_params_get_rate (params, &value, &dir));
	DBG1("Sample rate: %d Hz\n", value);
	ACHECK(snd_pcm_hw_params_get_period_size (params, &frames, &dir));
	DBG1("Period size: %d frames\n", (int) frames);
	ACHECK(snd_pcm_hw_params_get_periods (params, &value, &dir));
	DBG1("Period count: %d \n", value);
	ACHECK(snd_pcm_hw_params_get_buffer_size (params, &frames));
	DBG1("Buffer size: %d frames\n", (int) frames);
	snd_pcm_hw_params_free(params);
}
#endif

#ifdef USE_CALLBACK
/*
 * Callback by ALSA called at every begin of a period.
 * It increases the counter of teh current period.
 */
static void alsaPeriodBeginCallback(snd_async_handler_t* ahandler) {
	DirectAlsaHandle* da = (DirectAlsaHandle*) snd_async_handler_get_callback_private(ahandler);
	if (da != NULL) {
		da->currentPeriod++;
	}
}
#endif

const int BIT_SIZES[] = { 16, 24, 32 };
#define BIT_SIZES_COUNT ( sizeof(BIT_SIZES) / sizeof(int) )

/*
 * Fill formatMask with all formats supported by ALSA
 */
int fillFormatMask(snd_pcm_t* pcm, snd_pcm_hw_params_t* hwParams, int* formatMask) {

	unsigned int bitIndex;
	int bitsPerSample;
	int packed24bit;
	int endian;
	int ret = 0;
	snd_pcm_format_t alsaFormat;

	(*formatMask) = 0;
	for (bitIndex = 0; bitIndex < BIT_SIZES_COUNT; bitIndex++) {
		bitsPerSample = BIT_SIZES[bitIndex];
		for (packed24bit = 0; packed24bit <= 1; packed24bit++) {
			for (endian = 0; endian <= 1; endian++) {
				alsaFormat = getAlsaEncoding(bitsPerSample, packed24bit, endian);
				if (alsaFormat != SND_PCM_FORMAT_UNKNOWN) {
					ret = snd_pcm_hw_params_test_format(pcm, hwParams, alsaFormat);
					if (ret >= 0) {
						// format is suported
						(*formatMask) |= getJavaBitType(alsaFormat);
#ifdef DEBUG
						printf("Supported bitsize: %d bits, packed24bit: %d, bigEndian: %d\n", bitsPerSample, packed24bit, endian);
#endif
					}
				}
			} // endian
			if (bitsPerSample <= 16 || bitsPerSample >= 32) {
				// do not need to test packed formats for non 24-bit formats
				break;
			}
		} // packed24bit
	} // bitIndex
	return 0;
}



/*
 * Class:     com_ibm_realtime_synth_modules_DirectAudioSink
 * Method:    nOpen
 * Signature: (Ljava/lang/String;IIIIIZ)J
 */
JNIEXPORT jlong JNICALL Java_com_ibm_realtime_synth_modules_DirectAudioSink_nOpen
(JNIEnv *env, jclass clazz, jstring devName, jint sampleRate, jint channels, jint
 bitsPerSample, jint bufferSize, jint periodSize, jboolean blocking) {
	int ret = 0;
	const char* sDevName;
	snd_pcm_format_t alsaFormat;
	unsigned int sr;
	snd_pcm_uframes_t ps;
	int dir;

	// check parameters
	sDevName = (*env)->GetStringUTFChars(env, devName, NULL);
	if (sDevName == NULL) {
		DBG("Could not get device name.");
		return 0;
	}

	DirectAlsaHandle* da = (DirectAlsaHandle*) calloc(sizeof(DirectAlsaHandle), 1);
	if (da != NULL) {
		da->writtenFrames = 0;
		/* Allocate a hardware parameters object. */
		ACHECK(snd_pcm_hw_params_malloc(&(da->params)));
		// open the device
		ACHECK(snd_pcm_open(&(da->alsaHandle), (char*) sDevName, SND_PCM_STREAM_PLAYBACK, SND_PCM_NONBLOCK));

		/* Fill it in with hardware default values. */
		ACHECK(snd_pcm_hw_params_any(da->alsaHandle, da->params));
		/* Set the desired hardware parameters. */
		/* Interleaved mode */
		ACHECK(snd_pcm_hw_params_set_access(da->alsaHandle, da->params, SND_PCM_ACCESS_RW_INTERLEAVED));
		// need to query device which PCM format is available for the requested bits per sample
		ACHECK(retrieveAlsaFormat(da, bitsPerSample, channels, &alsaFormat));
		ACHECK(snd_pcm_hw_params_set_format(da->alsaHandle, da->params, alsaFormat));
		ACHECK(snd_pcm_hw_params_set_channels(da->alsaHandle, da->params, (int)channels));
		sr = (unsigned int) sampleRate;
		dir = 0;
		ACHECK(snd_pcm_hw_params_set_rate_near(da->alsaHandle, da->params, &sr, &dir));
		DBG2("Tried to set  sample rate to %dHz -- actual: %dHz.\n", sampleRate, sr);
		ACHECK(snd_pcm_hw_params_set_buffer_size(da->alsaHandle, da->params, (snd_pcm_uframes_t) bufferSize));
		ps = (snd_pcm_uframes_t) periodSize;
		ACHECK(snd_pcm_hw_params_set_period_size_near(da->alsaHandle, da->params, &ps, &dir));

		/* Write the parameters to the driver */
		ACHECK(snd_pcm_hw_params(da->alsaHandle, da->params));
		ACHECK(snd_pcm_prepare(da->alsaHandle));
		ACHECK(snd_pcm_nonblock(da->alsaHandle, blocking?0:1));
#ifdef USE_CALLBACK
		ACHECK(snd_async_add_pcm_handler(&(da->asyncHandler),
										 da->alsaHandle,
										 alsaPeriodBeginCallback,
										 da));
#endif
		ACHECK(snd_pcm_hw_params_get_period_size(da->params, &(da->periodSize), NULL));
		da->currentPeriod = 0;

		if (ret < 0) {
			debugError("nOpen: ", ret);
			Java_com_ibm_realtime_synth_modules_DirectAudioSink_nClose(env, clazz, (long) da);
			da = NULL;
		} else {
			DBG1("opened successfully device '%s'\n", sDevName);
#ifdef DEBUG
			printHardwareParams(da);
#endif
		}
	}
	(*env)->ReleaseStringUTFChars(env, devName, sDevName);
	return (jlong) (INT_PTR) (da);
}

/*
 * Class:     com_ibm_realtime_synth_modules_DirectAudioSink
 * Method:    nGetBufferSize
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_com_ibm_realtime_synth_modules_DirectAudioSink_nGetBufferSize
(JNIEnv *env, jclass clazz, jlong handle) {
	snd_pcm_uframes_t bufferSize;
	snd_pcm_uframes_t periodSize;
	int ret = 0;
	DirectAlsaHandle* da = (DirectAlsaHandle*) (INT_PTR) handle;
	if (handle == 0) return 0;
	ACHECK(snd_pcm_hw_params_current(da->alsaHandle, da->params));
	ACHECK(snd_pcm_hw_params_get_buffer_size(da->params, &bufferSize));
	ACHECK(snd_pcm_hw_params_get_period_size(da->params, &periodSize, NULL));
#ifdef DEBUG
	printf("Native buffer size=%d samples, period size=%d samples\n", (int) bufferSize, (int) periodSize);
#endif
	if (ret < 0) {
		debugError("nGetBufferSize: ", ret);
		return 0;
	}
	return bufferSize;
}

/*
 * Class:     com_ibm_realtime_synth_modules_DirectAudioSink
 * Method:    nGetPeriodSize
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_com_ibm_realtime_synth_modules_DirectAudioSink_nGetPeriodSize
(JNIEnv *env, jclass clazz, jlong handle) {
	DirectAlsaHandle* da = (DirectAlsaHandle*) (INT_PTR) handle;
	if (handle == 0) return 0;
	return (int) da->periodSize;
}

/*
 * Class:     com_ibm_realtime_synth_modules_DirectAudioSink
 * Method:    nGetSampleBitType
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_com_ibm_realtime_synth_modules_DirectAudioSink_nGetSampleBitType
(JNIEnv* env, jclass clazz, jlong handle) {
	DirectAlsaHandle* da = (DirectAlsaHandle*) (INT_PTR) handle;
	if (handle == 0) return 0;
	return da->sampleBitType;
}


/*
 * Class:     com_ibm_realtime_synth_modules_DirectAudioSink
 * Method:    nClose
 * Signature: (J)Z
 */
JNIEXPORT jboolean JNICALL Java_com_ibm_realtime_synth_modules_DirectAudioSink_nClose
(JNIEnv *env, jclass clazz, jlong handle) {
	DirectAlsaHandle* da = (DirectAlsaHandle*) (INT_PTR) handle;
	if (handle == 0) return 0;
	if (da->alsaHandle != NULL) {
		snd_pcm_close(da->alsaHandle);
	}
	if (da->params != NULL) {
		snd_pcm_hw_params_free(da->params);
	}
	free(da);
	DBG("closed successfully");
	return 1;
}


#define WAIT_MILLIS_ON_UNAVAILABLE 100

/*
 *   Underrun and suspend recovery
 */
int xrun_recovery(snd_pcm_t *handle, int err, int* underrun) {
	/* only report an underrun if REALLY underrun... */
	debugError("xrun: ", err);
	if (err == -EPIPE || err == -EBADFD) {    /* under-run */
		err = snd_pcm_prepare(handle);
		*underrun = 1;
		if (err < 0) {
			debugError("underrun recovery failed: ", err);
		}
	} else if (err == -ESTRPIPE) {
		while ((err = snd_pcm_resume(handle)) == -EAGAIN) {
			/* wait until the suspend flag is released */
			usleep(WAIT_MILLIS_ON_UNAVAILABLE * 1000);
		}
		if (err < 0) {
			err = snd_pcm_prepare(handle);
			if (err < 0) {
				debugError("suspend recovery failed: ", err);
			}
		}
	} else if (err == -11) {
		/* resource temporarily unavailable */
		DBG1("wait for %dms\n", WAIT_MILLIS_ON_UNAVAILABLE);
		usleep(WAIT_MILLIS_ON_UNAVAILABLE * 1000);
		/* retry */
		err = 0;
	} else {
		DBG1("unknown error. Wait for %dms\n", WAIT_MILLIS_ON_UNAVAILABLE);
		usleep(WAIT_MILLIS_ON_UNAVAILABLE * 1000);
	}
	return err;
}


/*
 * Class:     com_ibm_realtime_synth_modules_DirectAudioSink
 * Method:    nWrite
 * Signature: (JLjava/lang/Object;II)I
 */
JNIEXPORT jint JNICALL Java_com_ibm_realtime_synth_modules_DirectAudioSink_nWrite
(JNIEnv *env, jclass clazz, jlong handle, jobject data, jint offset, jint length) {
	char* originalNData;
	char* nData;
	int ret;
	int underrun = 0;
	int maxTrials = 20;
	DirectAlsaHandle* da = (DirectAlsaHandle*) (INT_PTR) handle;
	if (handle == 0) return -1;
	if (length < da->frameSize) {
		//printf("Tried to write %d bytes!\n", length);
		return 0;
	}
	nData = (char*) ((*env)->GetByteArrayElements(env, data, NULL));
	originalNData = nData;
	// apply the offset
	nData += offset;
	do {
		ret = snd_pcm_writei(da->alsaHandle, nData, length / da->frameSize);
		//if (ret != length / da->frameSize) {
		//	printf("tried to write %d samples, result: %d\n", length/da->frameSize, ret);
		//}
		if (ret < 0) {
			ret = xrun_recovery(da->alsaHandle, ret, &underrun);
		} else {
			da->writtenFrames+=ret;
			break;
		}
	} while ((ret == 0) && (--maxTrials > 0));
	// release the native array
	(*env)->ReleaseByteArrayElements(env, data, (jbyte*) originalNData, JNI_ABORT);

	if (ret > 0) {
		ret *= da->frameSize;
		if (underrun && da->writtenFrames > MIN_FRAMES_BEFORE_REPORTED_UNDERRUNS) {
			ret |= com_ibm_realtime_synth_modules_DirectAudioSink_UNDERRUN_FLAG;
		}
	}
	return ret;
}

/*
 * Class:     com_ibm_realtime_synth_modules_DirectAudioSink
 * Method:    nGetPosition
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_com_ibm_realtime_synth_modules_DirectAudioSink_nGetPosition
(JNIEnv *env, jclass clazz, jlong handle) {
	int ret = 0;
	snd_pcm_sframes_t delay;
	DirectAlsaHandle* da = (DirectAlsaHandle*) (INT_PTR) handle;
	if (handle == 0) return 0;
	snd_pcm_hwsync(da->alsaHandle);
	ret = snd_pcm_delay(da->alsaHandle, &delay);
	if (ret >= 0) {
#ifndef USE_CALLBACK
		ret = da->writtenFrames - ((int) delay);
#else
		ret = (((jlong) da->periodSize) * (da->currentPeriod + 1))
			- ((jlong) delay);
		DBG1("Period vs writtenFrames: %d\n",
			 (int) ((((jlong) da->periodSize) * (da->currentPeriod + 1))  - da->writtenFrames));
#endif
		if (ret < 0) {
			ret = 0;
		}
	} else {
		ret = da->writtenFrames;
	}
	return ret;
}

#define JCHECK(a, b) if ((a) == NULL) { DBG1("Cannot get %s\n", b); return; }

/*
 * Class:     com_ibm_realtime_synth_modules_DirectAudioSink
 * Method:    nFillDeviceNames
 * Signature: (Ljava/util/List;)V
 */
JNIEXPORT void JNICALL Java_com_ibm_realtime_synth_modules_DirectAudioSink_nFillDeviceNames
(JNIEnv* env, jclass clazz, jobject list) {
	int ret = 0;
	snd_ctl_t *handle;
	int card;
	snd_ctl_card_info_t* cardInfo;
	snd_pcm_info_t* pcmInfo;
	snd_pcm_hw_params_t* hwParams;
	snd_pcm_t* pcm;
	//snd_pcm_uframes_t frames;
	int dev;
	char thisDeviceName[300];
	char* cName;

	jclass dasdeClass; // DirectAudioSinkDeviceEntry
	jmethodID dasdeConstructor;
	jclass listClass;
	jmethodID listAdd;
	jobject deviceEntry;
	jstring jDeviceName;
	jstring jName;
	unsigned int minChannels, maxChannels, minRate, maxRate;
	int formatMask, minTransfer, fifoSize;
#ifdef DEBUG
	unsigned int minPeriod, maxPeriod, minBuffer, maxBuffer, minPeriods, maxPeriods;
	snd_pcm_uframes_t minPeriodSamples, maxPeriodSamples, minBufferSamples, maxBufferSamples;
#endif
	int blockTransfers, doubleBuffering;

	// first get the Java method descriptors:
	// - for the DirectAudioSinkDeviceEntry class

	JCHECK(dasdeClass = (*env)->FindClass(env,
										  "com/ibm/realtime/synth/modules/DirectAudioSinkDeviceEntry"),
		   "DirectAudioSinkDeviceEntry class");
	JCHECK(dasdeConstructor = (*env)->GetMethodID(env, dasdeClass, "<init>",
												  "(Ljava/lang/String;Ljava/lang/String;IIIIIIIZZ)V"),
		   "DirectAudioSinkDeviceEntry constructor");
	// - for List.add(Object)
	JCHECK(listClass = (*env)->FindClass(env,
										 "java/util/List"),
		   "List class");
	JCHECK(listAdd = (*env)->GetMethodID(env, listClass, "add",
										 "(Ljava/lang/Object;)Z"),
		   "List.add(Object) method");

	snd_ctl_card_info_malloc(&cardInfo);
	snd_pcm_info_malloc(&pcmInfo);
	snd_pcm_hw_params_malloc(&hwParams);

	card = -1;
	ret = snd_card_next(&card);
	while (ret >= 0 && card >= 0) {
		sprintf(thisDeviceName, "hw:%d", card);

		ACHECK(snd_ctl_open(&handle, thisDeviceName, 0));
		ACHECK(snd_ctl_card_info(handle, cardInfo));
		dev = -1;
		ret = snd_ctl_pcm_next_device(handle, &dev);
		while (ret >= 0 && dev >= 0) {
			ret = 0;
			snd_pcm_info_set_device(pcmInfo, dev);
			// only use one subdevice
			snd_pcm_info_set_subdevice(pcmInfo, 0);
			snd_pcm_info_set_stream(pcmInfo, SND_PCM_STREAM_PLAYBACK);
			ret = snd_ctl_pcm_info(handle, pcmInfo);
			if (ret >= 0) {
				//cName = snd_pcm_info_get_name(pcmInfo);
				//cName = snd_pcm_info_get_id(pcmInfo);
				if (snd_card_get_name(card, &cName) < 0) {
					// if failed to get card name, use pcm stream
					cName = (char*) snd_pcm_info_get_name(pcmInfo);
				}
				sprintf(thisDeviceName, "hw:%d,%d", card, dev);
				// retrieve card format capabilities
				pcm = NULL;
				ACHECK(snd_pcm_open(&pcm, thisDeviceName, SND_PCM_STREAM_PLAYBACK, SND_PCM_NONBLOCK));
				ACHECK(snd_pcm_hw_params_any(pcm, hwParams));
				ACHECK(snd_pcm_hw_params_get_channels_min(hwParams, &minChannels));
				ACHECK(snd_pcm_hw_params_get_channels_max(hwParams, &maxChannels));
				ACHECK(snd_pcm_hw_params_get_rate_min(hwParams, &minRate, NULL));
				ACHECK(snd_pcm_hw_params_get_rate_max(hwParams, &maxRate, NULL));
#ifdef DEBUG
				ACHECK(snd_pcm_hw_params_get_period_time_min(hwParams, &minPeriod, NULL));
				ACHECK(snd_pcm_hw_params_get_period_time_max(hwParams, &maxPeriod, NULL));
				ACHECK(snd_pcm_hw_params_get_period_size_min(hwParams, &minPeriodSamples, NULL));
				ACHECK(snd_pcm_hw_params_get_period_size_max(hwParams, &maxPeriodSamples, NULL));
				ACHECK(snd_pcm_hw_params_get_buffer_time_min(hwParams, &minBuffer, NULL));
				ACHECK(snd_pcm_hw_params_get_buffer_time_max(hwParams, &maxBuffer, NULL));
				ACHECK(snd_pcm_hw_params_get_buffer_size_min(hwParams, &minBufferSamples));
				ACHECK(snd_pcm_hw_params_get_buffer_size_max(hwParams, &maxBufferSamples));
				ACHECK(snd_pcm_hw_params_get_periods_min(hwParams, &minPeriods, NULL));
				ACHECK(snd_pcm_hw_params_get_periods_max(hwParams, &maxPeriods, NULL));
#endif
				ACHECK(fillFormatMask(pcm, hwParams, &formatMask));
				//ACHECK(snd_pcm_hw_params_get_min_align(hwParams, &frames));
				//minTransfer = (int) frames;
				minTransfer = -1; // cannot find out, since multiple minTransfers are present
				fifoSize = snd_pcm_hw_params_get_fifo_size(hwParams);
				blockTransfers = snd_pcm_hw_params_is_block_transfer(hwParams);
				doubleBuffering = snd_pcm_hw_params_is_batch(hwParams);
				if (pcm != NULL) {
					snd_pcm_close(pcm);
					pcm = NULL;
				}
				// create the Java info object
				jDeviceName = (*env)->NewStringUTF(env, thisDeviceName);
				jName = (*env)->NewStringUTF(env, cName);
				deviceEntry = (*env)->NewObject(env, dasdeClass, dasdeConstructor,
												jDeviceName, jName,
												minChannels, maxChannels, minRate, maxRate,
												formatMask, minTransfer, fifoSize,
												blockTransfers, doubleBuffering);
				// add the device entry to the list
				(*env)->CallBooleanMethod(env, list, listAdd, deviceEntry);
#ifdef DEBUG
				DBG1("device %s caps:\n", thisDeviceName);
				DBG2("   minChannels=%dus  maxChannels=%dus\n", minChannels, maxChannels);
				DBG2("   minRate=%dus  maxRate=%dus\n", minRate, maxRate);
				DBG2("   minPeriod=%dus  maxPeriod=%dus\n", minPeriod, maxPeriod);
				DBG2("   minPeriod=%d samples  maxPeriod=%d samples\n", (int) minPeriodSamples, (int) maxPeriodSamples);
				DBG2("   minBuffer=%dus  maxBuffer=%dus\n", minBuffer, maxBuffer);
				DBG2("   minBuffer=%d samples  maxBuffer=%d samples\n", (int) minBufferSamples, (int) maxBufferSamples);
				DBG2("   minPeriods=%d  maxPeriods=%d\n", minPeriods, maxPeriods);
				DBG3("   fifoSize=%d  blockTransfers=%d  doubleBuffering=%d\n", fifoSize, blockTransfers, doubleBuffering);
#endif

				// also report plug hardware
				sprintf(thisDeviceName, "plughw:%d,%d", card, dev);
				jDeviceName = (*env)->NewStringUTF(env, thisDeviceName);
				deviceEntry = (*env)->NewObject(env, dasdeClass, dasdeConstructor,
												jDeviceName, jName,
												minChannels, maxChannels, minRate, maxRate,
												formatMask, minTransfer, fifoSize,
												blockTransfers, doubleBuffering);
				// add the device entry to the list
				(*env)->CallBooleanMethod(env, list, listAdd, deviceEntry);

				if ((*env)->ExceptionOccurred(env)) {
					DBG("Native Exception occured!");
					return;
				}
				if (ret < 0) {
					debugError("nFillDeviceNames: error occured", ret);
				}
			}
			ret = snd_ctl_pcm_next_device(handle, &dev);
		}
		snd_ctl_close(handle);
		ret = snd_card_next(&card);
	}

	snd_pcm_hw_params_free(hwParams);
	snd_ctl_card_info_free(cardInfo);
	snd_pcm_info_free(pcmInfo);
}
