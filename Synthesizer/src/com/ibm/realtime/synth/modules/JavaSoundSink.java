/*
 * (C) Copyright IBM Corp. 2005, 2008. All Rights Reserved
 */
package com.ibm.realtime.synth.modules;

import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

import com.ibm.realtime.synth.utils.AudioUtils;

import com.ibm.realtime.synth.engine.*;

import static com.ibm.realtime.synth.utils.Debug.*;

/**
 * An AudioSink that writes to a Java Sound SourceDataLine.
 * 
 * @author florian
 */
public class JavaSoundSink implements AudioSink, AdjustableAudioClock {

	public static boolean DEBUG_SINK = false;

	/**
	 * The SourceDataLine used to access the soundcard
	 */
	private SourceDataLine sdl;

	/**
	 * the name of the open device
	 */
	private String devName;

	/**
	 * The current (or previous) sample rate.
	 */
	private double sampleRate = 44100.0;

	/**
	 * List of usable audio devices, i.e. they provide a SourceDataLine (line
	 * out/speaker).
	 */
	private static List<Mixer.Info> devList;

	/**
	 * A temporary byte buffer for conversion to the native format
	 */
	private byte[] byteBuffer;

	/**
	 * Flag to track if the line was started
	 */
	private boolean started = false;

	/**
	 * The current offset of the audio clock (interface AdjustableAudioClock) in
	 * samples.
	 */
	private long clockOffsetSamples = 0;

	/**
	 * Constructor for this sink
	 */
	public JavaSoundSink() {
		setupAudioDevices();
	}

	/**
	 * Open the soundcard with the given format and buffer size in milliseconds.
	 * The audio time (regardless of the time offset) will be reset to 0.
	 */
	public synchronized void open(int devIndex, int bufferSizeInMillis,
			AudioFormat format) throws LineUnavailableException, Exception {
		open(devIndex, format, (int) AudioUtils.millis2samples(
				bufferSizeInMillis, format.getSampleRate()));
	}

	/**
	 * Open the soundcard with the given format. The audio time (regardless of
	 * the time offset) will be reset to 0.
	 */
	public synchronized void open(int devIndex, AudioFormat format,
			int bufferSizeInSamples) throws LineUnavailableException, Exception {
		if (devIndex < -1 || devIndex >= devList.size()) {
			throw new Exception("illegal audio device index: " + devIndex);
		}
		if (devIndex < 0) {
			if (DEBUG_SINK) debugNoNewLine("Opening default soundcard...");
			devName = "(default)";
			sdl = AudioSystem.getSourceDataLine(format);
			if (DEBUG_SINK) debug(sdl.getClass().getName());
		} else {
			Mixer.Info info = devList.get(devIndex);
			devName = info.getName();
			if (DEBUG_SINK) {
				debug("Opening audio out device: " + devName + " ("
						+ info.getDescription() + ")");
			}
			sdl = AudioSystem.getSourceDataLine(format, info);
		}
		sdl.open(format, bufferSizeInSamples * format.getFrameSize());

		sampleRate = (double) format.getSampleRate();
		if (DEBUG_SINK) {
			debug("Buffer size = "
					+ sdl.getBufferSize()
					+ " bytes = "
					+ (sdl.getBufferSize() / format.getFrameSize())
					+ " samples = "
					+ format2(AudioUtils.samples2micros(sdl.getBufferSize()
							/ format.getFrameSize(), format.getSampleRate()) / 1000.0)
					+ " millis");
		}
	}

	public synchronized void close() {
		started = false;
		if (sdl != null) {
			sdl.close();
		}
		if (DEBUG_SINK) debug("closed soundcard: "+devName);
	}

	public boolean isOpen() {
		return (sdl != null && sdl.isOpen());
	}

	public AudioFormat getFormat() {
		return sdl.getFormat();
	}

	public static List<Mixer.Info> getDeviceList() {
		setupAudioDevices();
		return devList;
	}

	public static boolean isJavaSoundEngine(Mixer.Info info) {
		return info.getName().indexOf("Java Sound Audio Engine") >= 0;
	}

	private static void setupAudioDevices() {
		if (devList == null) {
			if (DEBUG_SINK) debugNoNewLine("Gathering Audio devices...");
			devList = new ArrayList<Mixer.Info>();
			Mixer.Info[] infos = AudioSystem.getMixerInfo();
			// go through all audio devices and see if they provide input
			// line(s)
			for (Mixer.Info info : infos) {
				Mixer m = AudioSystem.getMixer(info);
				Line.Info[] lineInfos = m.getSourceLineInfo();
				for (Line.Info lineInfo : lineInfos) {
					if (lineInfo instanceof DataLine.Info) {
						// we found a source data line, so we can add this mixer
						// to the list of supported devices
						devList.add(info);
						break;
					}
				}
			}
			if (DEBUG_SINK) {
				debug("done (" + devList.size() + " devices available).");
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.ibm.realtime.synth.engine.AudioSink#write(com.ibm.realtime.synth.engine.AudioBuffer)
	 */
	public synchronized void write(AudioBuffer buffer) {
		if (!isOpen()) {
			return;
		}
		// if the device is not started, start it
		if (!started || !sdl.isActive()) {
			sdl.start();
			started = true;
		}
		// set up the temporary buffer that receives the converted
		// samples in bytes
		int requiredSize = buffer.getByteArrayBufferSize(getFormat());
		if (byteBuffer == null || byteBuffer.length < requiredSize) {
			byteBuffer = new byte[requiredSize];
		}

		int length = buffer.convertToByteArray(byteBuffer, 0, getFormat());
		sdl.write(byteBuffer, 0, length);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.ibm.realtime.synth.engine.AudioClock#getClockTime()
	 */
	public AudioTime getAudioTime() {
		if (sdl != null) {
			return new AudioTime(sdl.getLongFramePosition()
					+ clockOffsetSamples, getSampleRate());
		} else {
			return new AudioTime(clockOffsetSamples, getSampleRate());
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.ibm.realtime.synth.engine.AdjustableAudioClock#getTimeOffset()
	 */
	public AudioTime getTimeOffset() {
		return new AudioTime(clockOffsetSamples, getSampleRate());
	}

	/**
	 * Set the clock offset. This offset is internally stored in samples, so you
	 * should only call it when this sink is open, or has already been open with
	 * the correct sample rate.
	 * 
	 * @see com.ibm.realtime.synth.engine.AdjustableAudioClock#setTimeOffset(com.ibm.realtime.synth.engine.AudioTime)
	 */
	public void setTimeOffset(AudioTime offset) {
		this.clockOffsetSamples = offset.getSamplesTime(getSampleRate());
	}

	/*
	 * (non-Javadoc) @return the buffer size of this sink in samples
	 * 
	 * @see com.ibm.realtime.synth.engine.AudioSink#getBufferSize()
	 */
	public int getBufferSize() {
		if (sdl != null) {
			return sdl.getBufferSize() / sdl.getFormat().getFrameSize();
		} else {
			return 1024; // something arbitrary
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.ibm.realtime.synth.engine.AudioSink#getChannels()
	 */
	public int getChannels() {
		if (sdl != null) {
			return sdl.getFormat().getChannels();
		} else {
			return 2; // something arbitrary
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.ibm.realtime.synth.engine.AudioSink#getSampleRate()
	 */
	public double getSampleRate() {
		return sampleRate;
	}

	/**
	 * @return the name of the open device, or a generic name if not open
	 */
	public String getName() {
		if (isOpen()) {
			return devName;
		}
		return "JavaSoundSink";
	}
}
