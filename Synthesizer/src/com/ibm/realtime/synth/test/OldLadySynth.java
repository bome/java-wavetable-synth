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

import static com.ibm.realtime.synth.utils.Debug.*;

import java.util.*;
import java.io.*;

import javax.sound.sampled.AudioFormat;

/*
 * TODO: 
 * - at the end of playing a note, a beep
 */

/**
 * A synth implementation that uses the old lady samples.
 * 
 * @author florian
 * 
 */
public class OldLadySynth {

	private final static String[] NOTE_NAMES = {
			"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
	};

	private final static String directory1 = "G:\\LargeProgs\\OldLadySoundbank";
	private final static String directory2 = "/samples/OldLadySoundbank";

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {

		String sampleDir = "";
		int midiDev = 0;
		int audioDev = -1;
		int latencyInMillis = 50;
		int sliceTimeMillis = 2;
		int sampleLength = 3000;

		// parse arguments
		int argi = 0;
		while (argi < args.length) {
			String arg = args[argi];
			if (arg.equals("-h")) {
				printUsageAndExit();
			} else if (arg.equals("-m")) {
				argi++;
				if (argi >= args.length) {
					printUsageAndExit();
				}
				midiDev = Integer.parseInt(args[argi]);
			} else if (arg.equals("-sl")) {
				argi++;
				if (argi >= args.length) {
					printUsageAndExit();
				}
				sampleLength = Integer.parseInt(args[argi]);
			} else if (arg.equals("-a")) {
				argi++;
				if (argi >= args.length) {
					printUsageAndExit();
				}
				audioDev = Integer.parseInt(args[argi]);
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
			} else if (arg.equals("-d")) {
				argi++;
				if (argi >= args.length) {
					printUsageAndExit();
				}
				sampleDir = args[argi];
			} else {
				printUsageAndExit();
			}
			argi++;
		}

		File dir;
		if (sampleDir.length()>0) {
			dir = new File(sampleDir);
			if (!dir.isDirectory() || !dir.exists()) {
				out("Invalid sample directory: " + sampleDir);
				printUsageAndExit();
			}
		}
		dir = new File(directory1);
		if (!dir.isDirectory() || !dir.exists()) {
			dir = new File(directory2);
			if (!dir.isDirectory() || !dir.exists()) {
				out("Cannot read samples from " + directory1+" or "+directory2+"!");
				out("Please specify a sample directory with the -d command line parameter.");
				out("");
				printUsageAndExit();
			}
		}
		

		debug("Loading samples (max length="+sampleLength+" seconds):");
		StaticPianoSoundbank.MAX_WAVE_LENGTH = sampleLength*1000;
		StaticPianoSoundbank sb = new StaticPianoSoundbank(dir,
				new StaticPianoSoundbank.FilenameScheme() {
					public String getFilename(int note, int vel) {
						String prepend="127";
						if (MidiUtils.isBlackKey(note)) {
							prepend += "s";
							note--;
						}
						return prepend+" lady " + NOTE_NAMES[note % 12]
						                                + ((note / 12) - 1) + ".wav";
					}

				});

		// double sampleRate = sb.getPreferredAudioFormat().getSampleRate();
		double sampleRate = 44100.0;
		AudioFormat format = new AudioFormat((float) sampleRate, 16, 2, true,
				false);

		// set up mixer
		debug("creating Mixer...");
		AudioMixer mixer = new AudioMixer();

		// set up soundcard (sink)
		debug("creating JavaSoundSink...");
		JavaSoundSink sink = new JavaSoundSink();
		// open the sink and connect it with the mixer
		sink.open(audioDev, latencyInMillis, format);
		try {

			// set up Synthesizer
			debug("creating Synthesizer...");
			Synthesizer synth = new Synthesizer(sb, mixer);
			synth.setFixedDelayNanos(2 * latencyInMillis * 1000000L);
			synth.setMasterClock(sink);
			synth.start();

			// create the pull thread
			debug("creating AudioPullThread...");
			AudioPullThread pullThread = new AudioPullThread(mixer, sink);
			pullThread.setSliceTimeMillis(sliceTimeMillis);
			debug("connecting Synthesizer with AudioPullThread...");
			pullThread.addListener(synth);

			// open a MIDI port?
			JavaSoundMidiIn midi = null;
			if (midiDev < JavaSoundMidiIn.getMidiDeviceCount()) {
				debug("creating JavaSoundMidiIn...");
				midi = new JavaSoundMidiIn();
				debug("connecting MidiIn to Synthesizer...");
				midi.addListener(synth);
				try {
					midi.open(midiDev);
				} catch (Exception e) {
					out("no MIDI: " + e.toString());
				}
			} else {
				out("no MIDI available.");
			}

			// create the maintenance thread
			debug("creating and connecting maintenance thread...");
			MaintenanceThread maintenance = new MaintenanceThread();
			maintenance.addServiceable(mixer);
			maintenance.addAdjustableClock(midi);
			maintenance.setMasterClock(sink);

			try {
				// start the pull thread -- from now on, the mixer is polled
				// for new data
				debug("starting AudioPullThread...");
				pullThread.start();

				debug("starting maintenance thread...");
				maintenance.start();

				out("Press ENTER to play a random note, 'q'+ENTER to quit.");
				int note = -1;
				while (true) {
					char c = (char) System.in.read();
					// finish a previously played note
					if (c == 'q') {
						if (note >= 0) {
							synth.midiInReceived(new MidiEvent(null,
									sink.getAudioTime(), 0, 0x90, note, 0));
							note = -1;
						}
						
						break;
					}
					if ((((int) c) == 13) || (((int) c) == 10)) {
						if (note >= 0) {
							synth.midiInReceived(new MidiEvent(null,
									sink.getAudioTime(), 0, 0x90, note, 0));
							note = -1;
						}
						// generate a random Note On event on a white key
						do {
							note = ((int) (Math.random() * 60)) + 40;
						} while (!MidiUtils.isWhiteKey(note));
						int vel = ((int) (Math.random() * 20)) + 100;
						synth.midiInReceived(new MidiEvent(null,
								sink.getAudioTime(), 0, 0x90, note, vel));
					}
				}
			} finally {
				// clean-up
				if (maintenance != null) {
					maintenance.stop();
				}
				if (pullThread != null) {
					pullThread.stop();
				}
				if (midi != null) {
					midi.close();
				}
				if (synth != null) {
					synth.close();
				}
			}
		} finally {
			sink.close();
		}

		// done
		out("OldLadySynth exit.");
	}

	@SuppressWarnings("unchecked")
	private static void printUsageAndExit() {
		List infos = JavaSoundMidiIn.getDeviceList();
		List ainfos = JavaSoundSink.getDeviceList();
		out("Usage:");
		out("java OldLadySynth [-m <MIDI dev>] [-a <audio dev>] [-l <latency>] [-s <slice time>] [-d <sample dir> [-h]");
		out("-d: specify the sample directory ");
		out("-sl: specify the maximum length per sample (to save memory) in seconds ");
		out("-l: specify the buffer size in milliseconds");
		out("-s: specify the quantum time in milliseconds (quantum time < buffer size)");
		out("-m: allow MIDI input. <MIDI dev> is a number for the MIDI "
				+ "device from the following list:");
		if (infos.size() > 0) {
			for (int i = 0; i < infos.size(); i++) {
				out("   " + i + ": " + infos.get(i));
			}
		} else {
			out("    (no MIDI IN devices available)");
		}

		out("-a: specify the audio output device (otherwise the default device will be used)");
		out("    <audio dev> is a number for the audio "
				+ "device from the following list:");
		if (ainfos.size() > 0) {
			for (int i = 0; i < ainfos.size(); i++) {
				out("   " + i + ": " + ainfos.get(i));
			}
		} else {
			out("    (no audio output devices available)");
		}

		System.exit(1);
	}
}
