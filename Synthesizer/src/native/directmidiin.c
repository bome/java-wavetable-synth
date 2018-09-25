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
 * Native implementation of the direct ALSA MIDI input
 * access.
 *
 * @author Florian Bomers
 */

/* IDEA: use the audio clock of an open audio device as time source... */

/* if defined, use poll() system call instead of blocking read() */
#define USE_POLL

#include <malloc.h>
#include <stdio.h>
#include <sys/time.h>
#include <alsa/asoundlib.h>
#include "directmidiin.h"
#ifdef USE_POLL
#include <poll.h>
#define POLL_DESCRIPTOR_COUNT 5
#endif

/* add a constant delay of 600 microseconds to all event times to account
 * for the time it takes to transmit the following 2 event bytes 
 */
#define CONSTANT_DELAY_NANOS      600000UL

/* ALSA's rawmidi timestamp facility does not work, so use realtime for now */
#define USE_REALTIME_TIMESTAMP

#ifdef DEBUG
#define DBG(a) printf("ALSA MIDI lib: %s\n", a); fflush(stdout)
#define DBG1(format, a) printf("ALSA MIDI lib: "format"\n", a); fflush(stdout)
#define DBG2(format, a, b) printf("ALSA MIDI lib: "format"\n", a, b); fflush(stdout)
#else
#define DBG(a)
#define DBG1(format, a)
#define DBG2(format, a, b)
#endif

#define ACHECK(code) if (ret >= 0) { ret = code; if (ret < 0) DBG1("FAILED CODE: %s\n", #code); }

#define TRUE 1
#define FALSE 0

// define an integer with the size of a pointer to work around compiler warnings
#ifdef SYSTEM64BIT
#define INT_PTR jlong
#else
#define INT_PTR jint
#endif


typedef struct {
	snd_rawmidi_t *alsaHandle;
  	snd_rawmidi_params_t *params;
#ifndef USE_REALTIME_TIMESTAMP
  	/* status object for getting timestamps */
  	snd_rawmidi_status_t *status_timestamp;
#endif
  	/* current running status. 0 if none */
  	unsigned char runningStatus;
  	/* the current message */
  	int message;
	/* the incoming timestamp, in nanoseconds, of message (first byte) */
	jlong timestamp;
  	/* bit shift of following byte to put into message */
  	int shift;
  	/* if TRUE, currently reading from device */
  	int reading;
  	/* if TRUE, the device is closing */
  	int closing;
  	/* the nanosecond time of starting the device */
  	jlong startTime;
#ifdef USE_POLL
	struct pollfd poll_descriptors[POLL_DESCRIPTOR_COUNT];
	int poll_descriptor_count;
#endif
} DirectAlsaHandle;

void debugError(char* method, int errorCode) {
	if (errorCode < 0) {
		char dbg[1000];
		sprintf(dbg, "%s: %s", method, snd_strerror(errorCode));
		DBG(dbg);
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


jlong getTimeInNanoseconds() {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return ((((jlong) tv.tv_sec) * 1000000UL) + tv.tv_usec)*1000UL;
}


/*
 * Class:     com_ibm_realtime_synth_modules_DirectMidiIn
 * Method:    nOpen
 * Signature: (Ljava/lang/String;)J
 */
JNIEXPORT jlong JNICALL Java_com_ibm_realtime_synth_modules_DirectMidiIn_nOpen
  (JNIEnv *env, jclass clazz, jstring devName) {
	int ret = 0;
	const char* sDevName;
	int blocking = TRUE;

#ifdef USE_POLL
	/* don't use blocking */
	blocking = FALSE;
#endif

	// check parameters
	sDevName = (*env)->GetStringUTFChars(env, devName, NULL);
	if (sDevName == NULL) {
		DBG("Could not get device name.");
		return 0;
	}

	DirectAlsaHandle* da = (DirectAlsaHandle*) calloc(sizeof(DirectAlsaHandle), 1);
	if (da != NULL) {
#ifndef USE_REALTIME_TIMESTAMP
		/* allocate status objects */
		ACHECK(snd_rawmidi_status_malloc(&(da->status_timestamp)));
#endif

		/* Allocate a hardware parameters object. */
		ACHECK(snd_rawmidi_params_malloc(&(da->params)));

		/* open the device */
		ACHECK(snd_rawmidi_open(&(da->alsaHandle), NULL, (char*) sDevName, SND_RAWMIDI_NONBLOCK));

		/* Fill the params object with current values. */
		ACHECK(snd_rawmidi_params_current(da->alsaHandle, da->params));
		/* Set the desired hardware parameters. */
		/* buffer size - not necessary
		ACHECK(snd_rawmidi_params_set_buffer_size(da->alsaHandle, da->params, bufferSizeInBytes));
		*/

		/* wake up after any byte that is written to the queue */
		ACHECK(snd_rawmidi_params_set_avail_min(da->alsaHandle, da->params, 1));

		/* Write the parameters to the driver */
		ACHECK(snd_rawmidi_params(da->alsaHandle, da->params));

		/* set the blocking mode */
		ACHECK(snd_rawmidi_nonblock(da->alsaHandle, blocking?0:1));

#ifdef USE_POLL
		if (ret >= 0) {
			da->poll_descriptor_count = snd_rawmidi_poll_descriptors(da->alsaHandle, 
																	 da->poll_descriptors,
																	 POLL_DESCRIPTOR_COUNT);
			if (da->poll_descriptor_count <= 0) {
				debugError("ERROR: snd_rawmidi_poll_descriptors returned %d poll descriptors", 
						   da->poll_descriptor_count);
				ret = -1;
			}
		}
#endif
		if (ret < 0) {
			debugError("nOpen: ", ret);
			Java_com_ibm_realtime_synth_modules_DirectMidiIn_nClose(env, clazz, (long) da);
			da = NULL;
		} else {
			da->startTime = getTimeInNanoseconds();
			DBG1("opened successfully device '%s'", sDevName);
		}
	}
	(*env)->ReleaseStringUTFChars(env, devName, sDevName);
	return (jlong) (INT_PTR) (da);
}

/*
 * Class:     com_ibm_realtime_synth_modules_DirectMidiIn
 * Method:    nClose
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_com_ibm_realtime_synth_modules_DirectMidiIn_nClose
  (JNIEnv *env, jclass clazz, jlong handle) {
	DirectAlsaHandle* da = (DirectAlsaHandle*) (INT_PTR) handle;
	if (handle == 0) return 0;
	da->closing = TRUE;
	if (da->reading) {
		/* signal Java layer to wait a little more for the read function to return */
		return 2;
	}
	if (da->alsaHandle != NULL) {
		snd_rawmidi_close(da->alsaHandle);
		da->alsaHandle = NULL;
	}
	if (da->params != NULL) {
		snd_rawmidi_params_free(da->params);
	}
#ifndef USE_REALTIME_TIMESTAMP
	if (da->status_timestamp != NULL) {
		snd_rawmidi_status_free(da->status_timestamp);
	}
#endif
	free(da);
	DBG("closed successfully");
	return 1;
 }

/* get time stamp in nanoseconds */
jlong getTimeStamp(DirectAlsaHandle* da) {
#ifdef USE_REALTIME_TIMESTAMP
	return getTimeInNanoseconds() - da->startTime;
#else
	/* $$fb the following method does not work. The ALSA timestamp
	 * is defined as being the start time, i.e. always 0.
	 */
	snd_htimestamp_t timestamp;
	if (snd_rawmidi_status(da->alsaHandle, da->status_timestamp)>=0) {
		snd_rawmidi_status_get_tstamp(da->status_timestamp, &timestamp);
		DBG2("time stamp: %ds %dns",  timestamp.tv_sec, timestamp.tv_nsec);
		return ((jlong) timestamp.tv_sec) * 1000000000 + (((jlong) timestamp.tv_nsec));
	} else {
		DBG("Cannot get status for timestamp");
	}
	return 0;
#endif
}

/*
 * Class:     com_ibm_realtime_synth_modules_DirectMidiIn
 * Method:    nReadShort
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_com_ibm_realtime_synth_modules_DirectMidiIn_nReadShort
  (JNIEnv *env, jclass clazz, jlong handle) {
	int readBytes;
	int currentStatus;
	int thisStatus;
	unsigned char byteRead;
	int done = FALSE;
	DirectAlsaHandle* da = (DirectAlsaHandle*) (INT_PTR) handle;
	if (handle == 0) return 3; /* device closed */

	/* init */
	if (da->shift == 0) {
	  da->message = 0;
	}
	currentStatus = da->message & 0xFF;

	while (!done) {
		/* first, read a byte */
		da->reading = TRUE;
		readBytes = snd_rawmidi_read(da->alsaHandle, &byteRead, 1);
#ifdef USE_POLL
		if (readBytes <= 0 && !da->closing) {
			/* wait for an event to arrive */
			poll(da->poll_descriptors, da->poll_descriptor_count, 200);
			if (!da->closing) {
				continue;
			}
		}
#endif
		da->reading = FALSE;
		if (da->closing) {
			return 3;
		}
		if (readBytes != 1) {
			return 1; /* error */
		}
		/*DBG2("Read byte: %2x (shift=%d)", byteRead, da->shift);*/
		da->message |= ((jlong) byteRead) << da->shift;

		/* parse the MIDI byte */
		if (byteRead >= 0xF8) {
			/* realtime message */
			/* does not affect running status */
			/* put timestamp to message */
			da->timestamp = getTimeStamp(da) + CONSTANT_DELAY_NANOS;
			/* ignored messages are finished now */
			switch (byteRead) {
			case 0xF9: /* ignored, fall through */
			case 0xFD: {
				/* ignored messages */
				da->shift = 0;
				currentStatus = 0;
				continue;
			}
			default: {
				/* continue current message in next call to this function */
				return ((jlong) byteRead) | ((da->timestamp >> 10) << 22);
			}
		    }
		} else
		if (byteRead >= 0xF0) {
			if (!byteRead == 0xF7) {
				/* put timestamp to message if not end-of-sys-ex */
				da->timestamp = getTimeStamp(da) + CONSTANT_DELAY_NANOS;
			}
			/* reset running status */
			da->runningStatus = 0;
			if (currentStatus > 0) {
				if (currentStatus == 0xF4 || currentStatus == 0xF4) {
					/* end of ignored message */
				} else {
					DBG1("unexpected status byte '%2x', data byte expected!", byteRead);
				}
				da->message = byteRead;
			}
			/* special handling for sys ex and one-byte messages*/
			switch (byteRead) {
			case 0xF6: {
				/* tune request has not data bytes */
				/* one byte message */
				return ((jlong) byteRead) | ((da->timestamp >> 10) << 22);
			case 0xF7: /* end sys ex */
				/* TODO: handle long messages */
				/* for now, ignore */
				da->shift = 0;
				currentStatus = 0;
				continue;
			}
			da->shift = 8;
			currentStatus = byteRead;
			}
		}  else
		if (byteRead >= 0x80) {
			/* got a status byte of a regular message */
			da->runningStatus = byteRead;
			/* put timestamp to message */
			da->timestamp = getTimeStamp(da) + CONSTANT_DELAY_NANOS;
			if (currentStatus > 0) {
				DBG1("unexpected status byte '%2x', data byte expected!", byteRead);
				da->message = byteRead;
			}
			da->shift = 8;
			currentStatus = byteRead;
		} else {
		  /* data bytes */
			/* first evaluate running status */
			if (currentStatus == 0) {
				currentStatus = da->runningStatus;
				da->message = ((jlong) currentStatus) | (((jlong) byteRead) << 8);
				da->shift = 8;
				/* put timestamp to message */
				da->timestamp = getTimeStamp(da) + CONSTANT_DELAY_NANOS;
			}
			thisStatus = (currentStatus < 0xF0)?currentStatus & 0xF0:currentStatus;
			switch (thisStatus) {
				/* one data byte messages */
				case 0xC0:   /* fall through (program change) */
				case 0xD0:   /* fall through (channel pressure) */
				case 0xF1:   /* fall through (MTC quarter frame) */
				case 0xF3: { /* (MTC song select) */
					if (da->shift == 8) {
						done = TRUE;
						da->shift = 0;
					} else {
						DBG2("unexpected data byte '%2x' in message with status '%2x'", byteRead, currentStatus);
						/* reset */
						currentStatus = 0;
						da->runningStatus = 0;
						da->shift = 0;
					}
					break;
				}

				/* two data bytes messages */
				case 0x80:   /* fall through (note off) */
				case 0x90:   /* fall through (note on) */
				case 0xA0:   /* fall through (aftertouch) */
				case 0xB0:   /* fall through (controller) */
				case 0xE0:   /* fall through (pitch bend) */
				case 0xF2: { /* (MTC song position pointer) */
					if (da->shift == 15) {
						done = TRUE;
					} else if (da->shift == 8) {
					  da->shift += 7;
					} else {
						DBG2("unexpected data byte '%2x' in message with status '%2x'", byteRead, currentStatus);
						/* reset */
						currentStatus = 0;
						da->runningStatus = 0;
						da->shift = 0;
					}
					break;
				}

				/* long messages */
				case 0xF0: {
					/* sys ex */
					/* TODO: handle sys ex */
					continue;
				}
				case 0xF4: /* ignored, fall through */
				case 0xF5: {
					/* ignored messages */
					continue;
				}
			} /* of switch */
		}
	}
	/* control flow gets here if a regular message has been successfully read */
	da->shift = 0;
	/* add time stamp */
	return ((jlong) da->message) | ((da->timestamp >> 10) << 22);
}

/*
 * Class:     com_ibm_realtime_synth_modules_DirectMidiIn
 * Method:    nGetLongMessageLength
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_com_ibm_realtime_synth_modules_DirectMidiIn_nGetLongMessageLength
  (JNIEnv *env, jclass clazz, jlong handle) {
	/* TODO: implement long MIDI */
	return 0;
}

/*
 * Class:     com_ibm_realtime_synth_modules_DirectMidiIn
 * Method:    nReadLong
 * Signature: (JLjava/lang/Object;II)I
 */
JNIEXPORT jint JNICALL Java_com_ibm_realtime_synth_modules_DirectMidiIn_nReadLong
  (JNIEnv *env, jclass clazz, jlong handle, jobject array, jint offset, jint length) {
	/* TODO: implement long MIDI */
	return 0;
}

/*
 * Class:     com_ibm_realtime_synth_modules_DirectMidiIn
 * Method:    nGetTimeStamp
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_com_ibm_realtime_synth_modules_DirectMidiIn_nGetTimeStamp
  (JNIEnv *env, jclass clazz, jlong handle) {
	DirectAlsaHandle* da = (DirectAlsaHandle*) (INT_PTR) handle;
	if (handle == 0) return 0;
	return getTimeStamp(da);
}


#define JCHECK(a, b) if ((a) == NULL) { DBG1("Cannot get %s\n", b); return; }

/*
 * Class:     com_ibm_realtime_synth_modules_DirectMidiIn
 * Method:    nFillDeviceNames
 * Signature: (Ljava/util/List;)V
 */
JNIEXPORT void JNICALL Java_com_ibm_realtime_synth_modules_DirectMidiIn_nFillDeviceNames
  (JNIEnv *env, jclass clazz, jobject list) {
	int ret = 0;
	snd_ctl_t *handle;
	int card;
	//snd_ctl_card_info_t* cardInfo;
	snd_rawmidi_info_t* info;
	snd_rawmidi_params_t* params;

	int dev;
	char thisDeviceName[300];
	char* cName;

	jclass dmideClass; // DirectMidiInDeviceEntry
	jmethodID dmideConstructor;
	jclass listClass;
	jmethodID listAdd;
	jobject deviceEntry;
	jstring jDeviceName;
	jstring jName;

	// first get the Java method descriptors:
	// - for the DirectMidiInDeviceEntry class

	JCHECK(dmideClass = (*env)->FindClass(env,
		"com/ibm/realtime/synth/modules/DirectMidiInDeviceEntry"),
		"DirectMidiInDeviceEntry class");
	JCHECK(dmideConstructor = (*env)->GetMethodID(env, dmideClass, "<init>",
		"(Ljava/lang/String;Ljava/lang/String;)V"),
		"DirectMidiInDeviceEntry constructor");
	// - for List.add(Object)
	JCHECK(listClass = (*env)->FindClass(env,
		"java/util/List"),
		"List class");
	JCHECK(listAdd = (*env)->GetMethodID(env, listClass, "add",
		"(Ljava/lang/Object;)Z"),
		"List.add(Object) method");

	// add the default device
	jDeviceName = (*env)->NewStringUTF(env, "default");
	jName = (*env)->NewStringUTF(env, "ALSA default MIDI device");
	deviceEntry = (*env)->NewObject(env, dmideClass, dmideConstructor,
		jDeviceName, jName);
	(*env)->CallBooleanMethod(env, list, listAdd, deviceEntry);

	//snd_ctl_card_info_malloc(&cardInfo);
	snd_rawmidi_info_malloc(&info);
	snd_rawmidi_params_malloc(&params);

	card = -1;
	ret = snd_card_next(&card);
	while (ret >= 0 && card >= 0) {
		sprintf(thisDeviceName, "hw:%d", card);

		ACHECK(snd_ctl_open(&handle, thisDeviceName, 0));
		//ACHECK(snd_ctl_card_info(handle, cardInfo));
		dev = -1;
		ret = snd_ctl_rawmidi_next_device(handle, &dev);
		while (ret >= 0 && dev >= 0) {
			ret = 0;
			snd_rawmidi_info_set_device(info, dev);
			// only use one subdevice
			snd_rawmidi_info_set_subdevice(info, 0);
			snd_rawmidi_info_set_stream(info, SND_RAWMIDI_STREAM_INPUT);
			ret = snd_ctl_rawmidi_info(handle, info);
			if (ret >= 0) {
				if (snd_card_get_name(card, &cName) < 0) {
					// if failed to get card name, use stream name
					cName = (char*) snd_rawmidi_info_get_name(info);
				}
				sprintf(thisDeviceName, "hw:%d,%d", card, dev);
				// create the Java info object
				jDeviceName = (*env)->NewStringUTF(env, thisDeviceName);
				jName = (*env)->NewStringUTF(env, cName);
				deviceEntry = (*env)->NewObject(env, dmideClass, dmideConstructor,
					jDeviceName, jName);
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
			ret = snd_ctl_rawmidi_next_device(handle, &dev);
		}
		snd_ctl_close(handle);
		ret = snd_card_next(&card);
	}


	// add the virtual device
	jDeviceName = (*env)->NewStringUTF(env, "virtual");
	jName = (*env)->NewStringUTF(env, "ALSA virtual MIDI device");
	deviceEntry = (*env)->NewObject(env, dmideClass, dmideConstructor,
		jDeviceName, jName);
	(*env)->CallBooleanMethod(env, list, listAdd, deviceEntry);

	snd_rawmidi_params_free(params);
	//snd_ctl_card_info_free(cardInfo);
	snd_rawmidi_info_free(info);
}
