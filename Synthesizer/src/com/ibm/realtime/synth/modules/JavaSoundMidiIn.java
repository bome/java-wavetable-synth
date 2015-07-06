/*
 * (C) Copyright IBM Corp. 2005, 2008. All Rights Reserved
 */
package com.ibm.realtime.synth.modules;

import java.util.ArrayList;
import java.util.List;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Transmitter;

import static com.ibm.realtime.synth.utils.Debug.*;

import com.ibm.realtime.synth.engine.*;
import com.ibm.realtime.synth.utils.Debug;

/**
 * Class for handling incoming MIDI events from a Java Sound MIDI device. This
 * class can only open one MIDI IN device, because it provides a clock, and
 * multiple MIDI IN devices would cause clock confusion.
 * <p>
 * In order to open several MIDI IN ports, create multiple instances of this
 * class.
 * 
 * @author florian
 */
public class JavaSoundMidiIn implements MidiIn {

	public static boolean DEBUG_JSMIDIIN = false;

	/**
	 * The instance of the selected device
	 */
	private MidiDevice midiDev;

	/**
	 * The Transmitter retrieved from the MIDI device.
	 */
	private Transmitter midiInTransmitter;

	/**
	 * The Receiver to use to dispatch the messages received from the
	 * Transmitter
	 */
	private JavaSoundReceiver receiver;

	/**
	 * List of usable MIDI devices, i.e. they provide a MIDI IN port.
	 */
	private static List<DevInfo> devList;

	/**
	 * the offset of the clock in nanoseconds
	 */
	private long clockOffset = 0;

	/**
	 * The index of this instance -- an arbitrary value to be used by other
	 * classes
	 */
	private int instanceIndex;

	/**
	 * Create a Java Sound MidiIn instance.
	 */
	public JavaSoundMidiIn() {
		this(0);
	}

	/**
	 * Create a Java Sound MidiIn instance with the given device index.
	 */
	public JavaSoundMidiIn(int instanceIndex) {
		this.instanceIndex = instanceIndex;
		setupMidiDevices();
		receiver = new JavaSoundReceiver(this);
	}

	public void addListener(Listener L) {
		receiver.addListener(L);
	}

	public void removeListener(Listener L) {
		receiver.removeListener(L);
	}

	/** if false, all MIDI events will have time stamp 0 */
	public void setTimestamping(boolean value) {
		receiver.setRemoveTimestamps(!value);
	}

	/** @return the current status of time stamping MIDI events */
	public boolean isTimestamping() {
		return !receiver.isRemovingTimestamps();
	}

	public synchronized void open(int devIndex) throws Exception {
		if (devList.size() == 0) {
			throw new Exception("no MIDI IN devices available!");
		}
		if (devIndex < 0 || devIndex >= devList.size()) {
			throw new Exception("selected MIDI IN device ID out of range");
		}
		DevInfo info = devList.get(devIndex);
		if (midiDev != null) {
			midiDev.close();
			midiDev = null;
		}
		midiDev = MidiSystem.getMidiDevice(info.info);
		if (!midiDev.isOpen()) {
			midiDev.open();
		}
		midiInTransmitter = midiDev.getTransmitter();
		// connect the device with this instance as Receiver
		receiver.setID(devIndex);
		midiInTransmitter.setReceiver(receiver);
		if (DEBUG_JSMIDIIN) {
			debug("Opened MIDI IN device '" + info.info + "'");
		}
	}

	public synchronized void close() {
		if (midiInTransmitter != null) {
			midiInTransmitter.setReceiver(null);
		}
		if (midiDev != null) {
			if (DEBUG_MASTER_SWITCH) {
				DevInfo devInfo = null;
				for (DevInfo devInfo2 : devList) {
					if (devInfo2.info == midiDev.getDeviceInfo()) {
						devInfo = devInfo2;
						break;
					}
				}
				if (DEBUG_JSMIDIIN) {
					debug("Closing MIDI IN device '" + devInfo + "'");
				}
			}
			try {
				midiDev.close();
			} catch (Exception e) {
				debug(e);
			}
			midiDev = null;
		}
	}

	public synchronized boolean isOpen() {
		return (midiDev != null) && (midiDev.isOpen());
	}

	// interface AudioClock
	public synchronized AudioTime getAudioTime() {
		if (midiDev != null) {
			assert (midiDev.getMicrosecondPosition() != -1);
			return new AudioTime((midiDev.getMicrosecondPosition() * 1000L)
					+ clockOffset);
		}
		return new AudioTime(0);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.ibm.realtime.synth.engine.AdjustableAudioClock#getTimeOffset()
	 */
	public AudioTime getTimeOffset() {
		return new AudioTime(clockOffset);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.ibm.realtime.synth.engine.AdjustableAudioClock#setTimeOffset(com.ibm.realtime.synth.engine.AudioTime)
	 */
	public void setTimeOffset(AudioTime offset) {
		this.clockOffset = offset.getNanoTime();
	}

	public String toString() {
		if (isOpen()) {
			return "JSMidiIn " + midiDev.getDeviceInfo().getName();
		}
		return "JSMidiIn";
	}

	// static portion to maintain a list of MIDI devices

	public static List<DevInfo> getDeviceList() {
		setupMidiDevices();
		return devList;
	}

	private static void setupMidiDevices() {
		if (DEBUG_JSMIDIIN) debug("Gathering MIDI devices...");
		if (devList == null) {
			devList = new ArrayList<DevInfo>();
			MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
			// go through all MIDI devices and see if they are MIDI IN
			for (MidiDevice.Info info : infos) {
				try {
					MidiDevice dev = MidiSystem.getMidiDevice(info);
					if (!(dev instanceof Sequencer)
							&& !(dev instanceof Synthesizer)
							&& (dev.getMaxTransmitters() != 0)) {
						devList.add(new DevInfo(info));
					}
				} catch (MidiUnavailableException mue) {
					Debug.debug(mue);
				}
			}
		}
		if (DEBUG_JSMIDIIN) {
			debug("done (" + devList.size() + " devices available).");
		}
	}

	public static int getMidiDeviceCount() {
		setupMidiDevices();
		return devList.size();
	}

	/**
	 * @return the name of the open device, or a generic name if not open
	 */
	public String getName() {
		if (isOpen()) {
			return midiDev.getDeviceInfo().getName();
		}
		return "JavaSoundMidiIn";
	}

	public int getInstanceIndex() {
		return instanceIndex;
	}

	/**
	 * A wrapper for the Java Sound MidiDevice.Info object.
	 * 
	 * @author florian
	 */
	private static class DevInfo {
		MidiDevice.Info info;

		public DevInfo(MidiDevice.Info info) {
			this.info = info;
		}

		public String toString() {
			return info.toString();
		}
	}

}
