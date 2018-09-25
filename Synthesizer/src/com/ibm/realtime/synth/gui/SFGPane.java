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
package com.ibm.realtime.synth.gui;

import java.util.*;
import java.io.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Mixer;
import javax.swing.*;
import javax.swing.event.*;

import com.ibm.realtime.synth.engine.*;
import com.ibm.realtime.synth.modules.*;
import com.ibm.realtime.synth.soundfont2.SoundFontSoundbank;
import com.ibm.realtime.synth.utils.*;

import static com.ibm.realtime.synth.engine.MidiChannel.*;
import static com.ibm.realtime.synth.utils.Debug.*;

/*
 * OLD SWING GUI for Harmonicon.
 * NOTE: this file is not maintained anymore!
 */

/**
 *
 * The panel accomodating the GUI elements.
 */

/*
 * TODO General:
 * - technical documentation document
 * - measure exact latency compared to a hardware synthesizer
 * - possibly help with eventron support
 * - integration of Tuning Fork audio effects
 * - help with MIDI-fication of Tuning Fork (for control surface support),
 *   if still necessary
 * - implement the remaining 10% of the soundfont standard
 * - implement some more General MIDI features (chorus+reverb effects,
 *   more real time sound control by way of controllers)
 * - multi-channel/surround
 * - Direct MIDI implementation for Linux with ALSA (to further
 *   reduce latency)
 *
 * TODO for the GUI:
 * <ul>
 * <li>put all DEBUG_* flag handling in a utility class so that it can be used 
 *     from the command line synth
 * <li>Master Volume slider/Mute
 * <li>MIDI playback: tempo and position slider, bars|beats display, fwd/rew buttons
 * <li>activity LED for MIDI file playback
 * <li>time display for audio device/MIDI device
 * <li>Reset controllers button (also for console synth)
 * <li>Synth status as text area
 * <li>smart loading of startup soundbank
 * <li>parameters for main() -- with a SynthTestCommon class or so in the test
 * package
 * <li>mouse keyboard?
 * <li>performance indicator, CPU/memory usage, polyphony
 * </ul>
 *
 * DONE:
 * <li>Load a MIDI file and play
 * <li>persistence of GUI elements, including debug switches so that they are
 * set before starting the engine.
 * <li>Recording to Wave file
 * <li>master tuning
 * <li>when finished playback of MIDI file, pressing play will cause 3-5
 * seconds of silence before playing anything (caused by wrong synchronization 
 * of SMFMidiIn).
 * <li>Panic button
 * <li>Multiple MIDI IN support
 * <li>device settings, like latency
 * <li>fix the deadlock bug -- seems to be a problem in the MIDI subsystem
 * <li>replace the low pass filter
 * <li>allow sub-millisecond latency
 * 
 * @author florian
 */
public class SFGPane extends JPanel implements ItemListener, ActionListener,
		ChangeListener, SynthesizerListener, SMFMidiInListener {
	private static final long serialVersionUID = 0;
	private static boolean DEBUG = true;

	private static final double DEFAULT_LATENCY_MILLIS = 50.0;
	private static double DEFAULT_SLICETIME_MILLIS = 1.0;
	
	/**
	 * How many MIDI devices can be opened simultaneously
	 */
	private static final int MIDI_DEV_COUNT = 2;

	/**
	 * The width of the left labels in the GUI
	 */
	private final static int LABEL_WIDTH = 80;

	// range of controllers
	private static final int RANGE_POS = 0; // 0..127
	private static final int RANGE_CENTER = 1; // -64..+63
	private static final int RANGE_EXT_POS = 2; // 0..128*128-1
	private static final int RANGE_EXT_CENTER = 3; // -128*64....128*64-1
	private static final int RANGE_SWITCH = 4;

	private static final String PROPERTIES_FILE_SUFFIX = ".properties";

	// classes that have a local CLASS_DEBUG field
	private static final String[] debugClasses =
			{
					"com.ibm.realtime.synth.utils.Debug",
					"com.ibm.realtime.synth.engine.Synthesizer",
					"com.ibm.realtime.synth.engine.NoteInput",
					"com.ibm.realtime.synth.engine.AudioMixer",
					"com.ibm.realtime.synth.engine.AudioPullThread",
					"com.ibm.realtime.synth.engine.MaintenanceThread",
					"com.ibm.realtime.synth.engine.MidiChannel",
					"com.ibm.realtime.synth.engine.Oscillator",
					"com.ibm.realtime.synth.modules.JavaSoundMidiIn",
					"com.ibm.realtime.synth.modules.JavaSoundSink",
					"com.ibm.realtime.synth.modules.DirectAudioSink",
					"com.ibm.realtime.synth.modules.SMFMidiIn",
					"com.ibm.realtime.synth.modules.DiskWriterSink",
					"com.ibm.realtime.synth.soundfont2.SoundFontArticulation",
					"com.ibm.realtime.synth.soundfont2.SoundFontEnvelope",
					"com.ibm.realtime.synth.soundfont2.SoundFontFilter",
					"com.ibm.realtime.synth.soundfont2.SoundFontLFO",
					"com.ibm.realtime.synth.soundfont2.SoundFontSoundbank",
					"com.ibm.realtime.synth.soundfont2.Parser",
			};

	public static double[] BUFFER_SIZES_MILLIS = {
		0.3, 0.5, 0.7, 0.8, 0.9, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 15, 17, 20, 25, 30, 40, 50, 60, 70, 80, 90, 100
	};
	
	/**
	 * The name of this synth
	 */
	private String name;

	private JComboBox[] midiInLists = new JComboBox[MIDI_DEV_COUNT];
	private JRadioButton[] midiInIndicators = new JRadioButton[MIDI_DEV_COUNT];
	private JComboBox audioOutList;
	private JTextField sbField;
	private JButton sbBrowse;
	private JLabel sbNameLabel;
	private JComboBox channelList;
	private JComboBox instList;
	private JTextField bankField;
	private JComboBox ctrlList;
	private JTextField ctrlField;
	private JSlider ctrlSlider;

	private JTextField playerFile;
	private JButton playerBrowse;
	private JButton playerStart;
	private JButton playerPause;
	private JButton playerStop;
	private JLabel playerPos;

	private JTextField recorderFile;
	private JButton recorderBrowse;
	private JButton recorderStart;
	private JButton recorderStop;
	private JLabel recorderPos;

	private JTextField tuningField;
	private JSlider tuningSlider;

	private JComboBox bufferSizeList;
	private JTextField bufferSizeField;

	private JButton panicButton;

	private JLabel statusLabel;

	private Synthesizer synth;
	private Soundbank soundbank;
	private InstDesc[] allInstruments;
	private AudioMixer mixer;
	private JavaSoundSink jsSink;
	private DirectAudioSink daSink;
	private AudioSink sink; // the currently open sink
	private int directAudioDevCount = 0;
	private JavaSoundMidiIn[] midis = new JavaSoundMidiIn[MIDI_DEV_COUNT];
	private AudioPullThread pullThread;
	private MaintenanceThread maintenance;
	private SMFMidiIn player;
	private double sampleRate;
	private AudioFormat format;
	private int noUpdate = 0;
	// display MIDI indicator
	private MidiIndicatorThread[] midiIndicatorThreads = new MidiIndicatorThread[MIDI_DEV_COUNT];
	// display current position
	private ShowPosThread showPosThread = null;
	private DiskWriterSink recorder = null;
	private Properties props;

	/**
	 * Create an instance of the SoundFontGUI pane and load the persistence
	 * properties.
	 * 
	 * @param name the title of this pane
	 */
	public SFGPane(String name) {
		super();
		this.name = name;
		// currently, the following values cannot be changed
		sampleRate = 44100.0;
		format = new AudioFormat((float) sampleRate, 16, 2, true, false);

		gatherDebugHandlers();
		createDefaultProperties();
		loadProperties();
	}

	/**
	 * Create the GUI elements for the GUI synth.
	 */
	public void createGUI() {
		setLayout(new BorderLayout());

		JPanel p = new JPanel();
		p.setLayout(new StripeLayout(4, 4, 4, 4, 2));

		// MIDI input
		JPanel p2;
		for (int i = 0; i < midiInLists.length; i++) {
			p2 = getLayoutPanel(p);
			createLabel(p2, "MIDI IN "+(i+1)+":", LABEL_WIDTH);
			midiInLists[i] = createComboBox(p2, 220);
			midiInIndicators[i] = new JRadioButton("activity");
			midiInIndicators[i].setEnabled(false);
			p2.add(midiInIndicators[i]);
		}
		
		// audio output
		p2 = getLayoutPanel(p);
		createLabel(p2, "Audio Out:", LABEL_WIDTH);
		audioOutList = createComboBox(p2, 220);
		createLabel(p2, "Buffer Size (ms):", 0);
		bufferSizeList = createComboBox(p2, 0);
		bufferSizeField = createTextField(p2, "100.00ms", true, 0);
		bufferSizeField.setPreferredSize(bufferSizeField.getPreferredSize());
		p.add(new JSeparator());

		// soundbank
		p2 = getLayoutPanel(p);
		createLabel(p2, "Soundbank:", LABEL_WIDTH);
		sbField = createTextField(p2, "", false, 300);
		sbBrowse = new JButton("...");
		sbBrowse.addActionListener(this);
		p2.add(sbBrowse);
		p2 = getLayoutPanel(p);
		createLabel(p2, "Loaded Soundbank:", LABEL_WIDTH);
		sbNameLabel = createLabel(p2, "(none)", 1);
		p.add(new JSeparator());

		// Master Tuning
		p2 = new JPanel();
		p.add(p2);
		p2.setLayout(new BorderLayout());
		JLabel l = createLabel(null, "Tuning:", LABEL_WIDTH);
		l.setPreferredSize(new Dimension(LABEL_WIDTH,
				l.getPreferredSize().height));
		p2.add(l, BorderLayout.WEST);
		tuningSlider = new JSlider(0, 100);
		tuningSlider.addChangeListener(this);
		p2.add(tuningSlider, BorderLayout.CENTER);
		tuningField = createTextField(null, "440.0Hz", true, 0);
		tuningField.setPreferredSize(tuningField.getPreferredSize());
		p2.add(tuningField, BorderLayout.EAST);
		p.add(new JSeparator());

		// MIDI File name
		p2 = getLayoutPanel(p);
		createLabel(p2, "MIDI File:", LABEL_WIDTH);
		playerFile = createTextField(p2, "", false, 300);
		playerBrowse = new JButton("...");
		playerBrowse.addActionListener(this);
		p2.add(playerBrowse);

		// MIDI Transport Control
		p2 = getLayoutPanel(p);
		createLabel(p2, "", LABEL_WIDTH);
		playerStart = new JButton("Play");
		playerStart.addActionListener(this);
		p2.add(playerStart);
		playerPause = new JButton("Pause");
		playerPause.addActionListener(this);
		p2.add(playerPause);
		playerStop = new JButton("Stop");
		playerStop.addActionListener(this);
		p2.add(playerStop);
		playerPos = createLabel(p2, "", 30);
		p.add(new JSeparator());

		// Wave Recording
		p2 = getLayoutPanel(p);
		createLabel(p2, "Wave Record:", LABEL_WIDTH);
		recorderFile = createTextField(p2, "", false, 300);
		recorderBrowse = new JButton("...");
		recorderBrowse.addActionListener(this);
		p2.add(recorderBrowse);
		recorderStart = new JButton("Start");
		recorderStart.addActionListener(this);
		p2.add(recorderStart);
		recorderStop = new JButton("Stop");
		recorderStop.addActionListener(this);
		p2.add(recorderStop);
		recorderPos = createLabel(p2, "", 30);
		p.add(new JSeparator());

		// Channel selection
		p2 = getLayoutPanel(p);
		createLabel(p2, "Channel:", LABEL_WIDTH);
		channelList = createComboBox(p2, 100);
		panicButton = new JButton("Panic!");
		panicButton.addActionListener(this);
		p2.add(panicButton);

		// instrument selection
		p2 = getLayoutPanel(p);
		createLabel(p2, "Instrument:", LABEL_WIDTH);
		instList = createComboBox(p2, 220);
		createLabel(p2, "Bank:Prog:", 0);
		bankField = createTextField(p2, "00000:000", true, 0);
		bankField.setPreferredSize(bankField.getPreferredSize());

		// controller inspector
		JPanel ctrlPanel = new JPanel();
		ctrlPanel.setLayout(new BorderLayout());
		p.add(ctrlPanel);
		p2 = getLayoutPanel(ctrlPanel);
		createLabel(p2, "Controller:", LABEL_WIDTH);
		ctrlList = createComboBox(p2, 0);
		// createLabel(p2, "Value:", 70);
		ctrlPanel.add(p2, BorderLayout.WEST);
		// controller slider
		ctrlSlider = new JSlider(0, (128 * 128) - 1);
		ctrlSlider.addChangeListener(this);
		ctrlPanel.add(ctrlSlider, BorderLayout.CENTER);
		ctrlField = createTextField(null, "00000", true, 0);
		// set in stone the size of the controller width
		ctrlField.setPreferredSize(ctrlField.getPreferredSize());
		ctrlPanel.add(ctrlField, BorderLayout.EAST);
		p.add(new JSeparator());

		this.add(p, BorderLayout.NORTH);

		// status field
		p = new JPanel();
		statusLabel = new JLabel();
		this.add(statusLabel, BorderLayout.SOUTH);

		this.add(getDebugPanel(), BorderLayout.CENTER);

	}

	/**
	 * Create all functional objects for the synth and initialize them. They are
	 * initialized with the last values using the properties.
	 */
	@SuppressWarnings("unchecked")
	public void init() {
		commitProps2DebugFields();
		noUpdate++;
		try {
			status("Creating Synthesizer...");
			synth = new Synthesizer();
			synth.start();
			status("Gathering MIDI devices...");
			List infos = JavaSoundMidiIn.getDeviceList();
			String[] infoArray = new String[infos.size()+1];
			infoArray[0] = "(none)";
			for (int i = 1; i<infoArray.length; i++) {
				infoArray[i] = infos.get(i-1).toString();
			}
			for (JComboBox mList: midiInLists) {
				mList.setModel(new DefaultComboBoxModel(infoArray));
				mList.setEnabled(infos.size() >= 1);
			}

			status("Gathering audio devices...");
			List<String> directAudioDevs = DirectAudioSink.getDeviceList();
			directAudioDevCount = directAudioDevs.size();
			List<Mixer.Info> ainfos = JavaSoundSink.getDeviceList();
			String[] sa = new String[ainfos.size() + directAudioDevCount];
			for (int i = 0; i < directAudioDevCount; i++) {
				sa[i] = directAudioDevs.get(i);
			}
			for (int i = 0; i < ainfos.size(); i++) {
				sa[i+directAudioDevCount] = "Java Sound: "+ainfos.get(i).getName();
			}
			audioOutList.setModel(new DefaultComboBoxModel(sa));
			audioOutList.setEnabled(sa.length > 0);
			// create the buffer size list's entries
			String[] bufferSizes = new String[BUFFER_SIZES_MILLIS.length];
			for (int i = 0; i<BUFFER_SIZES_MILLIS.length; i++) {
				bufferSizes[i] = String.valueOf(BUFFER_SIZES_MILLIS[i]);
			}
			bufferSizeList.setModel(new DefaultComboBoxModel(bufferSizes));
			bufferSizeList.setEnabled(audioOutList.isEnabled());
			displayEffectiveBufferTime();

			status("Gathering Channels...");
			List channels = synth.getChannels();
			channelList.setModel(new DefaultComboBoxModel(channels.toArray()));

			status("Gathering controllers...");
			String[] ctrls = new String[CONTROLLERS.length];
			for (int i = 0; i < ctrls.length; i++) {
				ctrls[i] = MidiUtils.getControllerName(CONTROLLERS[i]);
			}
			ctrlList.setModel(new DefaultComboBoxModel(ctrls));

			status("Creating Mixer...");
			mixer = new AudioMixer();
			synth.setMixer(mixer);
			synth.addListener(this);

			status("creating sinks...");
			jsSink = new JavaSoundSink();
			daSink = new DirectAudioSink();
			
			status("creating AudioPullThread...");
			pullThread = new AudioPullThread(mixer, null);

			DEFAULT_SLICETIME_MILLIS = getIntProperty("sliceTimeMicros", 
					(int) (DEFAULT_SLICETIME_MILLIS * 1000.0)) / 1000.0;
			pullThread.setSliceTimeMillis(DEFAULT_SLICETIME_MILLIS);

			status("connecting Synthesizer with AudioPullThread...");
			pullThread.addListener(synth);
			debug("Using "+synth.getRenderThreadCount()+" render threads.");
			for (int i = 0; i<midis.length; i++) {
				status("creating JavaSoundMidiIn "+(i+1)+"...");
				midis[i] = new JavaSoundMidiIn(i);
				status("connecting MidiIn "+(i+1)+"to Synthesizer...");
				midis[i].addListener(synth);
			}

			status("Creating player object");
			player = new SMFMidiIn();
			player.setDeviceIndex(MIDI_DEV_COUNT); // one after the real MIDI devices
			status("connecting Player to Synthesizer...");
			player.addListener(synth);

			status("creating and connecting maintenance thread...");
			maintenance = new MaintenanceThread();
			maintenance.addServiceable(mixer);
			for (MidiIn mi: midis) {
				maintenance.addAdjustableClock(mi);
			}
			maintenance.addAdjustableClock(player);

			status("creating GUI threads...");
			for (int i = 0; i<midiInIndicators.length; i++) {
				midiIndicatorThreads[i] = new MidiIndicatorThread(midiInIndicators[i]);
			}
			showPosThread = new ShowPosThread();

			applyPropertiesToGUI();
		} finally {
			noUpdate--;
		}
		status("Init done.");
	}

	/**
	 * Start the synthesizer. In particular, these classes are started:
	 * <ul>
	 * <li>the soundbank is loaded</li>
	 * <li>the audio output device is started</li>
	 * <li>the MIDI input device is started</li>
	 * <li>the audio thread (main rendering thread) is started</li>
	 * <li>the maintenance thread is started</li>
	 */
	public void start() {
		// will trigger displayChannel()
		loadSoundbank();
		startAudioDevice();
		for (int i = 0; i<MIDI_DEV_COUNT; i++) {
			startMIDIDevice(i);
		}

		status("starting AudioPullThread...");
		pullThread.start();
		status("starting maintenance thread...");
		maintenance.start();
		makePlayerButtons();
		makeRecorderButtons();
		status("Ready.");
	}

	/**
	 * Close all devices, i/o, and threads.
	 */
	public void close() {
		saveProperties();
		// clean-up
		if (player != null) {
			try {
				player.close();
			} catch (Exception e) {
				error(e);
			}
		}
		if (recorder != null) {
			recorder.close();
		}
		if (sink != null) {
			sink.close();
		}
		if (showPosThread != null) {
			showPosThread.close();
		}
		for (MidiIndicatorThread mit: midiIndicatorThreads) {
			if (mit != null) {
				mit.close();
			}
		}
		if (maintenance != null) {
			maintenance.stop();
		}
		if (pullThread != null) {
			pullThread.stop();
		}
		for (MidiIn mi : midis) {
			if (mi != null) {
				try {
					mi.close();
				} catch (Exception e) {
					error(e);
				}
			}
		}
		if (synth != null) {
			synth.close();
		}
	}

	private void loadSoundbank() {
		status("Loading Soundbank...");
		try {
			soundbank = null;
			soundbank = new SoundFontSoundbank(new File(sbField.getText()));
			status("Populating instrument list...");
			List<Soundbank.Bank> banks = soundbank.getBanks();
			List<InstDesc> all = new ArrayList<InstDesc>(banks.size() * 128);
			for (Soundbank.Bank bank : banks) {
				List<Soundbank.Instrument> insts = bank.getInstruments();
				for (Soundbank.Instrument inst : insts) {
					all.add(new InstDesc(bank.getMidiNumber(),
							inst.getMidiNumber(), inst.getName()));
				}
			}
			allInstruments = (InstDesc[]) all.toArray(new InstDesc[all.size()]);
			instList.setModel(new DefaultComboBoxModel(allInstruments));
			status("Loaded Soundbank.");
		} catch (FileNotFoundException fnfe) {
			Exception e = fnfe;
			if (e.getMessage() == null || e.getMessage().length() == 0) {
				e = new Exception("file '" + playerFile.getText()
						+ "' not found", e);
			}
			status(e, "Cannot load soundbank:");
		} catch (Exception e) {
			status(e, "Cannot load soundbank:");
		}
		// make sure that displayChannel is always called!
		displayChannel();
		synth.setSoundbank(soundbank);
		if (soundbank == null) {
			sbNameLabel.setText("(none)");
		} else {
			sbNameLabel.setText(soundbank.getName());
		}
	}

	private void startAudioDevice() {
		if ((sink != null) && sink.isOpen()) {
			status("Closing soundcard...");
			sink.close();
			status("Soundcard closed.");
		}
		int audioIndex = audioOutList.getSelectedIndex();
		if (audioIndex >= 0) {
			double latencyMillis = getSelectedLatency();
			status("Opening soundcard (latency="+format1(latencyMillis)+"ms...");
			// open the sink and connect it with the mixer
			try {
				// need to adjust the slice time?
				double sliceTime = DEFAULT_SLICETIME_MILLIS;
				if (latencyMillis < sliceTime) {
					sliceTime = latencyMillis;
				}
				pullThread.setSliceTimeMillis(sliceTime);
				// align the buffer size to the pull thread's slice time
				int bufferSizeSamples = pullThread.getPreferredSinkBufferSizeSamples(latencyMillis, format.getSampleRate());
				if (audioIndex < directAudioDevCount) {
					// open a direct audio device
					// the name of the audio device is derived from the description
					String name = audioOutList.getModel().getElementAt(audioIndex).toString();
					int p = name.indexOf("|");
					name = name.substring(0, p);
					daSink.open(name, format, bufferSizeSamples);
					sink = daSink;
				} else {
					jsSink.open(audioIndex - directAudioDevCount, format, bufferSizeSamples);
					sink = jsSink;
				}
				long effectiveLatency = AudioUtils.samples2nanos(sink.getBufferSize(), format.getSampleRate());
				// Since the render thread may already have finished rendering
				// when the sink just started playing the last buffer, there is 
				// a maximum of 2x buffer size latency in the system
				synth.setFixedDelayNanos(2 * effectiveLatency);
				status("Soundcard is open.");
			} catch (Exception e) {
				status(e, "Error opening soundcard:");
				noUpdate++;
				try {
					audioOutList.setSelectedIndex(-1);
				} finally {
					noUpdate--;
				}
			}
			displayEffectiveBufferTime();
			// apply the sink
			synth.setMasterClock(sink);
			pullThread.setSink(sink);
			maintenance.setMasterClock(sink);
		}
	}

	private void startMIDIDevice(int index) {
		if (midis[index].isOpen()) {
			status("Closing MIDI input...");
			try {
				midis[index].close();
				status("MIDI input closed.");
			} catch (Exception e) {
				error(e);
			}
		}
		int midiDev = midiInLists[index].getSelectedIndex()-1;
		if (midiDev >= 0) {
			// make sure that this MIDI device is not already open!
			for (int i = 0; i<MIDI_DEV_COUNT; i++) {
				if ((i != index) && (midiInLists[i].getSelectedIndex()-1 == midiDev)) {
					showError("This MIDI device is already open!");
					// set to an error number
					midiDev = -2;
					break;
				}
			}
		}
		if (midiDev >= 0) {
			status("Opening MIDI input "+(index+1)+"...");
			// open the midi device
			try {
				midis[index].open(midiDev);
				status("MIDI Input "+(index+1)+" is open.");
			} catch (Exception e) {
				status(e, "Cannot open MIDI Input "+(index+1)+":");
				// set to an error number
				midiDev = -2;
			}
		}
		if (midiDev == -2) {
			noUpdate++;
			try {
				midiInLists[index].setSelectedIndex(0);
			} finally {
				noUpdate--;
			}
		}
	}

	// UTILITY METHODS
	private JPanel getLayoutPanel(JPanel parent) {
		JPanel p2 = new JPanel();
		p2.setLayout(new FlowLayout2(0, 0, 0, 0, 5));
		if (parent != null) {
			parent.add(p2);
		}
		return p2;
	}

	private JComboBox createComboBox(JComponent parent, int minWidth) {
		JComboBox cb = new JComboBox();
		cb.addItemListener(this);
		if (minWidth > 0) {
			cb.setMinimumSize(new Dimension(minWidth, 1));
		}
		if (parent != null) {
			parent.add(cb);
		}
		return cb;
	}

	private JLabel createLabel(JComponent parent, String text, int minWidth) {
		JLabel l = new JLabel(text);
		if (minWidth > 0) {
			l.setMinimumSize(new Dimension(minWidth, 1));
		}
		if (parent != null) {
			parent.add(l);
		}
		return l;
	}

	private JTextField createTextField(JComponent parent, String text,
			boolean readOnly, int minWidth) {
		JTextField tf = new JTextField(text);
		tf.setEditable(!readOnly);
		tf.setMinimumSize(new Dimension(minWidth, 1));
		if (parent != null) {
			parent.add(tf);
		}
		return tf;
	}

	private void status(String s) {
		if (s == "") s = " ";
		if (statusLabel != null) {
			statusLabel.setText(s);
		}
	}

	private void status(Exception e, String description) {
		if (statusLabel != null) {
			status("Error: "+description+e.getMessage());
		}
		error(e);
		JOptionPane.showMessageDialog(this, e.getMessage(), description,
				JOptionPane.ERROR_MESSAGE);
	}

	private void showError(String description) {
		if (statusLabel != null) {
			status("Error: "+description);
		}
		JOptionPane.showMessageDialog(this, description, "Error",
				JOptionPane.ERROR_MESSAGE);
	}

	// PROPERTIES

	/**
	 * Load properties from file. This is called from the constructor before any
	 * GUI elements or functional classes are created.
	 */
	private void loadProperties() {
		File file = getPropertiesFile();
		if (file.exists()) {
			if (DEBUG) {
				debug("loading properties from file: " + file);
			}
			try {
				props.load(new FileInputStream(file));
			} catch (Exception e) {
				debug(e);
			}
		}
	}

	/**
	 * Save the current state in the properties and save them to the properties
	 * file. This method should be called before any objects are closed.
	 */
	private void saveProperties() {
		// first update the properties with the current GUI values
		// TODO: handle case that no item is selected!
		for (int i=0; i<midiInLists.length; i++) {
			storeListToProperty(midiInLists[i], "midiInDevice"+i);
		}
		storeListToProperty(audioOutList, "audioOutDevice");
		setProperty("soundbank", sbField.getText());
		setProperty("midiFile", playerFile.getText());
		setProperty("recorderFile", recorderFile.getText());
		setProperty("masterTuning",
				(int) (synth.getParams().getMasterTuning() * 10));
		setProperty("channel", getSelectedChannelNumber() + 1);
		setProperty("controller1-1", getSelectedController());
		setProperty("latencyInMicros", (int) (getSelectedLatency()*1000.0));
		commitDebugGUI2Props();

		File file = getPropertiesFile();
		status("writing properties file: " + file);
		if (DEBUG) {
			debug("writing properties file: " + file);
		}
		try {
			props.store(new FileOutputStream(file), name
					+ " properties: machine generated, do not modify.");
		} catch (Exception e) {
			debug(e);
		}
	}

	/**
	 * Initialize the GUI elements with the values in the properties object.
	 * This method is called after creation of GUI elements and after
	 * initialization of the synth engine objects, but before starting the
	 * engine.
	 */
	private void applyPropertiesToGUI() {
		noUpdate++;
		try {
			sbField.setText(getStringProperty("soundbank"));
			playerFile.setText(getStringProperty("midiFile"));
			for (int i=0; i<midiInLists.length; i++) {
				setListByProperty(midiInLists[i], "midiInDevice"+i);
			}
			setListByProperty(audioOutList, "audioOutDevice");
			recorderFile.setText(getStringProperty("recorderFile"));
			// apply selected channel and controller
			ctrlList.setSelectedIndex(getCtrlIndexFromController(getIntProperty(
					"controller1-1", 7)));
			// setup the controller slider with the correct range
			displayCtrl();
			channelList.setSelectedIndex(getIntProperty("channel", 1) - 1);
			// do not display channel here -- need to have soundbank for that
			synth.getParams().setMasterTuning(
					((double) getIntProperty("masterTuning", 4400)) / 10.0);
			displayTuningSlider();
			double latencyMillis = getIntProperty("latencyInMicros",
					(int) (DEFAULT_LATENCY_MILLIS * 1000.0)) / 1000.0;
			displayLatencyIndex(latencyMillis);
		} finally {
			noUpdate--;
		}
	}

	/**
	 * @return the file to which the properties are saved, and from which
	 *         they're read.
	 */
	private File getPropertiesFile() {
		String home = ".";
		String prefix = "";
		try {
			home = System.getProperty("user.home");
			// if saving to home directory, hide the file name
			prefix = ".";
		} catch (Exception e) {
			debug(e);
		}
		return new File(home, prefix + name.toLowerCase()
				+ PROPERTIES_FILE_SUFFIX);
	}

	/**
	 * @param key the key of the requested property
	 * @return the value of the property, or the empty string if the property
	 *         does not exist
	 */
	private String getStringProperty(String key) {
		return props.getProperty(key, "");
	}

	/**
	 * @param key the key of the requested integer property
	 * @return the value of the property, or the default value if the property
	 *         does not exist, or if the property is not an integer property
	 */
	public int getIntProperty(String key, int def) {
		String p = props.getProperty(key, Integer.toString(def));
		try {
			return Integer.parseInt(p);
		} catch (NumberFormatException nfe) {
		}
		return def;
	}

	public void setProperty(String key, String value) {
		props.setProperty(key, value);
	}

	public void setProperty(String key, int value) {
		props.setProperty(key, Integer.toString(value));
	}

	private void setProperty(String key, boolean value) {
		props.setProperty(key, value ? "true" : "false");
	}

	/**
	 * @param key the key of the requested integer property
	 * @return the value of the property, or the default value if the property
	 *         does not exist, or if the property is not a boolean property
	 */
	private boolean getBoolProperty(String key, boolean def) {
		String p = props.getProperty(key, def ? "true" : "false");
		p = p.toLowerCase();
		if (p.equals("true") || p.equals("yes")) {
			return true;
		}
		if (p.equals("false") || p.equals("no")) {
			return false;
		}
		return def;
	}

	/**
	 * Create the props object and fill it with a number of non-trivial default 
	 * properties.
	 */
	private void createDefaultProperties() {
		props = new Properties();
		setProperty("soundbank", "E:\\TestSounds\\sf2\\Chorium.sf2");
		setProperty("midiFile",
				"E:\\TestSounds\\mid\\hitbit_dance\\hb_DancingQueen.mid");
		setProperty("recorderFile", "C:\\" + name.toLowerCase() + ".wav");
		// 1-based channel
		setProperty("channel", 1);
		// first controller slider on first channel: volume
		setProperty("controller1-1", 7);
	}

	private void setListByProperty(JComboBox list, String key) {
		int index = 0;
		noUpdate++;
		try {
			String val = getStringProperty(key);
			if (val.length() > 0) {
				for (int i = 0; i < list.getModel().getSize(); i++) {
					if (list.getModel().getElementAt(i).toString().equals(val)) {
						index = i;
						break;
					}
				}
			}
			if (index < list.getModel().getSize()) {
				list.setSelectedIndex(index);
			}
		} finally {
			noUpdate--;
		}
	}

	private void storeListToProperty(JComboBox list, String key) {
		Object sel = list.getSelectedItem();
		String s = (sel == null) ? "" : sel.toString();
		props.setProperty(key, s);
	}

	// utils

	/**
	 * @return a String with the number
	 *         <code>num</num> prepended with as many zeroes as necessary to return a string with exactly <code>digits</code> characters.
	 */
	private static final String formatNum(int num, int digits) {
		// TODO: optimize with StringBuffer or so
		String result = Integer.toString(num);
		while (result.length() < digits)
			result = "0" + result;
		return result;
	}

	private static final String formatTime(long timeMillis) {
		// TODO: optimize with StringBuffer or so
		return Long.toString(timeMillis / 60000) + ":"
				+ formatNum((int) (timeMillis / 1000) % 60, 2) + "."
				+ Integer.toString((int) (timeMillis / 100) % 10);
	}

	private static final String getInstNumber(int bank, int prog) {
		String sBank;
		if (bank >= 0) {
			sBank = formatNum(bank, 5);
		} else {
			sBank = "-----";
		}
		String sProg;
		if (prog >= 0) {
			sProg = formatNum(prog + 1, 3);
		} else {
			sProg = "---";
		}
		return sBank + ":" + sProg;
	}

	// list listener
	public void itemStateChanged(ItemEvent e) {
		if (noUpdate > 0) return;
		if (e.getStateChange() == ItemEvent.SELECTED) {
			status("");
			if (e.getSource() == audioOutList) {
				startAudioDevice();
			} else if (e.getSource() == bufferSizeList) {
				// need to restart the audio device in order to change the 
				// buffer size... 
				startAudioDevice();
			} else if (e.getSource() == channelList) {
				displayChannel();
			} else if (e.getSource() == instList) {
				sendInstrument();
			} else if (e.getSource() == ctrlList) {
				displayCtrl();
			} else {
				for (int i = 0; i<MIDI_DEV_COUNT; i++) {
					if (e.getSource() == midiInLists[i]) {
						startMIDIDevice(i);
						break;
					}
				}
			}
		}
	}

	// action listener
	public void actionPerformed(ActionEvent e) {
		if (noUpdate > 0) return;
		if (e.getSource() == sbBrowse) {
			JFileChooser fileChooser = getFileChooser(sbField.getText());
			if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
				sbField.setText(fileChooser.getSelectedFile().getAbsolutePath());
				loadSoundbank();
			}
		} else if (e.getSource() == playerBrowse) {
			JFileChooser fileChooser = getFileChooser(playerFile.getText());
			if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
				playerFile.setText(fileChooser.getSelectedFile().getAbsolutePath());
			}
		} else if (e.getSource() == recorderBrowse) {
			JFileChooser fileChooser = getFileChooser(recorderFile.getText());
			if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
				recorderFile.setText(fileChooser.getSelectedFile().getAbsolutePath());
			}
		} else if (e.getSource() == playerStart) {
			startPlayer();
		} else if (e.getSource() == playerPause) {
			pausePlayer();
		} else if (e.getSource() == playerStop) {
			stopPlayer();
		} else if (e.getSource() == recorderStart) {
			startRecorder();
		} else if (e.getSource() == recorderStop) {
			stopRecorder();
		} else if (e.getSource() == panicButton) {
			if (synth != null) {
				status("Resetting synth...");
				synth.reset();
				displayChannel();
				status("Synth reset.");
			}
		} else {
			status("");
		}
	}

	// change listener
	public void stateChanged(ChangeEvent e) {
		if (noUpdate > 0) return;
		if (e.getSource() == ctrlSlider) {
			sendController();
		} else if (e.getSource() == tuningSlider) {
			sendTuning();
		}
	}

	// synthesizer listener
	public void midiEventPlayed(AudioTime time, MidiIn input, MidiChannel channel, int status, int data1,
			int data2) {
		if (channel.getChannelNum() == getSelectedChannelNumber()) {
			if (status == 0xB0) {
				// controller
				if (data1 == getSelectedController()) {
					displayCtrlValue();
				}
			} else if (status == 0xC0) {
				// program change
				displayInstrument();
			}
		}
		if (input != null) {
			int devIndex = input.getInstanceIndex();
			if (devIndex < midiIndicatorThreads.length) {
				// blink the activity button
				if (midiIndicatorThreads[devIndex] != null) {
					midiIndicatorThreads[devIndex].onMidiEvent();
				}
			}
		}
	}

	private JFileChooser getFileChooser(String defFile) {
		File file = new File(defFile);
		JFileChooser fc = new JFileChooser(file);
		fc.setCurrentDirectory(file.getParentFile());
		return fc;
	}
	
	// buffer time handling
	
	private double getSelectedLatency() {
		int listIndex = bufferSizeList.getSelectedIndex();
		if (listIndex >= 0 && listIndex < BUFFER_SIZES_MILLIS.length) {
			return BUFFER_SIZES_MILLIS[listIndex];
		}
		return DEFAULT_LATENCY_MILLIS;
	}
	
	private void displayLatencyIndex(double latencyMillis) {
		int listIndex = BUFFER_SIZES_MILLIS.length-1;
		for (int i = 0; i<BUFFER_SIZES_MILLIS.length; i++) {
			if (latencyMillis <= BUFFER_SIZES_MILLIS[i]) {
				listIndex = i;
				break;
			}
		}
		noUpdate++;
		try {
			bufferSizeList.setSelectedIndex(listIndex);
		} finally {
			noUpdate--;
		}
		displayEffectiveBufferTime();
	}
	
	private void displayEffectiveBufferTime() {
		if (sink != null && sink.isOpen()) {
			bufferSizeField.setText(Debug.format2(
					AudioUtils.samples2micros(sink.getBufferSize(), 
					sink.getSampleRate())/1000.0));
		} else {
			bufferSizeField.setText("");
		}
	}

	// controller handling

	/**
	 * The supported set of controllers.
	 */
	private int[] CONTROLLERS =
			new int[] {
					MODULATION, PORTAMENTO_TIME, VOLUME, PAN, EXPRESSION,
					SUSTAIN_PEDAL, PORTAMENTO, SOSTENUTO_PEDAL, SOFT,
					RESONANCE, RELEASE_TIME, ATTACK_TIME, CUTOFF, DECAY_TIME,
					VIBRATO_RATE, VIBRATO_DEPTH, VIBRATO_DELAY, REVERB_LEVEL,
					CHORUS_LEVEL,
			};

	private int getControllerRange(int controller) {
		switch (controller) {
		case MODULATION: // fall through
		case PORTAMENTO_TIME: // fall through
		case VOLUME: // fall through
		case EXPRESSION:
			return RANGE_EXT_POS;
		case PAN:
			return RANGE_EXT_CENTER;

		case SUSTAIN_PEDAL: // fall through
		case PORTAMENTO: // fall through
		case SOSTENUTO_PEDAL:// fall through
		case SOFT:
			return RANGE_SWITCH;

		case RESONANCE: // fall through
		case RELEASE_TIME: // fall through
		case ATTACK_TIME: // fall through
		case CUTOFF: // fall through
		case DECAY_TIME: // fall through
		case VIBRATO_RATE: // fall through
		case VIBRATO_DEPTH: // fall through
		case VIBRATO_DELAY: // fall through
		case REVERB_LEVEL: // fall through
		case CHORUS_LEVEL: // fall through
			return RANGE_CENTER;
		}
		return RANGE_POS;
	}

	private MidiChannel getSelectedChannel() {
		return (MidiChannel) channelList.getSelectedItem();
	}

	private int getSelectedChannelNumber() {
		return getSelectedChannel().getChannelNum();
	}

	private InstDesc getSelectedInstrument() {
		return (InstDesc) instList.getModel().getSelectedItem();
	}

	/**
	 * React to a change of the currently selected MIDI channel
	 */
	private void displayChannel() {
		noUpdate++;
		try {
			int iChannel = channelList.getSelectedIndex();
			boolean enabled = (iChannel >= 0);
			int bank = -1;
			int program = -1;
			if (enabled) {
				MidiChannel channel = getSelectedChannel();
				bank = channel.getBank();
				program = channel.getProgram();
			}
			displayInstrument(bank, program);
			displayCtrlValue();
			instList.setEnabled(enabled);
			ctrlList.setEnabled(enabled);
			ctrlSlider.setEnabled(enabled);
		} finally {
			noUpdate--;
		}
	}

	/**
	 * Returns the index in instList, or -1 if not found.
	 * 
	 * @param bank
	 * @return
	 */
	private int getInstIndex(int bank, int program) {
		if (allInstruments == null || allInstruments.length == 0) {
			return -1;
		}
		for (int i = 0; i < allInstruments.length; i++) {
			InstDesc desc = allInstruments[i];
			if (desc.getBank() == bank && desc.getProgram() == program) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Only update the text field with the bank/program number.
	 */
	private void displayInstrumentNumber(int bank, int program) {
		bankField.setText(getInstNumber(bank, program));
	}

	private void displayInstrument() {
		MidiChannel channel = getSelectedChannel();
		displayInstrument(channel.getBank(), channel.getProgram());
	}

	private void displayInstrument(int bank, int program) {
		noUpdate++;
		try {
			int index = getInstIndex(bank, program);
			instList.setSelectedIndex(index);
			// work-around: on some systems, the instrument list is not
			// correctly displayed
			instList.repaint();
			displayInstrumentNumber(bank, program);
		} finally {
			noUpdate--;
		}
	}

	private int getCtrlIndexFromController(int ctrl) {
		for (int i = 0; i < CONTROLLERS.length; i++) {
			if (CONTROLLERS[i] == ctrl) {
				return i;
			}
		}
		return 0;
	}

	private int getSelectedController() {
		int i = ctrlList.getSelectedIndex();
		if (i >= 0) {
			return CONTROLLERS[i];
		}
		return -1;
	}

	private void displayCtrl() {
		noUpdate++;
		try {
			int ctrl = getSelectedController();
			MidiChannel channel = getSelectedChannel();
			if (ctrl >= 0 && channel != null) {
				ctrlSlider.setEnabled(true);
				int min = 0;
				int max = 0;
				switch (getControllerRange(ctrl)) {
				case RANGE_CENTER:
					min = -64;
					max = 63;
					break;
				case RANGE_POS:
					min = 0;
					max = 127;
					break;
				case RANGE_EXT_CENTER:
					min = -64 * 128;
					max = (63 * 128) - 1;
					break;
				case RANGE_EXT_POS:
					min = 0;
					max = (127 * 128) - 1;
					break;
				case RANGE_SWITCH:
					min = 0;
					max = 1;
					break;
				}
				ctrlSlider.setMinimum(min);
				ctrlSlider.setMaximum(max);
			} else {
				ctrlSlider.setEnabled(false);
			}
			displayCtrlValue();
		} finally {
			noUpdate--;
		}
	}

	private void displayCtrlValueNumber(int value) {
		ctrlField.setText(Integer.toString(value));
	}

	private void displayCtrlValue() {
		displayCtrlValue(-1, -1);
	}

	private void displayCtrlValue(int ctrl, int value) {
		noUpdate++;
		try {
			int value14 = 0;
			if (ctrl < 0) {
				ctrl = getSelectedController();
				MidiChannel channel = getSelectedChannel();
				if (channel == null) return;
				value = channel.getController(ctrl);
				if (ctrl < 32) {
					value14 = channel.getController14bit(ctrl);
				}
			} else {
				if (ctrl >= 32 && ctrl < 64) {
					// ignore LSB messages, they must be followed by MSB anyway
					return;
				}
				if (ctrl < 32) {
					MidiChannel channel = getSelectedChannel();
					if (channel == null) return;
					value14 = (value << 7) | channel.getController(ctrl + 32);
				}
			}
			if (ctrl >= 0) {
				switch (getControllerRange(ctrl)) {
				case RANGE_CENTER:
					value -= 64;
					break;
				case RANGE_POS:
					// nothing
					break;
				case RANGE_EXT_CENTER:
					value = value14 - (64 * 128);
					break;
				case RANGE_EXT_POS:
					value = value14;
					break;
				case RANGE_SWITCH:
					if (value >= 64) {
						value = 1;
					} else {
						value = 0;
					}
					break;
				}
				ctrlSlider.setValue(value);
				displayCtrlValueNumber(value);
			} else {
				ctrlField.setText("");
			}
		} finally {
			noUpdate--;
		}
	}

	/**
	 * Send out the MIDI controller message in response to moving the slider.
	 */
	@SuppressWarnings("fallthrough")
	private void sendController() {
		if (synth == null || sink == null) {
			return;
		}
		noUpdate++;
		try {
			int ctrl = getSelectedController();
			int channel = getSelectedChannelNumber();
			if (ctrl >= 0 && channel >= 0) {
				int value = ctrlSlider.getValue();
				// display should get updated by way of MIDI feedback
				// displayCtrlValueNumber(value);
				switch (getControllerRange(ctrl)) {
				case RANGE_CENTER:
					value += 64;
					// fall through
				case RANGE_POS:
					synth.midiInReceived(new MidiEvent(null,
							sink.getAudioTime(), channel, 0xB0, ctrl, value));
					break;
				case RANGE_EXT_CENTER:
					value += 128 * 64;
					// fall through
				case RANGE_EXT_POS:
					synth.midiInReceived(new MidiEvent(null,
							sink.getAudioTime(), channel, 0xB0, ctrl + 32,
							value & 0x7F));
					synth.midiInReceived(new MidiEvent(null,
							sink.getAudioTime(), channel, 0xB0, ctrl,
							(value >> 7) & 0x7F));
					break;
				case RANGE_SWITCH:
					if (value > 0) {
						value = 127;
					}
					synth.midiInReceived(new MidiEvent(null,
							sink.getAudioTime(), channel, 0xB0, ctrl, value));
					break;
				}
			}
		} finally {
			noUpdate--;
		}
	}

	/**
	 * Change the current channel to the currently selected instrument in the
	 * instrument combo box.
	 */
	private void sendInstrument() {
		int channel = getSelectedChannelNumber();
		InstDesc desc = getSelectedInstrument();
		if (desc != null && channel >= 0) {
			if (synth != null && sink != null) {
				synth.midiInReceived(new MidiEvent(null, sink.getAudioTime(),
						channel, 0xB0, MidiChannel.BANK_SELECT_LSB,
						desc.getBank() & 0x7F));
				synth.midiInReceived(new MidiEvent(null, sink.getAudioTime(),
						channel, 0xB0, MidiChannel.BANK_SELECT_MSB,
						(desc.getBank() >> 7) & 0x7F));
				synth.midiInReceived(new MidiEvent(null, sink.getAudioTime(),
						channel, 0xC0, desc.getProgram(), 0));
			}
			// display will get updated by way of MIDI events
			// displayInstrumentNumber(desc.getBank(), desc.getProgram());
		}
	}

	// Tuning

	/**
	 * Set the slider's position according to the current value of the synth's
	 * master tuning. Also call displayTuningValue() so that the tuning field is
	 * updated accordingly.
	 */
	private void displayTuningSlider() {
		noUpdate++;
		try {
			// the tuning slider's values are from 0 to x. Center it around 440
			// Hz, allow 0.5Hz steps.
			int relativeTuning =
					(int) ((synth.getParams().getMasterTuning() - 440.0) * 2);
			int newValue = (tuningSlider.getMaximum() / 2) + relativeTuning;
			if (newValue < 0) {
				newValue = 0;
			} else if (newValue > tuningSlider.getMaximum()) {
				newValue = tuningSlider.getMaximum();
			}
			tuningSlider.setValue(newValue);
			displayTuningValue();
		} finally {
			noUpdate--;
		}
	}

	/**
	 * Update the tuning text field with the current tuning in the synth.
	 */
	private void displayTuningValue() {
		double tuning = synth.getParams().getMasterTuning();
		tuningField.setText(format1(tuning) + "Hz");
	}

	/**
	 * Update the synth with the new tuning as selected by the tuning slider.
	 * Also call displayTuningValue() so that the tuning field is updated
	 * accordingly.
	 */
	private void sendTuning() {
		int relativeSliderVal =
				tuningSlider.getValue() - (tuningSlider.getMaximum() / 2);
		double newTuning = (((double) relativeSliderVal) / 2.0) + 440.0;
		synth.getParams().setMasterTuning(newTuning);
		displayTuningValue();
	}

	// MIDI player stuff

	protected volatile boolean midiPlayerStarted;

	/**
	 * Start the currently selected MIDI file
	 */
	private void startPlayer() {
		midiPlayerStarted = false;
		try {
			File file = new File(playerFile.getText());
			if (!file.equals(player.getFile())) {
				status("Loading MIDI file: " + file);
				player.open(file);
				status("MIDI file loaded: " + file);
			}
			player.setStopListener(this);
			midiPlayerStarted = true;
			// finally start the sequencer
			player.start();
			// make sure the clocks are synchronized
			maintenance.synchronizeClocks(true);
			if (showPosThread != null) {
				showPosThread.ping();
			}
			status("MIDI file playing: " + file);
		} catch (FileNotFoundException fnfe) {
			Exception e = fnfe;
			if (e.getMessage() == null || e.getMessage().length() == 0) {
				e = new Exception("file '" + playerFile.getText()
						+ "' not found", e);
			}
			status(e, "Cannot load MIDI file:");
		} catch (Exception e) {
			status(e, "Cannot load MIDI file:");
		}
		makePlayerButtons();
	}

	/**
	 * Pause the currently playing MIDI file
	 */
	private void pausePlayer() {
		if (player.isStarted()) {
			midiPlayerStarted = false;
			player.stop();
			makePlayerButtons();
			status("MIDI file playback paused.");
		} else {
			startPlayer();
		}
	}

	/**
	 * Stop the currently playing MIDI file and rewind
	 */
	private void stopPlayer() {
		if (player.isOpen()) {
			player.stop();
			player.rewind();
			status("MIDI file playback stopped.");
		}
		midiPlayerStarted = false;
		displayCurrentPositions();
		makePlayerButtons();
	}

	// interface SMFMidiIn.StopListener
	public void onMidiPlaybackStop() {
		midiPlayerStarted = false;
		makePlayerButtons();
		status("MIDI file playback stopped.");
	}

	public void displayCurrentPositions() {
		if (player != null) {
			playerPos.setText(formatTime(player.getPlaybackPosMillis()));
		} else {
			playerPos.setText("");
		}
		if (recorder != null) {
			recorderPos.setText(formatTime(recorder.getAudioTime().getMillisTime()));
		}
	}

	/**
	 * Enable/disable buttons of the MIDI Player transport panel
	 */
	private void makePlayerButtons() {
		playerStart.setEnabled(!midiPlayerStarted);
		playerStop.setEnabled(midiPlayerStarted
				|| (player != null && player.getPlaybackPosMillis() != 0));
		playerPause.setEnabled(playerStop.isEnabled());
	}

	// WAVE RECORDER

	protected volatile boolean recorderStarted;

	/**
	 * Start recording to the currently selected wave file
	 */
	private void startRecorder() {
		recorderStarted = false;
		try {
			File file = new File(recorderFile.getText());
			if (recorder == null) {
				recorder = new DiskWriterSink();
			}
			// open the recorder
			recorder.open(file, format);
			pullThread.setSlaveSink(recorder);
			recorderStarted = true;
			recorderFile.setEnabled(false);
			if (showPosThread != null) {
				showPosThread.ping();
			}
			status("Wave recording started.");
		} catch (Exception e) {
			if (recorder != null) {
				recorder.close();
			}
			status(e, "Cannot start Recorder:");
		}
		makeRecorderButtons();
	}

	/**
	 * Stop the currently capturing recorder
	 */
	private synchronized void stopRecorder() {
		if (recorder != null) {
			recorder.close();
			pullThread.setSlaveSink(null);
			recorder = null;
			status("Wave recording stopped.");
		}
		recorderStarted = false;
		displayCurrentPositions();
		makeRecorderButtons();
	}

	/**
	 * Enable/disable buttons of the WAVE recorder transport panel
	 */
	private void makeRecorderButtons() {
		recorderStart.setEnabled(!recorderStarted);
		recorderStop.setEnabled(recorderStarted);
		recorderFile.setEnabled(!recorderStarted);
		recorderBrowse.setEnabled(!recorderStarted);
	}

	// inner classes

	private static class InstDesc {
		private int bank;
		private int prog;
		private String name;

		public InstDesc(int bank, int prog, String name) {
			this.bank = bank;
			this.prog = prog;
			this.name = name;
		}

		public String toString() {
			return getInstNumber(bank, prog) + " " + name;
		}

		/**
		 * @return Returns the bank.
		 */
		public int getBank() {
			return bank;
		}

		/**
		 * @return Returns the name.
		 */
		public String getName() {
			return name;
		}

		/**
		 * @return Returns the program.
		 */
		public int getProgram() {
			return prog;
		}
	}

	// dynamic debug switches

	private List<DebugHandler> debugHandlers;

	private void commitProps2DebugFields() {
		if (debugHandlers != null) {
			for (DebugHandler h : debugHandlers) {
				h.setValue(getBoolProperty(h.getPropKey(), h.getValue()));
			}
		}
	}

	private void commitDebugGUI2Props() {
		if (debugHandlers != null) {
			for (DebugHandler h : debugHandlers) {
				setProperty(h.getPropKey(), h.getValue());
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void gatherDebugHandlers() {
		debugHandlers = new ArrayList<DebugHandler>(20);
		for (String sClass : debugClasses) {
			try {
				Class clazz = Class.forName(sClass);
				// get all static fields from this class
				Field[] fields = clazz.getFields();
				for (Field field : fields) {
					if (((field.getModifiers() & Modifier.STATIC) == Modifier.STATIC)
							&& (field.getType() == Boolean.TYPE)
							&& (field.getName().startsWith("DEBUG") || field.getName().startsWith(
									"TRACE"))) {
						// we found a DEBUG or TRACE field!
						try {
							DebugHandler h = new DebugHandler(sClass, field);
							debugHandlers.add(h);
						} catch (Exception e) {
							error(e);
						}
					}
				}
			} catch (Exception e) {
				error(e);
			}
		}
	}

	/**
	 * Creates a panel with all DEBUG fields of the classes listed in the
	 * debugClasses field.
	 * 
	 * @return the panel with checkboxes for each debug flag
	 */
	private JComponent getDebugPanel() {
		JPanel p = new JPanel();
		p.setLayout(new StripeLayout(0, 0, 0, 0, 2));
		if (debugHandlers != null) {
			for (DebugHandler h : debugHandlers) {
				p.add(h.createCheckbox());
			}
		}
		return new JScrollPane(p);
	}

	private static class DebugHandler implements ItemListener {
		/**
		 * The full class name of this field.
		 */
		private String clazz;

		/**
		 * The boolean field that should be set/reset by this checkbox handler
		 */
		private Field field;

		private JCheckBox cb;

		public DebugHandler(String clazz, Field field) {
			this.field = field;
			this.clazz = clazz;
		}

		public JCheckBox createCheckbox() {
			cb = new JCheckBox(toString(), getValue());
			cb.addItemListener(this);
			return cb;
		}

		public void itemStateChanged(ItemEvent e) {
			try {
				if (e.getStateChange() == ItemEvent.DESELECTED) {
					if (DEBUG) {
						debug("Disabling " + toString());
					}
					field.setBoolean(null, false);
				} else if (e.getStateChange() == ItemEvent.SELECTED) {
					if (DEBUG) {
						debug("Enabling " + toString());
					}
					field.setBoolean(null, true);
				}
			} catch (Exception ex) {
				error(ex);
			}
		}

		public boolean getValue() {
			try {
				return field.getBoolean(null);
			} catch (IllegalAccessException iae) {
				debug(iae);
			}
			return false;
		}

		public void setValue(boolean val) {
			try {
				field.setBoolean(null, val);
			} catch (IllegalAccessException iae) {
				debug(iae);
			}
			cb.getModel().setSelected(getValue());
		}

		public String getPropKey() {
			return "Debug." + toString();
		}

		public String toString() {
			int dot = clazz.lastIndexOf(".");
			return clazz.substring(dot + 1) + "." + field.getName();
		}
	}

	private static class MidiIndicatorThread extends Thread {

		private volatile boolean stopped = false;
		private JRadioButton button;
		private int midiEventReceived;

		public MidiIndicatorThread(JRadioButton button) {
			super("MidiIndicatorThread");
			this.button = button;
			start();
		}

		public void close() {
			stopped = true;
			synchronized (this) {
				this.notifyAll();
			}
			try {
				this.join();
			} catch (InterruptedException ie) {
			}
		}

		public synchronized void onMidiEvent() {
			midiEventReceived++;
			if (!button.isSelected()) {
				button.setSelected(true);
				this.notifyAll();
			}
		}

		public void run() {
			int lastmidiEventReceived = midiEventReceived;
			int nextWait = 1000;
			if (DEBUG) {
				debug(this.getName() + " start.");
			}
			while (!stopped) {
				synchronized (this) {
					try {
						this.wait(nextWait);
					} catch (InterruptedException ie) {
					}
					if (lastmidiEventReceived == midiEventReceived) {
						nextWait = 1000;
						button.setSelected(false);
					} else {
						nextWait = 50;
					}
					lastmidiEventReceived = midiEventReceived;
				}
			}
			if (DEBUG) {
				debug(this.getName() + " stop.");
			}
		}
	}

	private class ShowPosThread extends Thread {

		private volatile boolean stopped = false;

		public ShowPosThread() {
			super("ShowPositionThread");
			start();
		}

		public void close() {
			stopped = true;
			ping();
			try {
				this.join();
			} catch (InterruptedException ie) {
			}
		}

		public synchronized void ping() {
			this.notifyAll();
		}

		public void run() {
			int nextWait = 1000;
			if (DEBUG) {
				debug(this.getName() + " start.");
			}
			while (!stopped) {
				synchronized (this) {
					try {
						this.wait(nextWait);
					} catch (InterruptedException ie) {
					}
					if (midiPlayerStarted || recorderStarted) {
						nextWait = 100;
						displayCurrentPositions();
					} else {
						nextWait = 1000;
					}
				}
			}
			if (DEBUG) {
				debug(this.getName() + " stop.");
			}
		}
	}
}
