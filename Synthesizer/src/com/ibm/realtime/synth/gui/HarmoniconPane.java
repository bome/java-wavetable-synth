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

import static com.ibm.realtime.synth.engine.MidiChannel.*;
import static com.ibm.realtime.synth.utils.Debug.*;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Mixer;

import org.eclipse.swt.*;
import org.eclipse.swt.events.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import com.ibm.realtime.synth.engine.*;
import com.ibm.realtime.synth.modules.*;
import com.ibm.realtime.synth.soundfont2.SoundFontSoundbank;
import com.ibm.realtime.synth.utils.*;
import com.ibm.realtime.synth.test.*;

// TODO: occasional "already disposed" exception when closing while playing a MIDI file

/*
 * TODO for the GUI:
 * <ul>
 * <li>display free/total memory
 * <li>put all DEBUG_* flag handling in a utility class so that it can be used 
 *     from the command line synth
 * <li>MIDI playback: tempo slider and display
 * <li>time display for audio device/MIDI device
 * <li>Reset controllers button (also for console synth)
 * <li>Synth status as text area:<br>
 *     performance indicator, CPU/memory usage, polyphony
 * <li>smart loading of startup soundbank
 * <li>parameters for main() -- with a SynthTestCommon class or so in the test
 * package
 * <li>mouse keyboard?
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
 * <li>status bar is too small
 * <li>change render thread count
 * <li>activity LED for MIDI file playback
 * <li>add memory page
 * <li>Master Volume slider/Mute
 * <li>MIDI playback: position slider, bars|beats display, fwd/rew buttons
 * <li>Memory: display objects/second, retention count in megabytes, call it "Memory Retention"
 * <li>Added DirectMidiIn support
 * <li>display realtime thread status
 * <li>added -play -duration -trace parameters
 * 
 * @author florian
 */
public class HarmoniconPane implements SynthesizerListener, SMFMidiInListener {

	public static boolean DEBUG = false;

	private static final double DEFAULT_LATENCY_MILLIS = 50.0;
	private static double DEFAULT_SLICETIME_MILLIS = 1.0;

	/**
	 * How many MIDI devices can be opened simultaneously
	 */
	private static final int MIDI_DEV_COUNT = 2;

	// range of controllers
	private static final int RANGE_POS = 0; // 0..127
	private static final int RANGE_CENTER = 1; // -64..+63
	private static final int RANGE_EXT_POS = 2; // 0..128*128-1
	private static final int RANGE_EXT_CENTER = 3; // -128*64....128*64-1
	private static final int RANGE_SWITCH = 4;
	private static final int[][] RANGES = new int[][] {
			new int[] {
					0, 127
			}, new int[] {
					-64, 63
			}, new int[] {
					0, 128 * 128 - 1
			}, new int[] {
					-128 * 64, 128 * 64 - 1
			}
	};
	private static final int MASTER_VOLUME_SLIDER_OFFSET = 100;

	private static final String PROPERTIES_FILE_SUFFIX = ".properties";

	// classes that have a local CLASS_DEBUG field
	private static final String[] debugClasses = {
			"com.ibm.realtime.synth.utils.Debug",
			"com.ibm.realtime.synth.gui.HarmoniconPane",
			"com.ibm.realtime.synth.engine.Synthesizer",
			"com.ibm.realtime.synth.engine.NoteInput",
			"com.ibm.realtime.synth.engine.AudioMixer",
			"com.ibm.realtime.synth.engine.AudioPullThread",
			"com.ibm.realtime.synth.engine.MaintenanceThread",
			"com.ibm.realtime.synth.engine.MidiChannel",
			"com.ibm.realtime.synth.engine.Oscillator",
			"com.ibm.realtime.synth.engine.ThreadFactory",
			"com.ibm.realtime.synth.test.MemoryAllocator",
			"com.ibm.realtime.synth.modules.JavaSoundMidiIn",
			"com.ibm.realtime.synth.modules.JavaSoundReceiver",
			"com.ibm.realtime.synth.modules.JavaSoundSink",
			"com.ibm.realtime.synth.modules.DirectAudioSink",
			"com.ibm.realtime.synth.modules.DirectMidiIn",
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
			0.1, 0.15, 0.2, 0.25, 0.3, 0.5, 0.73, 0.8, 0.9, 1, 1.3, 1.46, 1.7, 2,
			2.5, 3, 3.5, 4, 5, 6, 7, 8, 9, 10, 12, 15, 17, 20, 25, 30, 40, 50,
			60, 70, 80, 90, 100
	};

	public static String[] THREAD_COUNTS = {
			"(default)", "(use mix thread)", "1", "2", "3", "4", "5", "6", "7",
			"8", "9", "10", "12", "14", "16", "18", "20", "30",
	};

	/**
	 * The name of this synth
	 */
	private String name;

	// TOP LEVEL
	private TabFolder tabFolder;

	// SYNTH TAB
	private TabItem synthTab;
	private Text sbField;
	private Label sbNameLabel;
	private Text playerFile;
	private Button playerStart;
	private Button playerPause;
	private Button playerStop;
	private Button playerForward;
	private Button playerRewind;
	private Button playerBeginning;
	private Label playerPos;
	private Slider playerSlider;
	private Label playerBarPosField;
	private Text recorderFile;
	private Button recorderBrowse;
	private Button recorderStart;
	private Button recorderStop;
	private Label recorderPos;
	private Button muteButton;
	private Slider masterVolumeSlider;
	private Label masterVolumeField;
	private Label statusLabel;

	// SETUP TAB
	private TabItem setupTab;
	private Combo[] midiInLists = new Combo[MIDI_DEV_COUNT];
	// The extra one is for the midi file.
	private Button[] midiInIndicators = new Button[MIDI_DEV_COUNT + 1];
	private int[] midiEventReceived = new int[MIDI_DEV_COUNT + 1];
	private long[] whenLastMidiEventReceived = new long[MIDI_DEV_COUNT + 1]; // System.currentTimeMillis()

	private Combo audioOutList;
	private Text tuningField;
	private Combo renderThreadCountList;
	private Text renderThreadCountField;
	private Combo bufferSizeList;
	private Text bufferSizeField;
	private Combo noteDispatcherCombo;
	private Label noteDispatcherStatus;

	// CONTROLLER TAB
	private Combo channelList;
	private Combo instList;
	private Text bankField;
	private Combo ctrlList;
	private Text ctrlField;
	private Slider ctrlSlider;

	// memory page
	private static final String MEMORY_TAB_TEXT = "Memory";
	private TabItem memoryTab;
	private Button allocEnable;
	private Slider allocRateSlider;
	private Label allocRateField;
	private Slider allocSizeSlider;
	private Label allocSizeField;
	private Slider allocRetentionSlider;
	private Label allocRetentionField;
	private int ALLOC_RATE_COUNT = 25;
	private int[] ALLOC_RATES;
	private int ALLOC_SIZE_COUNT = 30;
	private int[] ALLOC_SIZES;
	private int ALLOC_RETENTION_COUNT = 15;
	private int[] ALLOC_RETENTIONS;
	private Button useNewBuffersButton;
	private Label allocStatusField1;
	private Label allocStatusField2;
	private Label allocStatusField3;

	// synth engine objects
	private double sampleRate;
	private Synthesizer synth;
	private Soundbank soundbank;
	private InstDesc[] allInstruments;
	private AudioMixer mixer;
	private JavaSoundSink jsSink;
	private DirectAudioSink daSink;
	private AudioSink sink; // the currently open sink
	private int directAudioDevCount = 0;

	private MidiIn[] midis = new MidiIn[MIDI_DEV_COUNT];
	private int directMidiDevCount = 0;

	private AudioPullThread pullThread;
	private MaintenanceThread maintenance;
	private SMFMidiIn player;
	private AudioFormat format;
	private DiskWriterSink recorder = null;

	private MemoryAllocator memAllocator;
	private Refresher refresher;
	private int noUpdate = 0;
	private Properties props;
	private boolean closed = false;
	private boolean autoPlay = false;
	private int timeOutInSeconds = 0;
	private CloseListener closeListener;

	public HarmoniconPane(String name, boolean trace) {
		super();
		this.name = name;

		construct();
	}

	/**
	 * Create an instance of the SoundFontGUI pane and load the persistence
	 * properties.
	 * 
	 * @param name the title of this pane
	 */
	public HarmoniconPane(String name, String[] args) {
		super();
		this.name = name;

		parseArguments(args);

		construct();
	}

	private void construct() {
		// currently, the following values cannot be changed
		sampleRate = 44100.0;
		format = new AudioFormat((float) sampleRate, 16, 2, true, false);

		// the list of allocation rates
		ALLOC_RATES = new int[ALLOC_RATE_COUNT];
		int value = 16;
		for (int i = 0; i < ALLOC_RATE_COUNT; i++) {
			ALLOC_RATES[i] = value;
			value = value << 1;
		}
		// the list of allocation sizes
		ALLOC_SIZES = new int[ALLOC_SIZE_COUNT];
		value = 8;
		for (int i = 0; i < 8; i++) {
			ALLOC_SIZES[i] = value;
			value += 8;
		}
		for (int i = 8; i < ALLOC_SIZE_COUNT; i++) {
			ALLOC_SIZES[i] = value;
			value = value << 1;
		}
		// the list of number of objects
		ALLOC_RETENTIONS = new int[ALLOC_RETENTION_COUNT];
		ALLOC_RETENTIONS[0] = 0;
		value = 1;
		for (int i = 1; i < ALLOC_RETENTION_COUNT; i++) {
			ALLOC_RETENTIONS[i] = value;
			value = value << 1;
		}
		Synthesizer.ASYNCH_RENDER_STREAM_THRESHOLD = 0;
		if (Synthesizer.DEBUG_SYNTH) {
			debug("If multiple render threads used, they are used without threshold."
					+ " (ASYNCH_RENDER_STREAM_THRESHOLD=0)");
		}
	}

	protected String openFile(Shell shell, String path, String filterName,
			String extension) {
		FileDialog dialog = new FileDialog(shell, SWT.OPEN);
		dialog.setFilterNames(new String[] {
				filterName, "All Files (*.*)"
		});
		dialog.setFilterExtensions(new String[] {
				"*." + extension, "*.*"
		});
		dialog.setFilterPath(path);
		dialog.setFileName("");
		return dialog.open();
	}

	public interface IFileOpenButtonAction {
		public void open(String filename);
	}

	public interface IButtonAction {
		public void activate();
	}

	public interface IToggleAction {
		public void toggle(boolean on);
	}

	public Button checkBox(Composite group, String name,
			final IToggleAction action) {
		final Button button = new Button(group, SWT.CHECK);
		button.setText(name);
		button.addSelectionListener(new SelectionListener() {
			public void widgetSelected(SelectionEvent e) {
				action.toggle(button.getSelection());
			}

			public void widgetDefaultSelected(SelectionEvent e) {
				widgetSelected(e);
			}
		});
		return button;
	}

	public Button createToggleButton(Composite group, String name,
			final IToggleAction action) {
		final Button button = new Button(group, SWT.TOGGLE);
		button.setText(name);
		button.addSelectionListener(new SelectionListener() {
			public void widgetSelected(SelectionEvent e) {
				action.toggle(button.getSelection());
			}

			public void widgetDefaultSelected(SelectionEvent e) {
				widgetSelected(e);
			}
		});
		return button;
	}

	public Button actionButton(Composite group, String name,
			final IButtonAction action) {
		Button button = new Button(group, SWT.PUSH);
		button.setText(name);

		button.addSelectionListener(new SelectionListener() {
			public void widgetSelected(SelectionEvent e) {
				action.activate();
			}

			public void widgetDefaultSelected(SelectionEvent e) {
				widgetSelected(e);
			}
		});
		return button;
	}

	private Slider createSlider(Composite parent, int min, int max, int inc,
			int pageInc) {
		return createSlider(parent, min, max, inc, pageInc, 1);
	}

	private Slider createSlider(Composite parent, int min, int max, int inc,
			int pageInc, int thumbSize) {
		Slider slider = new Slider(parent, SWT.BORDER);
		slider.setMinimum(min);
		slider.setMaximum(max + thumbSize);
		slider.setIncrement(inc);
		slider.setPageIncrement(pageInc);
		slider.setThumb(thumbSize);
		return slider;
	}

	public Button fileOpenButton(Composite group, final Text fileLabel,
			final String filterType, final String ext,
			final IFileOpenButtonAction action) {

		final Shell shell = group.getShell();
		Button browse = new Button(group, SWT.PUSH);
		browse.setText("Load");

		browse.addSelectionListener(new SelectionListener() {
			public void widgetSelected(SelectionEvent e) {
				String curFilename = fileLabel.getText();
				File curFile = new File(curFilename);
				String path = curFile.getParent();
				String fileName = openFile(shell, path, filterType, ext);
				if (fileName != null) {
					action.open(fileName);
				}
			}

			public void widgetDefaultSelected(SelectionEvent e) {
				widgetSelected(e);
			}
		});
		return browse;
	}

	private void setHorizontalFill(Control ctrl) {
		GridData gridData = new GridData();
		gridData.horizontalAlignment = GridData.FILL;
		gridData.grabExcessHorizontalSpace = true;
		ctrl.setLayoutData(gridData);
	}

	private void setGridMinimumWidth(Control ctrl, int minWidth) {
		GridData gridData = new GridData();
		gridData.horizontalAlignment = SWT.BEGINNING;
		gridData.minimumWidth = minWidth;
		// gridData.grabExcessHorizontalSpace = true;
		gridData.widthHint = minWidth;
		ctrl.setLayoutData(gridData);
	}

	private void setGridFill(Control ctrl) {
		ctrl.setLayoutData(new GridData(GridData.FILL_BOTH));
	}

	private Composite createLayoutPanel(Composite parent) {
		Composite ret = new Composite(parent, SWT.NONE);
		return ret;
	}

	private Layout createNoSpaceGridLayout(int columns) {
		GridLayout ret = new GridLayout();
		ret.numColumns = columns;
		ret.marginBottom = 0;
		ret.marginTop = 0;
		ret.marginLeft = 0;
		ret.marginRight = 0;
		ret.marginHeight = 0;
		ret.marginWidth = 0;
		return ret;
	}

	public void createPartControl(final Composite parent) {

		final HarmoniconPane pane = this;
		GridLayout grid5 = new GridLayout();
		grid5.numColumns = 5;
		GridLayout grid4 = new GridLayout();
		grid4.numColumns = 4;
		GridLayout grid3 = new GridLayout();
		grid3.numColumns = 3;
		GridLayout grid2 = new GridLayout();
		grid2.numColumns = 2;
		GridLayout grid1 = new GridLayout();
		grid1.numColumns = 1;

		// ---------- Top level GUI widgets -------------
		parent.setLayout(grid1);
		// tab folder
		tabFolder = new TabFolder(parent, SWT.BORDER);
		setGridFill(tabFolder);
		// status field
		Composite statusComposite = createLayoutPanel(parent);
		GridLayout statusGrid = (GridLayout) createNoSpaceGridLayout(2);
		statusGrid.marginLeft = grid2.marginLeft;
		statusGrid.marginRight = grid2.marginRight;
		statusComposite.setLayout(statusGrid);
		setHorizontalFill(statusComposite);
		statusLabel = new Label(statusComposite, SWT.NONE);
		setHorizontalFill(statusLabel);
		// panic button
		actionButton(statusComposite, "Panic!", new IButtonAction() {
			public void activate() {
				status("Resetting synth...");
				synth.reset();
				displayChannel();
				status("Synth reset.");
			}
		});

		// ---------- Synth Tab -------------
		String fieldDefaultText = "__________";

		tabFolder.setLayout(new FillLayout());
		synthTab = new TabItem(tabFolder, SWT.NONE);
		synthTab.setText("Synth");
		Composite synthGroup = createLayoutPanel(tabFolder);
		synthGroup.setLayout(grid1);
		synthTab.setControl(synthGroup);

		// soundbank
		Group sbGroup = new Group(synthGroup, SWT.NONE);
		setHorizontalFill(sbGroup);
		sbGroup.setText("Soundbank");
		sbGroup.setLayout(grid3);
		Group sbfileGroup = sbGroup;
		new Label(sbfileGroup, SWT.NONE).setText("File:");
		sbField = new Text(sbfileGroup, SWT.BORDER | SWT.READ_ONLY);
		setHorizontalFill(sbField);
		fileOpenButton(sbfileGroup, sbField, "SoundFont2", "sf2",
				new IFileOpenButtonAction() {
					public void open(String filename) {
						sbField.setText(filename);
						pane.loadSoundbank();
					}
				});
		Group sbnameGroup = sbGroup;
		new Label(sbnameGroup, SWT.NONE).setText("Loaded Soundbank:");
		sbNameLabel = new Label(sbnameGroup, SWT.BORDER);
		sbNameLabel.setText("(none)");
		setHorizontalFill(sbNameLabel);

		// MIDI File name and Transport Control
		Group playbackGroup = new Group(synthGroup, SWT.NONE);
		setHorizontalFill(playbackGroup);
		playbackGroup.setText("MIDI File Playback");
		playbackGroup.setLayout(grid1);
		Composite playbackFileGroup = createLayoutPanel(playbackGroup);
		setHorizontalFill(playbackFileGroup);
		playbackFileGroup.setLayout(createNoSpaceGridLayout(4));
		new Label(playbackFileGroup, SWT.NONE).setText("File: ");
		playerFile = new Text(playbackFileGroup, SWT.BORDER | SWT.READ_ONLY);
		setHorizontalFill(playerFile);
		fileOpenButton(playbackFileGroup, playerFile, "MIDI File", "mid",
				new IFileOpenButtonAction() {
					public void open(String filename) {
						playerFile.setText(filename);
						if (!midiPlayerStarted) {
							loadMidiFile();
						}
					}
				});
		playerPos = new Label(playbackFileGroup, SWT.BORDER | SWT.RIGHT);
		playerPos.setText(fieldDefaultText);

		Composite playbackTransportGroup = createLayoutPanel(playbackGroup);
		setHorizontalFill(playbackTransportGroup);
		playbackTransportGroup.setLayout(createNoSpaceGridLayout(3));
		Composite playbackButtonsGroup = createLayoutPanel(playbackTransportGroup);
		RowLayout playbackButtonsLayout = new RowLayout();
		playbackButtonsLayout.marginLeft = 0;
		playbackButtonsLayout.marginRight = 0;
		playbackButtonsLayout.marginBottom = 0;
		playbackButtonsLayout.marginTop = 0;
		playbackButtonsGroup.setLayout(playbackButtonsLayout);
		playerRewind = actionButton(playbackButtonsGroup, " << ",
				new IButtonAction() {
					public void activate() {
						rewindPlayer();
					}
				});
		playerStart = actionButton(playbackButtonsGroup, "Play",
				new IButtonAction() {
					public void activate() {
						startPlayer();
					}
				});
		playerForward = actionButton(playbackButtonsGroup, " >> ",
				new IButtonAction() {
					public void activate() {
						forwardPlayer();
					}
				});
		playerPause = actionButton(playbackButtonsGroup, "Pause",
				new IButtonAction() {
					public void activate() {
						pausePlayer();
					}
				});
		playerStop = actionButton(playbackButtonsGroup, "Stop",
				new IButtonAction() {
					public void activate() {
						stopPlayer();
					}
				});
		playerBeginning = actionButton(playbackButtonsGroup, " 0 ",
				new IButtonAction() {
					public void activate() {
						beginningPlayer();
					}
				});
		playerSlider = createSlider(playbackTransportGroup, 0, 100, 1, 5, 10);
		SelectionListener playerSliderListener = new SelectionListener() {
			public void widgetSelected(SelectionEvent e) {
				if (noUpdate == 0) sendPlaybackSliderPosition();
			}

			public void widgetDefaultSelected(SelectionEvent e) {
				widgetSelected(e);
			}
		};
		playerSlider.addSelectionListener(playerSliderListener);
		setHorizontalFill(playerSlider);
		playerBarPosField = new Label(playbackTransportGroup, SWT.BORDER
				| SWT.RIGHT);
		playerBarPosField.setText(fieldDefaultText);

		// Wave Recording
		Group wavGroup = new Group(synthGroup, SWT.NONE);
		setHorizontalFill(wavGroup);
		wavGroup.setLayout(grid5);
		wavGroup.setText("Wave Record");
		recorderFile = new Text(wavGroup, SWT.BORDER | SWT.READ_ONLY);
		setHorizontalFill(recorderFile);
		recorderBrowse = fileOpenButton(wavGroup, recorderFile, "Wave File",
				"wav", new IFileOpenButtonAction() {
					public void open(String filename) {
						recorderFile.setText(filename);
					}
				});
		recorderBrowse.setText("Browse");
		recorderStart = actionButton(wavGroup, "Start", new IButtonAction() {
			public void activate() {
				startRecorder();
			}
		});
		recorderStop = actionButton(wavGroup, "Stop", new IButtonAction() {
			public void activate() {
				stopRecorder();
			}
		});
		recorderPos = new Label(wavGroup, SWT.BORDER | SWT.RIGHT);
		recorderPos.setText(fieldDefaultText);

		// master volume
		Group masterVolumeGroup = new Group(synthGroup, SWT.NONE);
		masterVolumeGroup.setText("Master Synth Volume");
		setHorizontalFill(masterVolumeGroup);
		masterVolumeGroup.setLayout(grid3);

		muteButton = createToggleButton(masterVolumeGroup, "Mute",
				new IToggleAction() {
					public void toggle(boolean on) {
						if (noUpdate == 0) sendMasterVolume();
					}
				});
		masterVolumeSlider = createSlider(masterVolumeGroup, 0, 150, 1, 5, 10);
		SelectionListener masterVolumeListener = new SelectionListener() {
			public void widgetSelected(SelectionEvent e) {
				if (noUpdate == 0) sendMasterVolume();
			}

			public void widgetDefaultSelected(SelectionEvent e) {
				widgetSelected(e);
			}
		};
		masterVolumeSlider.addSelectionListener(masterVolumeListener);

		setHorizontalFill(masterVolumeSlider);
		masterVolumeField = new Label(masterVolumeGroup, SWT.BORDER | SWT.RIGHT);
		masterVolumeField.setText(fieldDefaultText);

		// ---------- Controllers Tab -------------

		TabItem ctrlTab = new TabItem(tabFolder, SWT.NONE);
		ctrlTab.setText("Controllers");
		Composite ctrlGroup = createLayoutPanel(tabFolder);
		ctrlGroup.setLayout(grid1);
		ctrlTab.setControl(ctrlGroup);

		// Channel selection
		Group controlGroup = new Group(ctrlGroup, SWT.NONE);
		setHorizontalFill(controlGroup);
		controlGroup.setLayout(grid2);
		controlGroup.setText("Controllers");
		new Label(controlGroup, SWT.NONE).setText("Channel:");
		channelList = new Combo(controlGroup, SWT.BORDER | SWT.READ_ONLY);
		setHorizontalFill(channelList);
		new Label(controlGroup, SWT.NONE).setText("Instrument:");
		instList = new Combo(controlGroup, SWT.BORDER | SWT.READ_ONLY);
		setHorizontalFill(instList);
		new Label(controlGroup, SWT.NONE).setText("Bank:Prog:");
		bankField = new Text(controlGroup, SWT.BORDER | SWT.READ_ONLY);
		setHorizontalFill(bankField);
		// controller inspector
		ctrlList = new Combo(controlGroup, SWT.BORDER | SWT.READ_ONLY);
		setHorizontalFill(ctrlList);
		ctrlSlider = createSlider(controlGroup, RANGES[RANGE_EXT_POS][0],
				RANGES[RANGE_EXT_POS][1], 1, 128, 1500);
		setHorizontalFill(ctrlSlider);
		new Label(controlGroup, SWT.NONE);
		ctrlField = new Text(controlGroup, SWT.BORDER | SWT.READ_ONLY);
		setHorizontalFill(ctrlField);
		channelList.addSelectionListener(new SelectionListener() {
			public void widgetSelected(SelectionEvent e) {
				displayChannel();
			}

			public void widgetDefaultSelected(SelectionEvent e) {
				widgetSelected(e);
			}
		});
		ctrlList.addSelectionListener(new SelectionListener() {
			public void widgetSelected(SelectionEvent e) {
				displayCtrl();
			}

			public void widgetDefaultSelected(SelectionEvent e) {
				widgetSelected(e);
			}
		});
		ctrlSlider.addSelectionListener(new SelectionListener() {
			public void widgetSelected(SelectionEvent e) {
				if (noUpdate == 0) sendController();
			}

			public void widgetDefaultSelected(SelectionEvent e) {
				widgetSelected(e);
			}
		});
		instList.addSelectionListener(new SelectionListener() {
			public void widgetSelected(SelectionEvent e) {
				sendInstrument();
			}

			public void widgetDefaultSelected(SelectionEvent e) {
				widgetSelected(e);
			}
		});

		// --------------------- Setup Tab ------------------------

		setupTab = new TabItem(tabFolder, SWT.NONE);
		setupTab.setText("Setup");
		Composite setupGroup = createLayoutPanel(tabFolder);
		setupGroup.setLayout(grid1);
		setupTab.setControl(setupGroup);

		// MIDI input
		Group inRegionGroup = new Group(setupGroup, SWT.NONE);
		setHorizontalFill(inRegionGroup);
		inRegionGroup.setLayout(grid3);
		inRegionGroup.setText("Input Settings");
		for (int i = 0; i < 1 + midiInLists.length; i++) {
			boolean isRealDevice = i < midiInLists.length;
			Label lab = new Label(inRegionGroup, SWT.NONE);
			lab.setText(isRealDevice ? ("MIDI IN " + (i + 1) + ":")
					: "MIDI File");
			if (isRealDevice) {
				midiInLists[i] = new Combo(inRegionGroup, SWT.BORDER
						| SWT.READ_ONLY);
				setHorizontalFill(midiInLists[i]);
			} else {
				new Label(inRegionGroup, SWT.NONE); // gap
			}
			midiInIndicators[i] = new Button(inRegionGroup, SWT.RADIO);
			midiInIndicators[i].setEnabled(false);
			if (isRealDevice) {
				final int midiInIndex = i;
				midiInLists[i].addSelectionListener(new SelectionListener() {
					public void widgetSelected(SelectionEvent e) {
						startMIDIDevice(midiInIndex);
					}

					public void widgetDefaultSelected(SelectionEvent e) {
						widgetSelected(e);
					}
				});
			}
		}

		// audio output
		Group outRegionGroup = new Group(setupGroup, SWT.NONE);
		setHorizontalFill(outRegionGroup);
		outRegionGroup.setLayout(grid2);
		outRegionGroup.setText("Output Settings");
		new Label(outRegionGroup, SWT.NONE).setText("Audio Out:");
		audioOutList = new Combo(outRegionGroup, SWT.BORDER | SWT.READ_ONLY);
		setHorizontalFill(audioOutList);

		new Label(outRegionGroup, SWT.NONE).setText("Buffer Size: ");
		Composite bufferSizeComposite = createLayoutPanel(outRegionGroup);
		setHorizontalFill(bufferSizeComposite);
		bufferSizeComposite.setLayout(createNoSpaceGridLayout(2));
		bufferSizeList = new Combo(bufferSizeComposite, SWT.BORDER
				| SWT.READ_ONLY);
		setGridMinimumWidth(bufferSizeList, 50);
		bufferSizeField = new Text(bufferSizeComposite, SWT.BORDER
				| SWT.READ_ONLY);
		setHorizontalFill(bufferSizeField);

		SelectionListener audioDeviceListener = new SelectionListener() {
			public void widgetSelected(SelectionEvent e) {
				// need to restart the audio device in order to change the
				// buffer size...
				startAudioDevice();
			}

			public void widgetDefaultSelected(SelectionEvent e) {
				widgetSelected(e);
			}
		};
		audioOutList.addSelectionListener(audioDeviceListener);
		bufferSizeList.addSelectionListener(audioDeviceListener);

		// Engine Properties
		Group engineRegionGroup = new Group(setupGroup, SWT.NONE);
		setHorizontalFill(engineRegionGroup);
		engineRegionGroup.setLayout(grid2);
		engineRegionGroup.setText("Engine Settings");
		new Label(engineRegionGroup, SWT.NONE).setText("Render Thread Count:");
		Composite c = createLayoutPanel(engineRegionGroup);
		setHorizontalFill(c);
		c.setLayout(new FillLayout());
		renderThreadCountList = new Combo(c, SWT.BORDER | SWT.READ_ONLY);
		renderThreadCountField = new Text(c, SWT.BORDER | SWT.READ_ONLY);

		new Label(engineRegionGroup, SWT.NONE).setText("Global Tuning (Hz): ");
		tuningField = new Text(engineRegionGroup, SWT.BORDER);
		setHorizontalFill(tuningField);
		tuningField.addModifyListener(new ModifyListener() {
			public void modifyText(ModifyEvent e) {
				if (noUpdate == 0) {
					sendTuning();
				}
			}
		});
		SelectionListener renderThreadCountListener = new SelectionListener() {
			public void widgetSelected(SelectionEvent e) {
				sendRenderThreadCount();
			}

			public void widgetDefaultSelected(SelectionEvent e) {
				widgetSelected(e);
			}
		};
		renderThreadCountList.addSelectionListener(renderThreadCountListener);

		// asynchronous note dispatcher?
		new Label(engineRegionGroup, SWT.NONE).setText("Note Dispatcher: ");
		c = createLayoutPanel(engineRegionGroup);
		setHorizontalFill(c);
		c.setLayout(createNoSpaceGridLayout(2));
		noteDispatcherCombo = new Combo(c, SWT.BORDER | SWT.READ_ONLY);
		noteDispatcherCombo.setItems(new String[] {
				"Synchronous", "Request asynchronous (*)", "Force asynchronous"
		});
		// default value
		noteDispatcherCombo.select(1);
		SelectionListener noteDispatcherListener = new SelectionListener() {
			public void widgetSelected(SelectionEvent e) {
				if (noUpdate == 0) {
					sendNoteDispatcher();
				}
			}

			public void widgetDefaultSelected(SelectionEvent e) {
				widgetSelected(e);
			}
		};
		noteDispatcherCombo.addSelectionListener(noteDispatcherListener);
		noteDispatcherStatus = new Label(c, SWT.BORDER);
		setHorizontalFill(noteDispatcherStatus);

		// -------------------------- MEMORY TAB --------------------------

		fieldDefaultText = "________________";
		memoryTab = new TabItem(tabFolder, SWT.NONE);
		memoryTab.setText(MEMORY_TAB_TEXT);
		Composite memoryGroup = createLayoutPanel(tabFolder);
		memoryGroup.setLayout(grid1);
		memoryTab.setControl(memoryGroup);

		Group allocMainGroup = new Group(memoryGroup, SWT.NONE);
		allocMainGroup.setText("Allocate Memory");
		setHorizontalFill(allocMainGroup);
		allocMainGroup.setLayout(grid1);
		allocEnable = checkBox(allocMainGroup, "Enable Memory Allocation",
				new IToggleAction() {
					public void toggle(boolean on) {
						if (noUpdate == 0) {
							memAllocator.setEnabled(on);
							updateMemoryTabText();
						}
					}
				});
		Composite allocGroup = createLayoutPanel(allocMainGroup);
		setHorizontalFill(allocGroup);
		allocGroup.setLayout(createNoSpaceGridLayout(3));

		new Label(allocGroup, SWT.NONE).setText("Allocation Rate:");
		allocRateSlider = createSlider(allocGroup, 0, ALLOC_RATE_COUNT - 1, 1,
				5);
		setHorizontalFill(allocRateSlider);
		allocRateField = new Label(allocGroup, SWT.BORDER);
		allocRateField.setText(fieldDefaultText);

		new Label(allocGroup, SWT.NONE).setText("Allocation Object Size:");
		allocSizeSlider = createSlider(allocGroup, 0, ALLOC_SIZE_COUNT - 1, 1,
				5);
		setHorizontalFill(allocSizeSlider);
		allocSizeField = new Label(allocGroup, SWT.BORDER);
		allocSizeField.setText(fieldDefaultText);

		new Label(allocGroup, SWT.NONE).setText("Memory Retention:");
		allocRetentionSlider = createSlider(allocGroup, 0,
				ALLOC_RETENTION_COUNT - 1, 1, 5);
		setHorizontalFill(allocRetentionSlider);
		allocRetentionField = new Label(allocGroup, SWT.BORDER);
		allocRetentionField.setText(fieldDefaultText);

		SelectionListener allocListener = new SelectionListener() {
			public void widgetSelected(SelectionEvent e) {
				if (noUpdate == 0) sendAllocator();
			}

			public void widgetDefaultSelected(SelectionEvent e) {
				widgetSelected(e);
			}
		};
		allocRateSlider.addSelectionListener(allocListener);
		allocSizeSlider.addSelectionListener(allocListener);
		allocRetentionSlider.addSelectionListener(allocListener);

		Group newBuffersGroup = new Group(memoryGroup, SWT.NONE);
		newBuffersGroup.setText("Engine Buffer Handling");
		setHorizontalFill(newBuffersGroup);
		newBuffersGroup.setLayout(grid1);

		useNewBuffersButton = checkBox(
				newBuffersGroup,
				"For every audio slice, use a new audio buffer object (default: off)",
				new IToggleAction() {
					public void toggle(boolean on) {
						if (noUpdate == 0) {
							sendNewBuffersSelection();
						}
					}
				});
		// status line
		Composite allocStatusGroup = createLayoutPanel(allocMainGroup);
		setHorizontalFill(allocStatusGroup);
		allocStatusGroup.setLayout(new FillLayout());
		allocStatusField1 = new Label(allocStatusGroup, SWT.NONE);
		allocStatusField2 = new Label(allocStatusGroup, SWT.NONE);
		allocStatusField3 = new Label(allocStatusGroup, SWT.NONE);

		// ---------------- Debug Tab -------------------
		TabItem debugTab = new TabItem(tabFolder, SWT.NONE);
		debugTab.setText("Debug");
		Composite debugGroup = createLayoutPanel(tabFolder);
		debugGroup.setLayout(new RowLayout(SWT.VERTICAL));
		debugTab.setControl(debugGroup);

		gatherDebugHandlers(debugGroup);

		// ---------------- init properties -------------------

		createDefaultProperties();
		loadProperties();
	}

	/**
	 * Create all functional objects for the synth and initialize them. They are
	 * initialized with the last values using the properties.
	 */
	public void init() {
		commitProps2DebugFields();
		noUpdate++;
		try {

			status("Creating Synthesizer...");
			synth = new Synthesizer();
			// prevent useless allocation of render threads if not used (render
			// thread count will be set in applyPropertiesToGUI)
			synth.setRenderThreadCount(0);
			// prevent creation of useless note dispatcher thread if not asynch
			// note dispatcher used. Note dispatcher mode will be set below
			synth.setNoteDispatcherMode(Synthesizer.NOTE_DISPATCHER_SYNCHRONOUS);
			// set an approximate fixed delay so that note dispatcher will not
			// be started unnecessarily
			synth.setFixedDelayNanos(((long) getSelectedLatency()) * 1000000L);
			synth.start();

			status("Gathering MIDI devices...");
			java.util.List<String> directMidiDevs = DirectMidiIn.getDeviceList();
			directMidiDevCount = directMidiDevs.size();

			java.util.List<?> infos = JavaSoundMidiIn.getDeviceList();
			String[] infoArray = new String[directMidiDevCount + infos.size()
					+ 1];
			infoArray[0] = "(none)";
			for (int i = 0; i < directMidiDevCount; i++) {
				infoArray[i + 1] = directMidiDevs.get(i);
			}
			for (int i = 0; i < infos.size(); i++) {
				infoArray[directMidiDevCount + 1 + i] = "Java Sound: "
						+ infos.get(i).toString();
			}
			for (Combo mList : midiInLists) {
				mList.setItems(infoArray);
				mList.setEnabled(directMidiDevCount + infos.size() >= 1);
			}

			status("Gathering audio devices...");
			java.util.List<String> directAudioDevs = DirectAudioSink.getDeviceList();
			directAudioDevCount = directAudioDevs.size();
			java.util.List<Mixer.Info> ainfos = JavaSoundSink.getDeviceList();
			String[] sa = new String[ainfos.size() + directAudioDevCount];
			for (int i = 0; i < directAudioDevCount; i++) {
				sa[i] = directAudioDevs.get(i);
			}
			for (int i = 0; i < ainfos.size(); i++) {
				sa[i + directAudioDevCount] = "Java Sound: "
						+ ainfos.get(i).getName();
			}
			audioOutList.setItems(sa);
			audioOutList.setEnabled(sa.length > 0);

			// create the buffer size list's entries
			String[] bufferSizes = new String[BUFFER_SIZES_MILLIS.length];
			for (int i = 0; i < BUFFER_SIZES_MILLIS.length; i++) {
				bufferSizes[i] = String.valueOf(BUFFER_SIZES_MILLIS[i]);
			}
			bufferSizeList.setItems(bufferSizes);
			bufferSizeList.setEnabled(audioOutList.isEnabled());
			displayEffectiveBufferTime();

			// render thread count
			renderThreadCountList.setItems(THREAD_COUNTS);

			status("Gathering Channels...");
			Object[] channels = synth.getChannels().toArray();
			String[] channelNames = new String[channels.length];
			for (int i = 0; i < channels.length; i++) {
				channelNames[i] = channels[i].toString();
			}
			channelList.setItems(channelNames);

			status("Gathering controllers...");
			String[] ctrls = new String[CONTROLLERS.length];
			for (int i = 0; i < ctrls.length; i++) {
				ctrls[i] = MidiUtils.getControllerName(CONTROLLERS[i]);
			}
			ctrlList.setItems(ctrls);

			status("Creating Mixer...");
			mixer = new AudioMixer();
			synth.setMixer(mixer);
			synth.addListener(this);
			status("Preloading synth...");
			synth.preLoad();

			status("creating sinks...");
			jsSink = new JavaSoundSink();
			daSink = new DirectAudioSink();

			status("creating AudioPullThread...");
			pullThread = new AudioPullThread(mixer, sink);
			DEFAULT_SLICETIME_MILLIS = getIntProperty("sliceTimeMicros",
					(int) (DEFAULT_SLICETIME_MILLIS * 1000.0)) / 1000.0;
			pullThread.setSliceTimeMillis(DEFAULT_SLICETIME_MILLIS);

			status("connecting Synthesizer with AudioPullThread...");
			pullThread.addListener(synth);

			status("Creating player object");
			player = new SMFMidiIn();
			// one after the real MIDI devices
			player.setDeviceIndex(MIDI_DEV_COUNT);
			status("connecting Player to Synthesizer...");
			player.addListener(synth);

			status("creating and connecting maintenance thread...");
			maintenance = new MaintenanceThread();

			maintenance.addServiceable(mixer);
			maintenance.addAdjustableClock(player);

			status("creating memory allocator thread...");
			memAllocator = new MemoryAllocator();

			status("setting up GUI refreshers...");
			refresher = new Refresher();
			refresher.addRefreshListener(new Runnable() {
				private int positionCounter = 0;

				public void run() {
					if (closed) return;

					if (isSynthTabSelected()) {
						displayHighFreqPositions();
						// display the low frequency elements every 10th time
						if (((++positionCounter) % 10) == 0) {
							displayLowFreqPositions();
						}
					} else if (isSetupTabSelected()) {
						for (int i = 0; i < midiInIndicators.length; i++) {
							long now = System.nanoTime() / 1000000L;
							// the midi "LED" is on for 200 millis
							boolean recent = Math.abs(now
									- whenLastMidiEventReceived[i]) < 200;
							midiInIndicators[i].setSelection(recent);
						}
					} else if (isMemoryTabSelected()) {
						displayAllocationStatus();
					}
				}
			});

			applyPropertiesToGUI();
		} finally {
			noUpdate--;
		}
		if (autoPlay) {
			// wait a second to let other GUI activity finish
			timerExec(1000, new Runnable() {
				public void run() {
					startPlayer();
				}
			});
		}
		if (timeOutInSeconds > 0) {
			timerExec(timeOutInSeconds * 1000, new Runnable() {
				public void run() {
					if (closeListener != null) {
						closeListener.closeWindow();
					}
				}
			});
		}
		status("Init done.");
	}

	protected void timerExec(int timeInMillis, Runnable runner) {
		Display.getDefault().timerExec(timeInMillis, runner);
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
		for (int i = 0; i < MIDI_DEV_COUNT; i++) {
			startMIDIDevice(i);
		}

		status("starting AudioPullThread...");
		pullThread.start();
		status("starting maintenance thread...");
		maintenance.start();
		makePlayerButtons();
		displayCurrentPositions();
		makeRecorderButtons();
		status("Ready.");
	}

	/**
	 * Close all devices, i/o, and threads.
	 */
	public synchronized void close() {
		if (closed) return;
		// out("close() start");
		closed = true;
		try {
			saveProperties();

			// clean-up
			if (memAllocator != null) {
				memAllocator.setEnabled(false);
			}
			if (refresher != null) {
				refresher.stop();
			}
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
		} catch (Throwable t) {
			error(t);
		}
		status("close() finished.");
	}

	protected void setAutoPlay(boolean auto) {
		autoPlay = auto;
	}

	protected void setDuration(int timeInSeconds) {
		timeOutInSeconds = timeInSeconds;
	}

	public CloseListener getCloseListener() {
		return closeListener;
	}

	/** set a listener that will be called to close the window */
	public void setCloseListener(CloseListener closeListener) {
		this.closeListener = closeListener;
	}

	public synchronized void waitForCloseToFinish() {
		// debug("waitForCloseToFinish()");
	}

	private String getLoadedSoundbankName() {
		return (soundbank == null) ? "(none)" : soundbank.getName();
	}

	private void loadSoundbank() {
		status("Loading Soundbank...");
		try {
			soundbank = null;
			soundbank = new SoundFontSoundbank(new File(sbField.getText()));
			status("Populating instrument list...");
			java.util.List<Soundbank.Bank> banks = soundbank.getBanks();
			java.util.List<InstDesc> all = new ArrayList<InstDesc>(
					banks.size() * 128);
			for (Soundbank.Bank bank : banks) {
				java.util.List<Soundbank.Instrument> insts = bank.getInstruments();
				for (Soundbank.Instrument inst : insts) {
					all.add(new InstDesc(bank.getMidiNumber(),
							inst.getMidiNumber(), inst.getName()));
				}
			}
			allInstruments = (InstDesc[]) all.toArray(new InstDesc[all.size()]);
			String[] names = new String[allInstruments.length];
			for (int i = 0; i < names.length; i++) {
				names[i] = allInstruments[i].toString();
			}
			instList.setItems(names);
			status("Loaded Soundbank.");
		} catch (FileNotFoundException fnfe) {
			Exception e = fnfe;
			if (e.getMessage() == null || e.getMessage().length() == 0) {
				e = new Exception("file '" + playerFile.getText()
						+ "' not found", e);
			}
			status(e, "Cannot load soundbank:");
		} catch (Throwable t) {
			status(t, "Cannot load soundbank:");
		}
		// make sure that displayChannel is always called!
		displayChannel();
		synth.setSoundbank(soundbank);
		sbNameLabel.setText(getLoadedSoundbankName());
	}

	private void startAudioDevice() {
		if ((sink != null) && sink.isOpen()) {
			status("Closing soundcard...");
			sink.close();
			status("Soundcard closed.");
		}
		int audioIndex = audioOutList.getSelectionIndex();
		if (audioIndex < 0 && audioOutList.getItems().length > 0) {
			audioIndex = 0;
			audioOutList.select(audioIndex);
		}
		if (audioIndex >= 0) {
			double latencyMillis = getSelectedLatency();
			status("Opening soundcard (latency=" + format1(latencyMillis)
					+ "ms)...");
			// open the sink and connect it with the mixer
			try {
				// need to adjust the slice time?
				double sliceTime = DEFAULT_SLICETIME_MILLIS;
				if (latencyMillis < sliceTime) {
					sliceTime = latencyMillis;
				}
				//$$fb let pullThread care about this
				//else {
				//	// if we have a fractional latency above
				//	// DEFAULT_SLICETIME_MILLIS,
				//	// set slice time to be a integral fraction of the latency
				//	if (((int) (latencyMillis * 10.0)) != ((int) latencyMillis)) {
				//		int sliceCount = (int) (latencyMillis / sliceTime);
				//		// sanity
				//		if (sliceCount < 1) sliceCount = 1;
				//		sliceTime = latencyMillis / ((double) sliceCount);
				//	}
				//}
				pullThread.setSliceTimeMillis(sliceTime);
				// align the buffer size to the pull thread's slice time
				int bufferSizeSamples = pullThread.getPreferredSinkBufferSizeSamples(
						latencyMillis, format.getSampleRate());
				if (audioIndex < directAudioDevCount) {
					// open a direct audio device
					// the name of the audio device is derived from the
					// description
					String name = audioOutList.getItem(audioIndex);
					int p = name.indexOf("|");
					name = name.substring(0, p);
					daSink.open(name, format, bufferSizeSamples);
					sink = daSink;
				} else {
					jsSink.open(audioIndex - directAudioDevCount, format,
							bufferSizeSamples);
					sink = jsSink;
				}
				long effectiveLatency = AudioUtils.samples2nanos(
						sink.getBufferSize(), format.getSampleRate());
				// Since the render thread may already have finished rendering
				// when the sink just started playing the last buffer, there is
				// a maximum of 2x buffer size latency in the system
				synth.setFixedDelayNanos(2 * effectiveLatency);

				updateNoteDispatcherStatus();
				status("Soundcard is open.");
			} catch (Exception e) {
				status(e, "Error opening soundcard:");
				noUpdate++;
				try {
					audioOutList.select(-1);
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
		if (midis[index] != null && midis[index].isOpen()) {
			status("Closing MIDI input...");
			try {
				maintenance.removeAdjustableClock(midis[index]);
				midis[index].close();
			} catch (Exception e) {
				error(e);
			}
		}
		midis[index] = null;
		int midiDev = midiInLists[index].getSelectionIndex() - 1;
		if (midiDev >= 0) {
			// make sure that this MIDI device is not already open!
			for (int i = 0; i < MIDI_DEV_COUNT; i++) {
				if ((i != index)
						&& (midiInLists[i].getSelectionIndex() - 1 == midiDev)) {
					showError("This MIDI device is already open!");
					// set to an error number
					midiDev = -2;
					break;
				}
			}
		}
		if (midiDev >= 0) {
			status("Opening MIDI input " + (index + 1) + "...");
			// open the midi device
			try {
				String name = "";
				if (midiDev >= directMidiDevCount) {
					// Java Sound device
					midis[index] = new JavaSoundMidiIn(index);
					((JavaSoundMidiIn) midis[index]).open(midiDev
							- directMidiDevCount);
					name = ((JavaSoundMidiIn) midis[index]).getName();
				} else {
					// Direct MIDI device
					midis[index] = new DirectMidiIn(index);
					// the name of the device is derived from the description
					name = midiInLists[index].getItem(midiDev + 1);
					int p = name.indexOf("|");
					name = name.substring(0, p);
					((DirectMidiIn) midis[index]).open(name);
				}
				status("connecting MidiIn " + (index + 1)
						+ " to Synthesizer...");
				midis[index].addListener(synth);
				maintenance.addAdjustableClock(midis[index]);
				status("MIDI Input " + (index + 1) + " (" + name + ") is open.");
			} catch (Exception e) {
				status(e, "Cannot open MIDI Input " + (index + 1) + ":");
				// set to an error number
				midiDev = -2;
			}
		}
		if (midiDev == -2) {
			/* on error, select (none) */
			noUpdate++;
			try {
				midiInLists[index].select(0);
			} finally {
				noUpdate--;
			}
		}
	}

	private boolean isSynthTabSelected() {
		TabItem[] sel = tabFolder.getSelection();
		return (sel.length > 0 && sel[0] == synthTab);
	}

	private boolean isSetupTabSelected() {
		TabItem[] sel = tabFolder.getSelection();
		return (sel.length > 0 && sel[0] == setupTab);
	}

	private boolean isMemoryTabSelected() {
		TabItem[] sel = tabFolder.getSelection();
		return (sel.length > 0 && sel[0] == memoryTab);
	}

	private void status(String s) {
		if (s == "") s = " ";
		if (statusLabel != null && !statusLabel.isDisposed()) {
			statusLabel.setText(s);
		}
		if (DEBUG) {
			debug(s);
		}
	}

	private void status(Throwable e, String description) {
		if (statusLabel != null) {
			status("Error: " + description + e.getMessage());
		}
		error(e);
		// JOptionPane.showMessageDialog(this, e.getMessage(), description,
		// JOptionPane.ERROR_MESSAGE);

	}

	private void showError(String description) {
		if (statusLabel != null) {
			status("Error: " + description);
		}
		error(description);
		// JOptionPane.showMessageDialog(this, description, "Error",
		// JOptionPane.ERROR_MESSAGE);
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
		try {
			// first update the properties with the current GUI values
			for (int i = 0; i < midiInLists.length; i++) {
				storeListToProperty(midiInLists[i], "midiInDevice" + i);
			}
			storeListToProperty(audioOutList, "audioOutDevice");
			setProperty("soundbank", sbField.getText());
			setProperty("midiFile", playerFile.getText());
			setProperty("recorderFile", recorderFile.getText());
			setProperty("masterTuning",
					(int) (synth.getParams().getMasterTuning() * 10));
			setProperty("channel", getSelectedChannelNumber() + 1);
			setProperty("controller1-1", getSelectedController());
			setProperty("latencyInMicros",
					(int) (getSelectedLatency() * 1000.0));
			storeListToProperty(renderThreadCountList, "renderThreadCount");
			commitDebugGUI2Props();
			setProperty("AllocMemoryEnable", memAllocator.isEnabled());
			storeSliderToProperty(allocRateSlider, "allocRateIndex");
			storeSliderToProperty(allocSizeSlider, "allocSizeIndex");
			storeSliderToProperty(allocRetentionSlider, "allocRetentionIndex");
			setProperty("UseNewAudioBuffers",
					useNewBuffersButton.getSelection());
			setProperty("muteMasterVolume", muteButton.getSelection());
			storeSliderToProperty(masterVolumeSlider, "masterVolume");
			storeListToProperty(noteDispatcherCombo, "noteDispatcherMode");

			File file = getPropertiesFile();
			status("writing properties file: " + file);
			props.store(new FileOutputStream(file), name
					+ " properties: machine generated, do not modify.");
		} catch (Exception e) {
			error(e);
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
			for (int i = 0; i < midiInLists.length; i++) {
				setListByProperty(midiInLists[i], "midiInDevice" + i);
			}
			setListByProperty(audioOutList, "audioOutDevice");
			recorderFile.setText(getStringProperty("recorderFile"));
			// apply selected channel and controller
			ctrlList.select(getCtrlIndexFromController(getIntProperty(
					"controller1-1", 7)));
			// setup the controller slider with the correct range
			displayCtrl();
			channelList.select(getIntProperty("channel", 1) - 1);
			// do not display channel here -- need to have soundbank for that
			synth.getParams().setMasterTuning(
					((double) getIntProperty("masterTuning", 4400)) / 10.0);
			displayTuningValue();
			double latencyMillis = getIntProperty("latencyInMicros",
					(int) (DEFAULT_LATENCY_MILLIS * 1000.0)) / 1000.0;
			displayLatencyIndex(latencyMillis);

			setListByProperty(renderThreadCountList, "renderThreadCount");
			sendRenderThreadCount();
			allocEnable.setSelection(getBoolProperty("AllocMemoryEnable",
					memAllocator.isEnabled()));
			setSliderByProperty(allocRateSlider, "allocRateIndex");
			setSliderByProperty(allocSizeSlider, "allocSizeIndex");
			setSliderByProperty(allocRetentionSlider, "allocRetentionIndex");
			sendAllocator();
			memAllocator.setEnabled(allocEnable.getSelection());
			updateMemoryTabText();
			useNewBuffersButton.setSelection(getBoolProperty(
					"UseNewAudioBuffers", useNewBuffersButton.getSelection()));
			sendNewBuffersSelection();
			muteButton.setSelection(getBoolProperty("muteMasterVolume", false));
			setSliderByProperty(masterVolumeSlider, "masterVolume");
			sendMasterVolume();
			displayAllocationStatus();
			setListByProperty(noteDispatcherCombo, "noteDispatcherMode");
			sendNoteDispatcher();

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
		if (p.equals("true") || p.equals("yes") || p.equals("ja")
				|| p.equals("si")) {
			return true;
		}
		if (p.equals("false") || p.equals("no") || p.equals("nein")
				|| p.equals("definitely not!")) {
			return false;
		}
		return def;
	}

	private static final String[] SOUNDBANK_FILES = {
			"E:\\TestSounds\\sf2\\Chorium.sf2", "~/Sounds/sf2/chorium.sf2",
	};
	private static final String[] MIDI_FILES = {
			"E:\\TestSounds\\mid\\hitbit_dance\\hb_DancingQueen.mid",
			"~/Sounds/mid/hitbit_dance/hb_DancingQueen.mid",
	};

	/**
	 * Create the props object and fill it with a number of non-trivial default
	 * properties.
	 */
	private void createDefaultProperties() {
		props = new Properties();
		for (int i = 0; i < SOUNDBANK_FILES.length; i++) {
			if (new File(SOUNDBANK_FILES[i]).exists()) {
				setProperty("soundbank", SOUNDBANK_FILES[i]);
				break;
			}
		}
		for (int i = 0; i < MIDI_FILES.length; i++) {
			if (new File(MIDI_FILES[i]).exists()) {
				setProperty("midiFile", MIDI_FILES[i]);
				break;
			}
		}
		if (new File("C:\\Windows").isDirectory()) {
			setProperty("recorderFile", "C:\\" + name.toLowerCase() + ".wav");
		} else {
			setProperty("recorderFile", name.toLowerCase() + ".wav");
		}
		// 1-based channel
		setProperty("channel", 1);
		// first controller slider on first channel: volume
		setProperty("controller1-1", 7);
		// by default, 0dB synth volume
		setProperty("masterVolume", MASTER_VOLUME_SLIDER_OFFSET);
	}

	private void setListByProperty(Combo list, String key) {
		noUpdate++;
		try {
			String val = getStringProperty(key);
			if (val.length() > 0) {
				list.select(list.indexOf(val));
			}
		} finally {
			noUpdate--;
		}
	}

	private void storeListToProperty(Combo list, String key) {
		try {
			int index = list.getSelectionIndex();
			String sel = null;
			if (index >= 0) {
				sel = list.getItem(index);
			}
			String s = (sel == null) ? "" : sel;
			props.setProperty(key, s);
		} catch (Exception e) {
			error(e);
		}
	}

	private void setSliderByProperty(Slider slider, String key) {
		noUpdate++;
		try {
			int val = getIntProperty(key, -1);
			if (val >= 0) {
				try {
					slider.setSelection(val);
				} catch (Exception e) {
					debug("Reading property '" + key + "', value '" + val
							+ "': " + e.getMessage());
				}
			}
		} finally {
			noUpdate--;
		}
	}

	private void storeSliderToProperty(Slider slider, String key) {
		setProperty(key, slider.getSelection());
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

	// buffer time handling

	/** @return the selected buffer size in milliseconds */
	private double getSelectedLatency() {
		int listIndex = bufferSizeList.getSelectionIndex();
		if (listIndex >= 0 && listIndex < BUFFER_SIZES_MILLIS.length) {
			return BUFFER_SIZES_MILLIS[listIndex];
		}
		return DEFAULT_LATENCY_MILLIS;
	}

	private void displayLatencyIndex(double latencyMillis) {
		int listIndex = BUFFER_SIZES_MILLIS.length - 1;
		for (int i = 0; i < BUFFER_SIZES_MILLIS.length; i++) {
			if (latencyMillis <= BUFFER_SIZES_MILLIS[i]) {
				listIndex = i;
				break;
			}
		}
		noUpdate++;
		try {
			bufferSizeList.select(listIndex);
		} finally {
			noUpdate--;
		}
		displayEffectiveBufferTime();
	}

	private void displayEffectiveBufferTime() {
		if (sink != null && sink.isOpen()) {
			bufferSizeField.setText(Debug.format2(AudioUtils.samples2micros(
					sink.getBufferSize(), sink.getSampleRate()) / 1000.0)
					+ "ms (" + sink.getBufferSize() + " samples)");
		} else {
			bufferSizeField.setText("");
		}
	}

	// controller handling

	/**
	 * The supported set of controllers.
	 */
	private int[] CONTROLLERS = new int[] {
			MODULATION, PORTAMENTO_TIME, VOLUME, PAN, EXPRESSION,
			SUSTAIN_PEDAL, PORTAMENTO, SOSTENUTO_PEDAL, SOFT, RESONANCE,
			RELEASE_TIME, ATTACK_TIME, CUTOFF, DECAY_TIME, VIBRATO_RATE,
			VIBRATO_DEPTH, VIBRATO_DELAY, REVERB_LEVEL, CHORUS_LEVEL,
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
		Object[] channels = synth.getChannels().toArray();
		/*
		 * String[] channelNames = new String[channels.length]; for (int i=0; i<channels.length;
		 * i++) { channelNames[i] = channels[i].toString(); }
		 */
		int index = channelList.getSelectionIndex();
		if (index < 0) {
			return null;
		}
		return (MidiChannel) channels[index];
	}

	private int getSelectedChannelNumber() {
		return getSelectedChannel().getChannelNum();
	}

	private InstDesc getSelectedInstrument() {
		return allInstruments[instList.getSelectionIndex()];
	}

	/**
	 * React to a change of the currently selected MIDI channel
	 */
	private void displayChannel() {
		noUpdate++;
		try {
			int iChannel = channelList.getSelectionIndex();
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
			instList.select(index);
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
		int i = ctrlList.getSelectionIndex();
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
		displayCtrlValue(-1);
	}

	private void displayCtrlValue(int ctrl) {
		noUpdate++;
		try {
			if (ctrl < 0) {
				ctrl = getSelectedController();
			}
			MidiChannel channel = getSelectedChannel();
			if (channel == null) {
				ctrlField.setText("");
				return;
			}
			int value14 = channel.getController14bit(ctrl);
			int displayValue = -1;
			switch (getControllerRange(ctrl)) {
			case RANGE_CENTER:
				displayValue = (value14 / 128) - 64;
				break;
			case RANGE_POS:
				displayValue = value14 / 128;
				break;
			case RANGE_EXT_CENTER:
				displayValue = value14 - 128 * 64;
				break;
			case RANGE_EXT_POS:
				displayValue = value14;
				break;
			case RANGE_SWITCH:
				displayValue = (value14 >= 64 * 128) ? 1 : 0;
				break;
			}
			ctrlSlider.setSelection(value14);
			displayCtrlValueNumber(displayValue);
		} finally {
			noUpdate--;
		}
	}

	/**
	 * Send out the MIDI controller message in response to moving the slider.
	 */
	private void sendController() {
		if (synth == null || sink == null) {
			return;
		}
		noUpdate++;
		try {
			int ctrl = getSelectedController();
			int channel = getSelectedChannelNumber();
			if (ctrl >= 0 && channel >= 0) {
				int value = ctrlSlider.getSelection();
				// display should get updated by way of MIDI feedback
				// displayCtrlValueNumber(value);
				switch (getControllerRange(ctrl)) {
				case RANGE_CENTER: // fall-through
				case RANGE_POS:
					synth.midiInReceived(new MidiEvent(null,
							sink.getAudioTime(), channel, 0xB0, ctrl,
							(value >> 7) & 0x7f));
					break;
				case RANGE_EXT_CENTER: // fall-through
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
	 * Update the tuning text field with the current tuning in the synth.
	 */
	private void displayTuningValue() {
		double tuning = synth.getParams().getMasterTuning();
		tuningField.setText(format1(tuning));
	}

	/**
	 * Update the synth with the new tuning as selected by the tuning slider. Do
	 * not call displayTuningValue()!
	 */
	private void sendTuning() {
		noUpdate++;
		try {
			String freqHz = tuningField.getText();
			String freq = freqHz.replaceAll("Hz", "");
			double newTuning = Double.parseDouble(freq);
			if (newTuning != synth.getParams().getMasterTuning()) {
				synth.getParams().setMasterTuning(newTuning);
			}
		} finally {
			noUpdate--;
		}
	}

	// RENDER THREAD COUNT
	private void displayRenderThreadCount() {
		int renderThreadCount = synth.getRenderThreadCount();
		renderThreadCountField.setText(Integer.toString(renderThreadCount)
				+ ", "
				+ (ThreadFactory.hasRealtimeThread() ? "using realtime threads "
						+ (ThreadFactory.couldSetRealtimeThreadPriority() ? "with high priority"
								: ", could not set priority")
						: "standard Java threads"));
	}

	/**
	 * Update the synth with the new render thread count as selected by the
	 * user. Also call displayRenderThreadCount() so that the text field is
	 * updated accordingly.
	 */
	private void sendRenderThreadCount() {
		noUpdate++;
		try {
			int index = renderThreadCountList.getSelectionIndex();
			if (index <= 0) {
				// the first entry is "default"
				synth.setRenderThreadCount(AsynchronousRenderer.getDefaultThreadCount());
				// if for some reason, "nothing" is selected, select the first
				// item
				if (index != 0) {
					renderThreadCountList.select(0);
				}
			} else if (index == 1) {
				// the second entry is "none"
				synth.setRenderThreadCount(0);
			} else {
				synth.setRenderThreadCount(Integer.parseInt(renderThreadCountList.getItem(index)));
			}
			displayRenderThreadCount();
			if (DEBUG) {
				debug("Using " + synth.getRenderThreadCount()
						+ " render threads.");
			}
		} finally {
			noUpdate--;
		}
	}

	// MEMORY ALLOCATOR

	/**
	 * update the fields of the current allocation rate, size and number of
	 * retained objects
	 */
	private void updateAllocationFields() {
		noUpdate++;
		try {
			allocRateField.setText(getFriendlyByteSize(memAllocator.getAllocationRate())
					+ "/s");
			allocSizeField.setText(getFriendlyByteSize(memAllocator.getAllocateObjectSize()));
			allocRetentionField.setText(memAllocator.getReferenceObjectCount()
					+ " objects");
		} finally {
			noUpdate--;
		}
	}

	/**
	 * update the fields of the current allocation rate, size and number of
	 * retained objects
	 */
	private void displayAllocationStatus() {
		double objPerSec = ((double) memAllocator.getAllocationRate())
				/ ((double) memAllocator.getAllocateObjectSize());
		allocStatusField1.setText(format3(objPerSec) + " objects/s");
		allocStatusField2.setText("Retention: "
				+ getFriendlyByteSize(memAllocator.getCurrentRetentionSize()));
		allocStatusField3.setText("Total created: "
				+ memAllocator.getTotalAllocatedObjects());
	}

	/**
	 * Update the allocator thread with the value of the slider, and update the
	 * field.
	 */
	private void sendAllocator() {
		memAllocator.setAllocationRate(ALLOC_RATES[allocRateSlider.getSelection()]);
		memAllocator.setAllocateObjectSize(ALLOC_SIZES[allocSizeSlider.getSelection()]);
		memAllocator.setReferenceObjectCount(ALLOC_RETENTIONS[allocRetentionSlider.getSelection()]);
		updateAllocationFields();
	}

	private void sendNewBuffersSelection() {
		AudioPullThread.REUSE_AUDIO_BUFFERS = !useNewBuffersButton.getSelection();
		if (DEBUG) {
			debug("AudioPullThread.REUSE_AUDIO_BUFFERS = "
					+ AudioPullThread.REUSE_AUDIO_BUFFERS);
		}
	}

	private void updateMemoryTabText() {
		if (memAllocator.isEnabled()) {
			memoryTab.setText("* " + MEMORY_TAB_TEXT);
		} else {
			memoryTab.setText(MEMORY_TAB_TEXT);
		}
	}

	// master volume
	/**
	 * update the fields of the current master volume
	 */
	private void updateMasterVolumeFields() {
		double linearVol = synth.getParams().getMasterVolume();
		if (linearVol <= 0.0) {
			masterVolumeField.setText("-inf dB");
		} else {
			double dB = AudioUtils.linear2decibel(linearVol);
			masterVolumeField.setText(format2(dB) + " dB");
		}
	}

	private void sendMasterVolume() {
		if (muteButton.getSelection()) {
			synth.getParams().setMasterVolume(0);
		} else {
			synth.getParams().setMasterVolume(
					AudioUtils.decibel2linear(masterVolumeSlider.getSelection()
							- MASTER_VOLUME_SLIDER_OFFSET));
		}
		updateMasterVolumeFields();
	}

	// MIDI player stuff

	protected volatile boolean midiPlayerStarted;

	/**
	 * Start the currently selected MIDI file
	 */
	private void loadMidiFile() {
		try {
			File file = new File(playerFile.getText());
			if (!file.equals(player.getFile())) {
				status("Loading MIDI file: " + file);
				player.open(file);
				status("MIDI file loaded: " + file);
			}
			player.setStopListener(this);
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
	 * Start the currently selected MIDI file
	 */
	private void startPlayer() {
		midiPlayerStarted = false;
		try {
			loadMidiFile();
			if (player.isOpen()) {
				midiPlayerStarted = true;
				// start the sequencer
				player.start();
				// make sure the clocks are synchronized
				maintenance.synchronizeClocks(true);
				status("MIDI file playing: " + playerFile.getText());
			}
		} catch (Exception e) {
			midiPlayerStarted = false;
			status(e, "Cannot play MIDI file:");
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

	/**
	 * rewind the currently playing MIDI file to the beginning
	 */
	private void beginningPlayer() {
		if (player.isOpen()) {
			player.rewind();
		}
		displayCurrentPositions();
	}

	/**
	 * Forward 10 seconds
	 */
	private void forwardPlayer() {
		if (player.isOpen()) {
			player.windSeconds(10);
		}
		displayCurrentPositions();
	}

	/**
	 * Backward 10 seconds
	 */
	private void rewindPlayer() {
		if (player.isOpen()) {
			player.windSeconds(-10);
		}
		displayCurrentPositions();
	}

	/**
	 * Submit the new playback position upon moving the playback slider
	 */
	private void sendPlaybackSliderPosition() {
		if (player.isOpen()) {
			player.setPositionPercent(playerSlider.getSelection());
		}
		displayCurrentPositions();
	}

	// interface SMFMidiIn.StopListener
	public void onMidiPlaybackStop() {
		midiPlayerStarted = false;
		playerPos.getDisplay().asyncExec(new Runnable() {
			public void run() {
				makePlayerButtons();
			}
		});
		status("MIDI file playback stopped.");
	}

	public void displayHighFreqPositions() {
		noUpdate++;
		try {
			if (player != null && player.isOpen()) {
				playerPos.setText(formatTime(player.getPlaybackPosMillis()));
				playerBarPosField.setText(player.getPlaybackPosBars());
			}
			if (recorder != null) {
				recorderPos.setText(formatTime(recorder.getAudioTime().getMillisTime()));
			}
		} finally {
			noUpdate--;
		}
	}

	public void displayLowFreqPositions() {
		noUpdate++;
		try {
			if (player != null && player.isOpen()) {
				playerSlider.setSelection((int) player.getPositionPercent());
			} else {
				playerPos.setText("");
				playerBarPosField.setText("");
				playerSlider.setSelection(0);
			}
			if (recorder == null) {
				recorderPos.setText("");
			}
		} finally {
			noUpdate--;
		}
	}

	public void displayCurrentPositions() {
		displayHighFreqPositions();
		displayLowFreqPositions();
	}

	/**
	 * Enable/disable buttons of the MIDI Player transport panel
	 */
	private void makePlayerButtons() {
		boolean isLoaded = (player != null && player.isOpen());
		playerStart.setEnabled(!midiPlayerStarted);
		playerStop.setEnabled(isLoaded
				&& (midiPlayerStarted || (player != null && player.getPlaybackPosMillis() != 0)));
		playerPause.setEnabled(playerStop.isEnabled());
		playerBeginning.setEnabled(isLoaded);
		playerForward.setEnabled(isLoaded);
		playerRewind.setEnabled(isLoaded);
	}

	private void updateNoteDispatcherStatus() {
		if (synth.isNoteDispatcherRunning()) {
			noteDispatcherStatus.setText("Status: asynchronous note dispatcher");
		} else {
			if (synth.getNoteDispatcherMode() == Synthesizer.NOTE_DISPATCHER_REQUEST_ASYNCHRONOUS) {
				noteDispatcherStatus.setText("Status: using AudioPullThread (latency too high)");
			} else {
				noteDispatcherStatus.setText("Status: notes dispatched from AudioPullThread");
			}
		}
	}

	private void sendNoteDispatcher() {
		synth.setNoteDispatcherMode(noteDispatcherCombo.getSelectionIndex());
		updateNoteDispatcherStatus();
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

	public final void addSynthesizerListeners(
			final SynthesizerListener synthListener) {
		synth.addListener(synthListener);
	}

	// synthesizer listener
	public void midiEventPlayed(AudioTime time, MidiIn input,
			MidiChannel channel, int status, int data1, int data2) {
		if (input != null) {
			int devIndex = input.getInstanceIndex();
			midiEventReceived[devIndex]++;
			whenLastMidiEventReceived[devIndex] = System.nanoTime() / 1000000L;
		}
		asyncMidiEventPlayed(input, channel, status, data1, data2);
	}

	public void asyncMidiEventPlayed(final MidiIn input,
			final MidiChannel channel, final int status, final int data1,
			final int data2) {

		if (closed || playerPos.isDisposed() || channelList.isDisposed()
				|| ctrlList.isDisposed()) {
			return;
		}
		playerPos.getDisplay().asyncExec(new Runnable() {
			public void run() {
				if (closed) return;
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
			}
		});
	}

	// dynamic debug switches

	private java.util.List<DebugHandler> debugHandlers;

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
	private void gatherDebugHandlers(Composite parent) {
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
							DebugHandler h = new DebugHandler(parent, sClass,
									field);
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

	private class DebugHandler {
		/**
		 * The full class name of this field.
		 */
		private String clazz;

		/**
		 * The boolean field that should be set/reset by this checkbox handler
		 */
		private Field field;

		private Button cb;

		public DebugHandler(Composite parent, String clazz, Field field) {
			this.field = field;
			this.clazz = clazz;
			createCheckbox(parent);
		}

		public Button createCheckbox(Composite parent) {

			cb = checkBox(parent, toString(), new IToggleAction() {
				public void toggle(boolean on) {
					setValue(on);
				}
			});
			return cb;
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
			cb.setSelection(val);
		}

		public String getPropKey() {
			return "Debug." + toString();
		}

		public String toString() {
			int dot = clazz.lastIndexOf(".");
			return clazz.substring(dot + 1) + "." + field.getName();
		}
	}

	private static int refresherID = 0;

	public static final class Refresher {
		private static final boolean DEBUG_REFRESHER = false;

		private static final int INITIAL_REFRESH_INTERVAL = 100; // ms

		private int refreshInterval;
		private final Runnable runnable;
		private volatile boolean running;
		private ArrayList<Runnable> refreshListeners;
		// private long lastTime;
		// private int actualRefreshInterval;

		private String name;

		public Refresher() {
			name = "Refresher " + refresherID;
			refresherID++;

			refreshListeners = new ArrayList<Runnable>();
			refreshInterval = INITIAL_REFRESH_INTERVAL;
			running = false;
			runnable = new Runnable() {
				private boolean lastState = true;

				public void run() {
					if (DEBUG_REFRESHER) {
						if (lastState != running) {
							debug(name + ": running=" + running);
						}
					}
					if (running) {
						// final long time = System.nanoTime() / 1000000L;
						// actualRefreshInterval = (int)(time-lastTime);
						// lastTime = time;
						fireRefresh();
						Display.getCurrent().timerExec(refreshInterval,
								runnable);
					}
				}
			};
			start();
		}

		public final void start() {
			running = true;
			if (DEBUG_REFRESHER) {
				debug(name + ": start");
			}
			Display.getCurrent().timerExec(refreshInterval, runnable);
		}

		public final void stop() {
			if (DEBUG_REFRESHER) {
				debug(name + ": stop");
			}
			running = false;
		}

		public final boolean isRunning() {
			return running;
		}

		// public final int getActualRefreshInterval() {
		// return actualRefreshInterval;
		// }

		public final int getRefreshInterval() {
			return refreshInterval;
		}

		public final void setRefreshInterval(final int refreshInterval) {
			this.refreshInterval = refreshInterval;
		}

		public final void addRefreshListener(final Runnable li) {
			refreshListeners.add(li);
		}

		public final void removeRefreshListener(final Runnable li) {
			refreshListeners.remove(li);
		}

		public final void refresh() {
			if (running) {
				fireRefresh();
			}
		}

		private final void fireRefresh() {
			if (!running) return;
			for (final Iterator<Runnable> it = refreshListeners.iterator(); it.hasNext();) {
				final Runnable li = it.next();
				try {
					li.run();
				} catch (final Exception ex) {
					System.err.println("Exception in Refresher:");
					ex.printStackTrace();
				}
			}
		}
	}

	public interface CloseListener {
		public void closeWindow();
	}

	private void parseArguments(String[] args) {
		if (args != null) {
			int argi = 0;
			while (argi < args.length) {
				String arg = args[argi];
				if (arg.equals("-h")) {
					printUsageAndExit();
				} else if (arg.equals("-play")) {
					setAutoPlay(true);
				} else if (arg.equals("-duration")) {
					argi++;
					if (argi >= args.length) {
						printUsageAndExit(arg);
					}
					setDuration(Integer.parseInt(args[argi]));
				} else if (arg.equals("-nortsj")) {
					ThreadFactory.setUseRTSJ(false);

				} else {
					printUsageAndExit(arg);
				}
				argi++;
			}
		}
	}

	private static void printUsageAndExit(String failedArg) {
		out("ERROR: argument " + failedArg);
		printUsageAndExit();
	}

	private static void printUsageAndExit() {
		out("Usage:");
		out("java HarmoniconGUI [options]");
		out("Options:");
		out("-h                  : display this help message");
		out("-nortsj             : do not use RTSJ threads");
		out("-play               : start MIDI file playback immediately");
		out("-duration <sec>     : quit this program after <sec> seconds");
		// throw new RuntimeException("aborted");
		System.exit(1);
	}
}
