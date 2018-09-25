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

import java.io.IOException;
import java.util.List;

import com.ibm.realtime.synth.engine.*;
import com.ibm.realtime.synth.modules.*;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Sequencer;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.SysexMessage;
import javax.sound.midi.Receiver;

/**
 * Equivalent to midiloop.c: echo everything from MIDI IN to MIDI OUT.
 * 
 * @author florian
 */
public class MidiLoop {

	private static boolean verbose = false;
	private static MidiIn mi = null;

	/** Java Sound MIDI OUT device */
	private static MidiDevice mo = null;

	/** Java Sound MIDI OUT receiver */
	private static Receiver receiver = null;
	
	private static int received = 0;
	
	private static void close() {
		if (mi != null && mi.isOpen()) {
			if (verbose) {
				System.out.println("Closing MIDI IN");
			}
			mi.close();
		}
		if (mo != null && mo.isOpen()) {
			if (verbose) {
				System.out.println("Closing MIDI OUT");
			}
			mo.close();
		}
	}

	@SuppressWarnings("unchecked")
	public static void main(String[] args) {

		boolean js = false;

		int currArg = 0;
		if (args.length < 2) {
			System.out.println("Too few parameters");
			printUsageAndExit();
		}
		for (int i = 0; i < args.length; i++) {
			String arg = args[currArg];
			if (arg.equals("-h") || arg.equals("--help")) {
				printUsageAndExit();
			} else if (arg.equals("-v")) {
				verbose = true;
			} else if (arg.equals("-js")) {
				js = true;
			} else if (args.length - currArg != 2) {
				System.out.println("Unknown parameter: " + arg);
				printUsageAndExit();
			} else
				break;
			currArg++;
		}
		String inDev = args[args.length - 2];
		String outDev = args[args.length - 1];
		if (verbose) {
			System.out.print("Opening MIDI IN  '" + inDev + "'...");
			System.out.flush();
		}
		try {
			if (js) {
				// find DirectMidiIn device by name
				List devs = JavaSoundMidiIn.getDeviceList();
				for (int i = 0; i < devs.size(); i++) {
					if (devs.get(i).toString().indexOf(inDev) >= 0) {
						// found the device. Open it!
						mi = new JavaSoundMidiIn(0);
						((JavaSoundMidiIn) mi).open(i);
					}
				}
				if (mi == null) {
					throw new Exception("cannot find Java Sound MIDI IN device '"
							+ inDev + "'");
				}
			} else {
				mi = new DirectMidiIn(0);
				((DirectMidiIn) mi).open(inDev);
			}
			mi.addListener(new MidiIn.Listener() {
				public void midiInReceived(MidiEvent me) {
					if (verbose) {
						System.out.println("IN " + me.getSource() + ": "
								+ me.getTime() + ": " + me.getStatus() + " "
								+ me.getData1() + " " + me.getData2());
					}
					MidiMessage mm = null;
					try {
						if (me.isLong()) {
							SysexMessage sm = new SysexMessage();
							byte[] longData = me.getLongData();
							sm.setMessage(longData, longData.length);
							mm = sm;
						} else if (me.isRealtimeEvent()) { 
							ShortMessage sm = new ShortMessage();
							sm.setMessage(me.getStatus(), me.getData1(), me.getData2());
							mm = sm;
						} else {
							ShortMessage sm = new ShortMessage();
							sm.setMessage(me.getStatus(), me.getChannel(),
									me.getData1(), me.getData2());
							mm = sm;
						}
						received += mm.getLength();
						if (receiver != null) {
							receiver.send(mm, -1);
						}
					} catch (InvalidMidiDataException imde) {
						if (verbose) {
							System.out.println(imde.toString());
						}
					}
				}
			});
			if (verbose) {
				System.out.println("OK");
			}

			if (verbose) {
				System.out.print("Opening MIDI OUT '" + outDev + "'...");
				System.out.flush();
			}
			MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
			for (MidiDevice.Info info : infos) {
				try {
					if (info.getName().indexOf(outDev) >= 0) {
						// found the device. if a regular MIDI porrt, open it
						MidiDevice dev = MidiSystem.getMidiDevice(info);
						if (!(dev instanceof Sequencer)
								&& !(dev instanceof Synthesizer)
								&& (dev.getMaxReceivers() != 0)) {
							dev.open();
							mo = dev;
							receiver = mo.getReceiver();
							break;
						}
					}
				} catch (MidiUnavailableException mue) {
					// ignore
				}
			}
			if (mo == null || receiver == null) {
				throw new Exception("cannot find Java Sound MIDI OUT device '"
						+ outDev + "'");
			}
			if (verbose) {
				System.out.println("OK");
			}

			System.out.println("Press ENTER to quit");
			try {
				System.in.read();
			} catch (IOException ioe) {
				ioe.printStackTrace();
			}
		} catch (Throwable t) {
			System.out.println();
			System.out.println("Caught exception: " + t);
		}
		close();
		System.out.println("Received "+received+" bytes.");
	}

	private static void printUsageAndExit() {
		System.out.println("Usage: java " + MidiLoop.class.getName()
				+ " [options] MIDI_IN MIDI_OUT");
		System.out.println("options:");
		System.out.println("  -js   Use Java Sound (otherwise will use DirectMidi)");
		System.out.println("  -v    verbose");
		System.out.println("MIDI devices:");
		List<DirectMidiInDeviceEntry> list = DirectMidiIn.getDeviceEntryList();
		if (list.size() == 0) {
			System.out.println("  [none]");
		} else {
			for (int i = 0; i < list.size(); i++) {
				System.out.println("  " + list.get(i).getFullInfoString());
			}
		}
		System.exit(1);
	}
}
