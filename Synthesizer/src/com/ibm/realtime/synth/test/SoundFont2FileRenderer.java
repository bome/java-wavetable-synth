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

import java.io.*;

import javax.sound.sampled.AudioFormat;

import com.ibm.realtime.synth.engine.*;
import com.ibm.realtime.synth.modules.*;
import com.ibm.realtime.synth.soundfont2.*;

/**
 * Test program to render one note for 2 seconds to an output file.
 * 
 * @author florian
 */
public class SoundFont2FileRenderer {
	public static final String SF_DIR = "E:\\TestSounds\\sf2\\";

	public static final String SF_FILENAME = SF_DIR + "Creative\\ct8mgm.sf2";

	// public static final String filename = sfDir+"classictechno.sf2";
	// public static final String filename = sfDir+"bh_cello.sf2";

	public static final String DEFAULT_OUTPUT_FILENAME = "C:\\harmonicon_output.wav";

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {

		String soundbankFile = SF_FILENAME;
		int sliceTimeMillis = 1;
		double sampleRate = 44100.0;
		String midiFile = "";
		String outputFile = DEFAULT_OUTPUT_FILENAME;
		AudioFormat format = new AudioFormat((float) sampleRate, 16, 2, true,
				false);
		double timeOut = -1; // seconds 

		// parse arguments
		int argi = 0;
		while (argi < args.length) {
			String arg = args[argi];
			if (arg.equals("-h")) {
				printUsageAndExit();
			} else if (arg.equals("-s")) {
				argi++;
				if (argi >= args.length) {
					printUsageAndExit();
				}
				sliceTimeMillis = Integer.parseInt(args[argi]);
			} else if (arg.equals("-if")) {
				argi++;
				if (argi >= args.length) {
					printUsageAndExit();
				}
				midiFile = args[argi];
			} else if (arg.equals("-of")) {
				argi++;
				if (argi >= args.length) {
					printUsageAndExit();
				}
				outputFile = args[argi];
			} else if (arg.equals("-sb")) {
				argi++;
				if (argi >= args.length) {
					printUsageAndExit();
				}
				soundbankFile = args[argi];
			} else if (arg.equals("-duration")) {
				argi++;
				if (argi >= args.length) {
					printUsageAndExit();
				}
				timeOut = Double.parseDouble(args[argi]);
			} else {
				printUsageAndExit();
			}
			argi++;
		}

		File file = new File(soundbankFile);
		if (file.isDirectory() || !file.exists()) {
			out("Invalid soundfont file: " + file);
			out("Please specify a sample directory with the -sb command line parameter.");
			out("");
			printUsageAndExit();
		}
		File mFile = null;
		if (midiFile != "") {
			mFile = new File(midiFile);
			if (mFile.isDirectory() || !mFile.exists()) {
				out("Invalid MIDI input file: " + mFile);
				out("Please specify an existing MIDI file with the -if command line parameter.");
				out("");
				printUsageAndExit();
			}
		}
		File wavFile = null;
		if (outputFile.length() > 0) {
			wavFile = new File(outputFile);
			if (wavFile.isDirectory()) {
				out("Invalid output file: " + wavFile);
				out("");
				printUsageAndExit();
			}
		}

		debug("Loading soundbank " + file + "...");
		SoundFontSoundbank sb = new SoundFontSoundbank(file);

		// set up mixer
		debug("creating Mixer...");
		AudioMixer mixer = new AudioMixer();

		// set up disk writer sink
		debug("creating DiskWriterSink...");
		DiskWriterSink sink = new DiskWriterSink();
		// open the sink and connect it with the mixer
		sink.open(wavFile, format);
		try {

			// set up Synthesizer
			debug("creating Synthesizer...");
			Synthesizer synth = new Synthesizer(sb, mixer);
			synth.setFixedDelayNanos(0); // 2 * 20 * 1000000L);
			synth.start();

			// create the pull thread
			debug("creating AudioPullThread...");
			AudioPullThread pullThread = new AudioPullThread(mixer, sink);
			pullThread.setSliceTimeMillis(sliceTimeMillis);
			debug("connecting Synthesizer with AudioPullThread...");
			pullThread.addListener(synth);

			// create the maintenance thread
			debug("creating and connecting maintenance thread...");
			MaintenanceThread maintenance = new MaintenanceThread();
			maintenance.addServiceable(mixer);
			maintenance.setMasterClock(sink);

			try {
				// push the MIDI input file to the synthesizer (at once)
				if (mFile != null) {
					outNoNewLine("Writing MIDI File: " + mFile.getName());
					SMFPusher pusher = new SMFPusher();
					pusher.open(mFile);
					int events = pusher.pushToSynth(synth);
					out(", duration:" + format3(pusher.getDurationInSeconds())
							+ "s, " + events + " notes.");
					if (timeOut < 0) {
						timeOut = pusher.getDurationInSeconds();
					}
				} else {
					int program = 19;
					debug("program change to " + program + "...");
					synth.midiInReceived(new MidiEvent(null,
							sink.getAudioTime(), 0, 0xC0, program, 0));

					int note = 0x3C;
					int vel = 127;
					debug("playing note " + note + "...");
					synth.midiInReceived(new MidiEvent(null,
							sink.getAudioTime(), 0, 0x90, note, vel));
					if (timeOut < 0) {
						timeOut = 1.0;
					}
				}

				// start the pull thread -- from now on, the mixer is polled
				// for new data
				debug("starting AudioPullThread...");
				pullThread.start();

				debug("starting maintenance thread...");
				maintenance.start();
				debug("Rendering "+timeOut+" seconds...");
				while (sink.getAudioTime().getSecondsTime() < timeOut) {
					Thread.sleep(100);
					// debug(""+sink.getAudioTime().getMillisTime());
				}

			} finally {
				// clean-up
				if (maintenance != null) {
					maintenance.stop();
				}
				if (pullThread != null) {
					pullThread.stop();
				}
				if (synth != null) {
					synth.close();
				}
			}
		} finally {
			sink.close();
		}

		// done
		out("SoundFont2FileRenderer exit.");
	}

	private static void printUsageAndExit() {
		out("Usage:");
		out("java SoundFont2FileRenderer [options]");
		out("-if [.mid file] : MIDI file to be played");
		out("-of [.wav file] : write output to .wav file");
		out("-sb: specify the soundbank in .sf2 format to be used");
		out("-s: specify the quantum time in milliseconds");
		out("-duration <sec> : render only <sec> seconds");

		System.exit(1);
	}
}
