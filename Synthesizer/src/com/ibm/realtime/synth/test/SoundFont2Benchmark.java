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

import com.ibm.realtime.synth.modules.*;
import com.ibm.realtime.synth.engine.*;
import com.ibm.realtime.synth.utils.*;
import com.ibm.realtime.synth.soundfont2.*;
import static com.ibm.realtime.synth.utils.Debug.*;

import java.io.*;
import javax.sound.sampled.AudioFormat;

/**
 * An implementation that renders a MIDI file as fast as possible and measures
 * the execution.
 * 
 * @author florian
 */
public class SoundFont2Benchmark {

	public static final String SF_DIR = "E:\\TestSounds\\sf2\\";
	public static final String SF_FILENAME = SF_DIR + "Creative\\CT8MGM.SF2";

	private static int NUMBER_OF_RUNS = 5;

	protected static int latencyInMillis = 20;
	protected static int sliceTimeMillis = 1;
	protected static double sampleRate = 44100.0;
	// protected static double sampleRate = 192000.0;
	protected static AudioFormat format =
			new AudioFormat((float) sampleRate, 16, 2, true, false);

	/**
	 * Global variable for the NullSink
	 */
	protected static AudioPullThread pullThread;
	protected static Synthesizer synth = null;
	protected static boolean inProfiler = false;

	protected static SMFPusher pusher = null;
	protected static int events = 0;
	protected static boolean waitForProfilingStart = true;
	protected static boolean waitProfiling = false;
	protected static NullSink sink;
	protected static DiskWriterSink waveSink = null;
	protected static int asynchronousRenderThreads = 0;

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		String soundbankFile = SF_FILENAME;
		String outputFile = "";
		String inputFile = "disco.mid";
		boolean polyphonyTest = false;

		Debug.DEBUG_MASTER_SWITCH = false;

		// parse arguments
		int argi = 0;
		while (argi < args.length) {
			String arg = args[argi];
			if (arg.equals("-h")) {
				printUsageAndExit();
			} else if (arg.equals("-l")) {
				argi++;
				if (argi >= args.length) {
					printUsageAndExit();
				}
				latencyInMillis = Integer.parseInt(args[argi]);
			} else if (arg.equals("-s")) {
				argi++;
				if (argi >= args.length) {
					printUsageAndExit();
				}
				sliceTimeMillis = Integer.parseInt(args[argi]);
			} else if (arg.equals("-sb")) {
				argi++;
				if (argi >= args.length) {
					printUsageAndExit();
				}
				soundbankFile = args[argi];
			} else if (arg.equals("-if")) {
				argi++;
				if (argi >= args.length) {
					printUsageAndExit();
				}
				inputFile = args[argi];
			} else if (arg.equals("-of")) {
				argi++;
				if (argi >= args.length) {
					printUsageAndExit();
				}
				outputFile = args[argi];
			} else if (arg.equals("-profile")) {
				inProfiler = true;
			} else if (arg.equals("-waitProfiling")) {
				waitProfiling = true;
			} else if (arg.equals("-debug")) {
				Debug.DEBUG_MASTER_SWITCH = true;
			} else if (arg.equals("-p")) {
				argi++;
				if (argi >= args.length) {
					printUsageAndExit();
				}
				asynchronousRenderThreads = Integer.parseInt(args[argi]);
			} else if (arg.equals("-polyphony")) {
				polyphonyTest = true;
			} else {
				printUsageAndExit();
			}
			argi++;
		}
		if (inProfiler && waitProfiling) {
			System.out.print("20 seconds...");
			System.out.flush();
			Thread.sleep(20000);
			System.out.println("done");
		}

		// verify parameters

		File sbFile = new File(soundbankFile);
		if (sbFile.isDirectory() || !sbFile.exists()) {
			out("Invalid soundfont file: " + sbFile);
			out("Please specify an existing soundbank file with the -sb command line parameter.");
			out("");
			printUsageAndExit();
		}
		File wavFile = null;
		if (!polyphonyTest && outputFile.length() > 0) {
			wavFile = new File(outputFile);
			if (wavFile.isDirectory()) {
				out("Invalid output file: " + wavFile);
				out("");
				printUsageAndExit();
			}
		}
		File midiFile = null;
		if (!polyphonyTest) {
			midiFile = new File(inputFile);
			if (midiFile.isDirectory() || !midiFile.exists()) {
				out("Invalid MIDI file: " + midiFile);
				out("Please specify an existing MIDI file with the -if command line parameter.");
				out("");
				printUsageAndExit();
			}
		}

		if (inProfiler) {
			NUMBER_OF_RUNS = 2;
		}

		out("Configuration");
		out("-------------");
		out("OS:          " + System.getProperty("os.name") + " "
				+ System.getProperty("os.arch") + " "
				+ System.getProperty("os.version"));
		out("JRE:         " + System.getProperty("java.vendor") + " "
				+ System.getProperty("java.name") + " "
				+ System.getProperty("java.version"));
		out("VM:          " + System.getProperty("java.vm.vendor") + " "
				+ System.getProperty("java.vm.name") + " "
				+ System.getProperty("java.vm.version"));
		out("Compiler:    " + System.getProperty("java.compiler"));
		out("Soundbank:   " + sbFile.getName());
		String addRT = "";
		if (asynchronousRenderThreads > 0) {
			addRT =
					" using " + asynchronousRenderThreads
							+ " asynchronous render threads.";
		}
		out("Render spec: Latency=" + latencyInMillis + "ms, slice time="
				+ sliceTimeMillis + "ms" + addRT);
		out("Format:      " + format);
		if (inProfiler) {
			out("Profiling:   " + inProfiler);
		}

		if (midiFile != null) {
			pusher = new SMFPusher();
			pusher.open(midiFile);
			events = pusher.pushToSynth(null);
			out("MIDI File  : " + midiFile.getName() + ", duration:"
					+ format3(pusher.getDurationInSeconds()) + "s, " + events
					+ " notes.");
		}
		if (wavFile != null) {
			out("Output File: " + wavFile.getName());
		}

		try {
			SoundFontSoundbank sb = new SoundFontSoundbank(sbFile);
			sink = new NullSink();
			synth = new Synthesizer(sb);
			synth.setFixedDelayNanos(2 * latencyInMillis * 1000000L);
			synth.setRenderThreadCount(asynchronousRenderThreads);
			synth.start();
			pullThread = new AudioPullThread(synth.getMixer(), sink);
			pullThread.setSliceTimeMillis(sliceTimeMillis);
			pullThread.addListener(synth);
			if (wavFile != null) {
				waveSink = new DiskWriterSink();
				waveSink.open(wavFile, format);
				pullThread.setSlaveSink(waveSink);
			}
			Thread.currentThread().setPriority(
					AudioPullThread.PULLTHREAD_PRIORITY);

			if (polyphonyTest) {
				doPolyphonyTest();
			} else {
				doMidiFileRenderTest();
			}
		} finally {
			// clean-up
			if (waveSink != null) {
				waveSink.close();
			}
			if (synth != null) {
				synth.close();
			}
		}
		out("------------------------------------------------------------------");
	}

	private static void doMidiFileRenderTest() throws Exception {
		out("");
		out("Results");
		out("-------");
		sink.setStopTime(pusher.getDurationInSeconds());
		if (inProfiler) {
			sink.setStopTime(10.0);
		}
		for (int run = 0; run < NUMBER_OF_RUNS; run++) {
			synth.hardReset();
			sink.reset();
			synth.getMixer().cleanUp();
			if (run != 1 || inProfiler) {
				pusher.pushToSynth(synth);
			}
			System.gc();
			if (inProfiler && waitForProfilingStart && run == 1) {
				System.out.print("Start profiler now (15 seconds)...");
				System.out.flush();
				Thread.sleep(15000);
				System.out.println("done");
			}
			Thread.sleep(100);
			long startTime = System.nanoTime();
			pullThread.run();
			double durationMillis =
					((double) (System.nanoTime() - startTime)) / 1000000.0;
			double writtenSeconds = sink.getWrittenTime().getSecondsTime();
			String add = "";
			String msPerNote;
			if (waveSink != null) {
				add = " [wrote to wave file]";
			}
			if (run == 1 && !inProfiler) {
				add = " [empty run]";
				msPerNote = "";
			} else {
				msPerNote =
						format5(durationMillis / ((double) events))
								+ "ms/note, ";
			}
			out("Result Run" + run + ": " + format3(durationMillis) + "ms, "
					+ msPerNote + "rendered " + format3(writtenSeconds) + "s, "
					+ format3(writtenSeconds / (durationMillis / 1000.0))
					+ "x realtime." + add);
			if (waveSink != null) {
				waveSink.close();
				waveSink = null;
				pullThread.setSlaveSink(null);
			}
			if (inProfiler) {
				if (waitProfiling) {
					System.out.print("20 seconds...");
					System.out.flush();
					Thread.sleep(20000);
					System.out.println("done");
				}
			}
		}
	}

	private final static int[] DELTA_TRIALS = {
			500, 100, 50, 20, 5, 1
	};

	private final static int MAX_RENDERED_NOTES_PER_CHANNEL = 90;

	/**
	 * Renders one second audio data with the specified number of voices.
	 * 
	 * @param polyphony the number of voices to render
	 * @return the processor usage percentage
	 */
	private static double renderPolyphony(int polyphony) throws Exception {
		if (polyphony > 16 * MAX_RENDERED_NOTES_PER_CHANNEL) {
			out("## Cannot output more than "
					+ (16 * MAX_RENDERED_NOTES_PER_CHANNEL)
					+ " notes at once (16 channels * "
					+ MAX_RENDERED_NOTES_PER_CHANNEL + " notes)");
			out("Benchmark prematurely quit.");
			System.exit(1);
		}
		debugNoNewLine("Trying " + polyphony + " voices...");
		synth.hardReset();
		sink.reset();
		synth.getMixer().cleanUp();

		// 1. submit all <polyphony> instruments to Synthesizer

		// disable realtime scheduling
		synth.setMasterClock(null);
		synth.setFixedDelayNanos(0);
		// disable concurrent rendering (if enabled at all)
		synth.setRenderThreadCount(0);

		// first set the instrument. It needs to fulfill these properties:
		// - at least one LFO usage (at best pitch LFO)
		// - volume envelope
		// - filter enabled
		// - does not stop on its own
		// - at best, occupies only one voice (i.e. no multi-layers, no stereo
		// instruments)
		int program = 42; // cello
		AudioTime time = new AudioTime(0);
		for (int ch = 0; ch < 16; ch++) {
			// set to bank 0
			synth.midiInReceived(new MidiEvent(null, time, ch, 0xB0, 0, 0));
			synth.midiInReceived(new MidiEvent(null, time, ch, 0xB0, 32, 0));
			synth.midiInReceived(new MidiEvent(null, time, ch, 0xC0, program, 0));
		}
		// now add the Note On commands
		for (int i = 0; i < polyphony; i++) {
			int note = 15 + (i % MAX_RENDERED_NOTES_PER_CHANNEL);
			int channel = i / MAX_RENDERED_NOTES_PER_CHANNEL;
			int vel = 120;
			synth.midiInReceived(new MidiEvent(null, time, channel, 0x90, note,
					vel));
		}
		// submit all notes
		synth.newAudioSlice(time, time);
		// verify that we have exactly <polyphony> voice
		if (synth.getMixer().getCount() > polyphony) {
			debug("");
			debug("Mixer has more than " + polyphony
					+ " streams! Removing excess streams.");
			synth.getMixer().removeLast(synth.getMixer().getCount() - polyphony);
		}
		if (synth.getMixer().getCount() != polyphony) {
			out("");
			out("## Mixer: has " + synth.getMixer().getCount()
					+ " streams instead of " + polyphony + "!");
			out("Benchmark prematurely quit.");
			System.exit(1);
		}
		// enable concurrent rendering (if enabled at all)
		synth.setRenderThreadCount(asynchronousRenderThreads);
		System.gc();
		// allow threads to be created, etc.
		Thread.sleep(200);

		// 2. render 1 second worth of data
		sink.setStopTime(1.0);
		long startTime = System.nanoTime();
		pullThread.run();
		double durationMillis =
				((double) (System.nanoTime() - startTime)) / 1000000.0;
		double writtenMillis = sink.getWrittenTime().getSecondsTime() * 1000.0;
		double percentage = durationMillis / writtenMillis;
		debug("rendering " + format3(writtenMillis) + "ms took "
				+ format3(durationMillis) + "ms -> "
				+ format1(percentage * 100.0) + "% processor usage.");
		return percentage;
	}

	private static void doPolyphonyTest() throws Exception {

		int index = 1;
		int currPolyphony = DELTA_TRIALS[0];

		boolean initiallyTooHigh = false;
		boolean firstRunAtThisPolyphony = true;
		double lastPercentage = 1.0;
		int lastPolyphony = currPolyphony;
		double percentage = 1.0;
		while (index < DELTA_TRIALS.length) {

			percentage = renderPolyphony(currPolyphony);
			boolean currTooHigh = percentage > 1.0;
			if (firstRunAtThisPolyphony) {
				initiallyTooHigh = currTooHigh;
				firstRunAtThisPolyphony = false;
			} else if (initiallyTooHigh != currTooHigh) {
				firstRunAtThisPolyphony = true;
				// increase the granularity
				index++;
				if (index >= DELTA_TRIALS.length) {
					break;
				}
			}
			lastPercentage = percentage;
			lastPolyphony = currPolyphony;
			// now adjust the next round's polyphony
			if (currTooHigh) {
				currPolyphony -= DELTA_TRIALS[index];
			} else {
				currPolyphony += DELTA_TRIALS[index];
			}
		}
		double effectivePolyphony;
		if (lastPolyphony > currPolyphony) {
			effectivePolyphony =
					(double) currPolyphony
							+ ((1.0 - percentage) / (lastPercentage - percentage));
		} else {
			effectivePolyphony =
					(double) lastPolyphony
							+ ((1.0 - lastPercentage) / (percentage - lastPercentage));
		}
		out("Result: " + format3(effectivePolyphony)
				+ " voices simultaneously.");
	}

	private static void printUsageAndExit() {
		out("SoundFont2Benchmark: Render a MIDI file and measure the execution time.");
		out("Usage:");
		out("java SoundFont2Benchmark [-if <MIDI file>] [-s <slice time>]");
		out("                     [-sb <soundbank>] [-of output_wave] [-profile] ");
		out("                     [-polyphony] [-debug] [-p N][-h]");
		out("-if: specify the MIDI file to be rendered");
		out("-s: specify the quantum time in milliseconds (quantum time < buffer size)");
		out("-sb: specify the soundbank in .sf2 format to be used");
		out("-of: specify a path to a .wav file that will be (over)written ");
		out("     with the rendered audio output.");
		out("-profile: for use with a profiler: waits twice for a keypress");
		out("-p N: use asynchronous rendering using N threads (default: not asynchronous)");
		out("-polyphony: iteratively find out the maximum polypony of one instrument.");
		out("            -if and -of are ignored.");
		out("");
		out("Results can only be compared if there is no DEBUG output!");
		out("This is a single-thread benchmark with the goal of improving");
		out("the efficiency of the rendering engine.");
		System.exit(1);
	}

	private static class NullSink implements AudioSink {

		private long writtenSamples = 0;
		private long clockOffsetSamples = 0;
		private long stopTimeSamples = -1;
		private static int serviceIntervalMillis = 100;
		private long serviceIntervalSamples = 0;
		private long nextServiceSamples;

		public void reset() {
			writtenSamples = 0;
			clockOffsetSamples = 0;
			serviceIntervalSamples =
					AudioUtils.millis2samples(serviceIntervalMillis,
							getSampleRate());
			nextServiceSamples = serviceIntervalSamples;
		}

		public void setStopTime(double timeInSeconds) {
			stopTimeSamples =
					AudioUtils.seconds2samples(timeInSeconds, getSampleRate());
		}

		public int getBufferSize() {
			return (int) AudioUtils.millis2samples(latencyInMillis,
					getSampleRate());
		}

		public int getChannels() {
			return format.getChannels();
		}

		public double getSampleRate() {
			return sampleRate;
		}

		public boolean isOpen() {
			return true;
		}

		public void close() {
			// nothing to do
		}
		
		public void write(AudioBuffer buffer) {
			writtenSamples += buffer.getSampleCount();
			// if we passed stopTime, stop the PullThread
			if (writtenSamples > stopTimeSamples) {
				// be careful with synchronization!
				pullThread.stop();
			} else if (writtenSamples > nextServiceSamples) {
				synth.getMixer().service();
				while (writtenSamples > nextServiceSamples) {
					nextServiceSamples += serviceIntervalSamples;
				}
			}
			if (inProfiler) {
				try {
					// don't block the profiler!
					Thread.sleep(10);
				} catch (InterruptedException ie) {
				}
			}
		}

		public AudioTime getWrittenTime() {
			return new AudioTime(writtenSamples, getSampleRate());
		}

		public AudioTime getAudioTime() {
			return new AudioTime(writtenSamples + clockOffsetSamples,
					getSampleRate());
		}

		public AudioTime getTimeOffset() {
			return new AudioTime(clockOffsetSamples, getSampleRate());
		}

		public void setTimeOffset(AudioTime offset) {
			this.clockOffsetSamples = offset.getSamplesTime(getSampleRate());
		}
	}
}
