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
package com.ibm.realtime.synth.test;

import static com.ibm.realtime.synth.utils.Debug.*;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import com.ibm.realtime.synth.engine.*;
import com.ibm.realtime.synth.modules.*;
import com.ibm.realtime.synth.soundfont2.*;
import com.ibm.realtime.synth.utils.AudioUtils;
import com.ibm.realtime.synth.utils.MidiUtils;

/**
 * Load a wave file and use it as instrument.
 * 
 * @author florian
 */
public class Looper {

	public static final double DEFAULT_LATENCY = 50.0;
	public static final double DEFAULT_SLICE_TIME = 1.0;
	public static final int DEFAULT_EVENTRON_DIVISOR = 2;

	// fields needed in the timeOutThread
	private static boolean running = false;
	private static Object runLock = new Object();
	private static int timeOutSeconds = 0; // no timeOut
	private static Synthesizer synth = null;
	private static AudioSink sink = null;
	private static double latencyMillis = DEFAULT_LATENCY;
	private static SoundFontSoundbank soundbank = null;
	private static double volumeDB = 0;

	private static MaintenanceThread maintenance = null;

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {

		String soundFile = "test.wav";
		int audioDev = -2; // use default device
		double sliceTimeMillis = DEFAULT_SLICE_TIME;
		double sampleRate = 44100.0;
		int channels = 2;
		int bitsPerSample = 16;
		int threadCount = -1;
		int noteDispatcherMode = Synthesizer.NOTE_DISPATCHER_REQUEST_ASYNCHRONOUS;
		boolean preload = true;
		boolean interactive = true;
		boolean errorOccured = false;
		int midiDevCount = 0;

		// parse arguments
		int argi = 0;
		while (argi < args.length) {
			String arg = args[argi];
			if (arg.equals("-h")) {
				printUsageAndExit();
			} else if (arg.equals("-w")) {
				argi++;
				if (argi >= args.length) {
					printUsageAndExit();
				}
				soundFile = args[argi];
			} else {
				debug("Unknown parameter: " + arg);
				printUsageAndExit();
			}
			argi++;
		}

		// verify parameters

		File sbFile = new File(soundFile);
		if (sbFile.isDirectory() || !sbFile.exists()) {
			out("Invalid sound file: " + sbFile);
			out("Please make sure that the file exists, or specify an existing wave file with the -w command line parameter.");
			out("");
			printUsageAndExit();
		}
		// use Java Sound's default device if neither -a nor -da is specified
		if (audioDev == -2) {
			audioDev = -1;
		}
		AudioFormat format = new AudioFormat((float) sampleRate, bitsPerSample,
				channels, true, false);
		DiskWriterSink waveSink = null;
		AudioPullThread pullThread = null;
		JavaSoundMidiIn[] midis = null;

		// STARTUP ENGINE

		try {
			debug("Loading sound file " + sbFile + "...");
			AudioInputStream ais = AudioSystem.getAudioInputStream(sbFile);
			AudioFormat waveFormat = ais.getFormat();
			AudioSystem.getAudioInputStream(new AudioFormat(waveFormat.getSampleRate(), 16, 1, true, false), ais);
			waveFormat = ais.getFormat();
			RandomAccessFile ra = new RandomAccessFile(sbFile, "r");
			int size = (int) ra.length();
			ra.close();
			byte[] data = new byte[size];
			int length = 0;
			while (size > 0) {
				int thisRead = ais.read(data, length, size);
				if (thisRead < 0) break;
				if (thisRead == 0) Thread.yield();
				length += thisRead;
				size -= thisRead;
			}
			if (length == 0) {
				throw new Exception("Error: cannot read wave file");
			}
			SoundFontSampleData sampleData = new MySoundFontSampleData(data,
					length);
			SoundFontInfo info = new MySoundFontInfo(sbFile.getName());

			List<SoundFontGenerator> generators = new ArrayList<SoundFontGenerator>();
			generators.add(new SoundFontGenerator(SoundFontGenerator.SAMPLE_ID,
					(short) 0));
			//generators.add(new SoundFontGenerator(
			//		SoundFontGenerator.START_ADDRS_OFFSET, (short) 0));
			//generators.add(new SoundFontGenerator(
			//		SoundFontGenerator.START_ADDRS_COARSE_OFFSET, (short) 0));
			//generators.add(new SoundFontGenerator(
			//		SoundFontGenerator.END_ADDRS_OFFSET,
			//		(short) (sampleData.getSampleCount() & 0xFFFF)));
			// generators.add(new
			// SoundFontGenerator(SoundFontGenerator.END_ADDRS_COARSE_OFFSET,
			// (short)(sampleData.getSampleCount() >> 16)));
			//generators.add(new SoundFontGenerator(
			//		SoundFontGenerator.START_LOOP_ADDRS_OFFSET, (short) 0));
			//generators.add(new SoundFontGenerator(
			//		SoundFontGenerator.START_LOOP_ADDRS_COARSE_OFFSET,
			//		(short) 0));
			//generators.add(new SoundFontGenerator(
			//		SoundFontGenerator.END_LOOP_ADDRS_COARSE_OFFSET,
			//		(short) (sampleData.getSampleCount() >> 16)));
			//generators.add(new SoundFontGenerator(
			//		SoundFontGenerator.END_LOOP_ADDRS_OFFSET,
			//		(short) (sampleData.getSampleCount() & 0xFFFF)));

			generators.add(new SoundFontGenerator(SoundFontGenerator.KEY_RANGE,
					(short) (0xFF00)));
			generators.add(new SoundFontGenerator(SoundFontGenerator.VEL_RANGE,
					(short) (0xFF00)));
			generators.add(new SoundFontGenerator(SoundFontGenerator.SAMPLE_MODES, (short) 1));

			List<SoundFontModulator> modulators = new ArrayList<SoundFontModulator>();

			SoundFontSample sample = new SoundFontSample(sbFile.getName(), 0,
					sampleData.getSampleCount(), 0,
					sampleData.getSampleCount(), waveFormat.getSampleRate(),
					60, 0, 0, SoundFontSample.MONO_SAMPLE);

			SoundFontInstrumentZone instZone = new SoundFontInstrumentZone(
					generators.toArray(new SoundFontGenerator[generators.size()]),
					modulators.toArray(new SoundFontModulator[modulators.size()]),
					sample);
			SoundFontInstrumentZone[] instZones = new SoundFontInstrumentZone[1];
			instZones[0] = instZone;

			SoundFontInstrument instrument = new MySoundFontInstrument("Loop", instZones);
			
			generators.clear();
			generators.add(new SoundFontGenerator(SoundFontGenerator.KEY_RANGE,
					(short) (0xFF00)));
			generators.add(new SoundFontGenerator(SoundFontGenerator.VEL_RANGE,
					(short) (0xFF00)));
			modulators.clear();

			SoundFontPresetZone presetZone = new SoundFontPresetZone(
					generators.toArray(new SoundFontGenerator[generators.size()]),
					modulators.toArray(new SoundFontModulator[modulators.size()]),
					instrument);

			SoundFontPresetZone[] presetZones = new SoundFontPresetZone[1];
			presetZones[0] = presetZone;
			SoundFontPreset preset = new MySoundFontPreset("Loop", 0, 0,
					presetZones);

			SoundFontBank bank = new SoundFontBank(0);
			bank.setPreset(0, preset);

			List<SoundFontBank> banks = new ArrayList<SoundFontBank>(1);
			banks.add(bank);

			soundbank = new SoundFontSoundbank(sampleData, info, banks);

			// set up mixer
			debug("creating Mixer...");
			AudioMixer mixer = new AudioMixer();

			// slice time should always be <= latency
			if (sliceTimeMillis > latencyMillis) {
				sliceTimeMillis = latencyMillis;
			}
			// create the pull thread
			debug("creating AudioPullThread...");
			pullThread = new AudioPullThread();
			pullThread.setSliceTimeMillis(sliceTimeMillis);
			pullThread.setInput(mixer);

			// set up soundcard (sink)
			int bufferSizeSamples = pullThread.getPreferredSinkBufferSizeSamples(
					latencyMillis, format.getSampleRate());
			if (audioDev >= -1) {
				debugNoNewLine("creating JavaSoundSink...");
				JavaSoundSink jsSink = new JavaSoundSink();
				// open the sink
				jsSink.open(audioDev, format, bufferSizeSamples);
				debug(jsSink.getName());
				sink = jsSink;
				format = jsSink.getFormat();
			}
			debug("- audio format: " + format.getChannels() + " channels, "
					+ format.getSampleSizeInBits() + " bits, "
					+ format.getFrameSize() + " bytes per frame, "
					+ format1(format.getSampleRate()) + " Hz, "
					+ (format.isBigEndian() ? "big endian" : "little endian"));

			// use effective latency
			latencyMillis = AudioUtils.samples2nanos(sink.getBufferSize(),
					sampleRate) / 1000000.0;
			pullThread.setSink(sink);
			// set up Synthesizer
			debugNoNewLine("creating Synthesizer: ");
			synth = new Synthesizer(soundbank, mixer);

			synth.setFixedDelayNanos((long) (((2.0 * latencyMillis)) * 1000000.0));
			synth.setMasterClock(sink);
			synth.getParams().setMasterTuning(443);
			synth.setNoteDispatcherMode(noteDispatcherMode);
			synth.getChannel(0).setPitchWheelSensitivity(12);
			debugNoNewLine(format1(synth.getParams().getMasterTuning())
					+ "Hz tuning, ");
			synth.getParams().setMasterVolume(
					AudioUtils.decibel2linear(volumeDB));
			if (volumeDB != 0.0) {
				debugNoNewLine("volume: "
						+ format1(AudioUtils.linear2decibel(synth.getParams().getMasterVolume()))
						+ "dB (linear: "
						+ format3(synth.getParams().getMasterVolume()) + "), ");
			}
			pullThread.addListener(synth);
			if (threadCount >= 0) {
				synth.setRenderThreadCount(threadCount);
			}
			if (preload) {
				debugNoNewLine("preloading, ");
				synth.preLoad();
			}
			synth.start();
			if (synth.getRenderThreadCount() > 1) {
				debug("" + synth.getRenderThreadCount() + " render threads");
			} else {
				debug("rendering from mixer thread");
			}

			debugNoNewLine("MIDI ports:");

			// open all Java Sound MIDI ports
			int mdc = JavaSoundMidiIn.getMidiDeviceCount();
			midis = new JavaSoundMidiIn[mdc];
			for (int i = 0; i < mdc; i++) {
				debugNoNewLine(" Java Sound ");
				JavaSoundMidiIn midi = new JavaSoundMidiIn(i);
				midis[midiDevCount] = midi;
				midi.addListener(synth);
				try {
					midi.open(i);
					out(midi.getName());
					midiDevCount++;
				} catch (Exception e) {
					out("ERROR:");
					out(" no MIDI: " + e.toString());
				}
			}
			if (midiDevCount == 0) {
				debug("none");
			} else {
				// new line
				debug("");
			}

			// create the maintenance thread
			maintenance = new MaintenanceThread();

			maintenance.addServiceable(mixer);
			for (int i = 0; i < midiDevCount; i++) {
				maintenance.addAdjustableClock(midis[i]);
			}
			maintenance.setMasterClock(sink);

			// start the pull thread -- from now on, the mixer is polled
			// for new data
			pullThread.start();
			// start the maintenance thread
			maintenance.start();

			if (ThreadFactory.hasRealtimeThread()) {
				if (ThreadFactory.couldSetRealtimeThreadPriority()) {
					debug("Using realtime threads with high priority");
				} else {
					debug("Using realtime threads with default priority");
				}
			} else {
				debug("Realtime threads are not enabled.");
			}

			maintenance.synchronizeClocks(true);

			running = true;

			// if we're using a time out, start a thread to get stdin
			if (interactive && timeOutSeconds > 0) {
				Thread t = new Thread(new Runnable() {
					public void run() {
						interactiveKeyHandling();
					}
				});
				t.setDaemon(true);
				t.start();
			}

			if (timeOutSeconds > 0) {
				out("This program will exit after " + timeOutSeconds
						+ " seconds.");
			}
			if (interactive) {
				out("Press ENTER or SPACE+ENTER to play a random note");
				out("p+ENTER:Panic                    q+ENTER to quit");
			}

			if (timeOutSeconds == 0) {
				interactiveKeyHandling();
			} else {
				long startTime = System.nanoTime();
				while (running) {
					try {
						synchronized (runLock) {
							runLock.wait(1000);
						}
						if (timeOutSeconds > 0) {
							long elapsedSeconds = (System.nanoTime() - startTime) / 1000000000L;
							if (elapsedSeconds >= timeOutSeconds) {
								debug("Timeout reached. Stopping...");
								running = false;
								break;
							}
						}
					} catch (Exception e) {
						error(e);
					}
				}
			}
		} catch (Throwable t) {
			error(t);
			errorOccured = true;
		}

		// clean-up
		if (waveSink != null) {
			waveSink.close();
		}
		if (maintenance != null) {
			maintenance.stop();
		}
		if (pullThread != null) {
			pullThread.stop();
			out("Resynchronizations of audio device: "
					+ pullThread.getResynchCounter());
		}
		for (int i = 0; i < midiDevCount; i++) {
			if (midis[i] != null) {
				midis[i].close();
			}
		}
		if (synth != null) {
			synth.close();
		}
		if (sink != null) {
			sink.close();
			if (sink instanceof DirectAudioSink) {
				out("Underruns of the audio device     : "
						+ ((DirectAudioSink) sink).getUnderrunCount());
			}
		}

		// done
		out("Looper exit.");
		System.exit(errorOccured ? 1 : 0);
	}

	private static void interactiveKeyHandling() {
		int note = -1;
		int ignoreNext = 0;
		try {
			while (true) {
				int c = ((char) System.in.read());

				// out(""+c);
				if (ignoreNext > 0 && (c == 10 || c == 13)) {
					ignoreNext--;
					continue;
				}

				if (c != 10 && c != 13) {
					ignoreNext = 2;
				}

				// finish a previously played note
				if (note >= 0) {
					synth.midiInReceived(new MidiEvent(null,
							sink.getAudioTime(), 0, 0x90, note, 0));
					note = -1;
				}
				if (c == 'q') {
					out("stopping...");
					break;
				}
				if (c == 'p') {
					out("Reset.");
					synth.reset();
				}
				if (c == 32 || c == 10 || c == 13) {
					// generate a random Note On event on a white key
					do {
						note = ((int) (Math.random() * 40)) + 40;
					} while (!MidiUtils.isWhiteKey(note));
					int vel = ((int) (Math.random() * 40)) + 87;
					out("Playing: NoteOn " + note + "," + vel);
					synth.midiInReceived(new MidiEvent(null,
							sink.getAudioTime(), 0, 0x90, note, vel));
					if (c != 32) ignoreNext++;
				}
			}
		} catch (Exception e) {
			error(e);
		}
		running = false;
		synchronized (runLock) {
			runLock.notifyAll();
		}
	}

	private static void printUsageAndExit() {
		out("Usage:");
		out("java Looper [options]");
		out("Options:");
		out("-h              : display this help message");
		out("-w [.wav file]  : wave file to be used (test.wav by default)");
		System.exit(1);
	}

	static class MySoundFontSampleData extends SoundFontSampleData {
		private int size;

		public MySoundFontSampleData(byte[] _data, int size) {
			super();
			setData(_data);
			this.size = size;
		}

		/**
		 * @return the number of sample data points
		 */
		@Override
		public int getSampleCount() {
			// 16-bit samples
			return size / 2;
		}

	}

	static class MySoundFontInfo extends SoundFontInfo {
		public MySoundFontInfo(String name) {
			super();
			this.name = name;
		}
	}

	static class MySoundFontPreset extends SoundFontPreset {
		public MySoundFontPreset(String name, int program, int bank,
				SoundFontPresetZone[] zones) {
			super(name, program, bank);
			setZones(zones);
		}
	}
	
	static class MySoundFontInstrument extends SoundFontInstrument {
		public MySoundFontInstrument(String name, SoundFontInstrumentZone[] zones) {
			super(name);
			setZones(zones);
		}
	}
}
