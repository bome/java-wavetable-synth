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

import static com.ibm.realtime.synth.utils.Debug.debug;
import static com.ibm.realtime.synth.utils.Debug.error;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Properties;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.BooleanControl;
import javax.sound.sampled.CompoundControl;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.Port;
import javax.sound.sampled.TargetDataLine;
import javax.sound.sampled.Port.Info;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.events.ShellEvent;
import org.eclipse.swt.events.ShellListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.ibm.realtime.synth.engine.AudioBuffer;
import com.ibm.realtime.synth.modules.DiskWriterSink;

/**
 * Base class for GUI programs allowing to capture audio data
 * 
 * @author florian
 */
public abstract class AudioCaptureGUIBase {

	public static boolean DEBUG = false;

	private static final String PROPERTIES_FILE_SUFFIX = ".properties";

	protected static double SAMPLING_RATE = 96000.0;
	protected static AudioFormat AUDIO_FORMAT = new AudioFormat(
			(float) SAMPLING_RATE, 16, 2, true, false);
	protected static double AMPLIFICATION = 1.0;

	private String name;

	private Label statusLabel;
	private Combo audioInList;
	private Combo audioInPorts;
	protected Text recorderFile;
	protected Button recorderBrowse;
	private Label recorderPos;
	protected Button recorderActive;
	private Button bStart;
	private Button bStop;
	// 2 level meters for left and right
	private ProgressBar[] levelMeter = new ProgressBar[2];

	protected int noUpdate = 0;
	private Properties props;
	private boolean closed = false;
	protected AudioReadThread audioReadThread = null;
	protected DiskWriterSink diskWriter = null;
	private boolean started = false;

	private boolean autoStart = false;
	private int timeOutInSeconds = 0;
	private String audioOutParam = "";

	protected java.util.List<Mixer.Info> devList = new ArrayList<Mixer.Info>();

	// PORT SUPPORT
	/** all compound controls available on the system, as a display list */
	private java.util.List<PortInfo> ports = new ArrayList<PortInfo>();
	/** the selected port info */
	private PortInfo currPortInfo;

	protected AudioCaptureGUIBase(String name) {
		this.name = name;
		// ---------------- init properties -------------------
		createDefaultProperties();
		loadProperties();
	}

	public String getName() {
		return name;
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

	protected Button checkBox(Composite group, String name,
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

	protected Button createToggleButton(Composite group, String name,
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

	protected ProgressBar progressBar(Composite group, int min, int max) {
		ProgressBar pb = new ProgressBar(group, SWT.VERTICAL | SWT.SMOOTH);
		pb.setMinimum(min);
		pb.setMaximum(max);
		return pb;
	}

	protected Button actionButton(Composite group, String name,
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

	protected Button fileOpenButton(Composite group, final Text fileLabel,
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

	protected void setHorizontalFill(Control ctrl) {
		GridData gridData = new GridData();
		gridData.horizontalAlignment = GridData.FILL;
		gridData.grabExcessHorizontalSpace = true;
		ctrl.setLayoutData(gridData);
	}

	protected void setVerticalFill(Control ctrl) {
		GridData gridData = new GridData();
		gridData.verticalAlignment = GridData.FILL;
		gridData.grabExcessVerticalSpace = true;
		ctrl.setLayoutData(gridData);
	}

	public void setGridMinimumWidth(Control ctrl, int minWidth) {
		GridData gridData = new GridData();
		gridData.horizontalAlignment = SWT.BEGINNING;
		gridData.minimumWidth = minWidth;
		// gridData.grabExcessHorizontalSpace = true;
		gridData.widthHint = minWidth;
		ctrl.setLayoutData(gridData);
	}

	public void setGridSpawnRows(Control ctrl, int rows) {
		GridData gridData = new GridData();
		gridData.verticalSpan = rows;
		gridData.grabExcessVerticalSpace = true;
		gridData.minimumHeight = 1;
		gridData.heightHint = 50;
		gridData.verticalAlignment = SWT.BEGINNING;
		ctrl.setLayoutData(gridData);
	}

	public void setGridSpawnCols(Control ctrl, int cols) {
		GridData gridData = new GridData();
		gridData.horizontalSpan = cols;
		gridData.grabExcessHorizontalSpace = true;
		// gridData.minimumWidth = 1;
		// gridData.widthHint = 100;
		gridData.horizontalAlignment = SWT.BEGINNING;
		ctrl.setLayoutData(gridData);
	}

	public void setGridFill(Control ctrl) {
		ctrl.setLayoutData(new GridData(GridData.FILL_BOTH));
	}

	protected Composite createLayoutPanel(Composite parent) {
		Composite ret = new Composite(parent, SWT.NONE);
		return ret;
	}

	protected Layout createNoSpaceGridLayout(int columns) {
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

	protected GridLayout grid5 = new GridLayout();
	protected GridLayout grid4 = new GridLayout();
	protected GridLayout grid3 = new GridLayout();
	protected GridLayout grid2 = new GridLayout();
	protected GridLayout grid1 = new GridLayout();

	public void createPartControl(final Composite parent) {
		grid5.numColumns = 5;
		grid4.numColumns = 4;
		grid3.numColumns = 3;
		grid2.numColumns = 2;
		grid1.numColumns = 1;

		String fieldDefaultText = "__________";
		// final LatencyTester pane = this;

		// ---------- Top level GUI widgets -------------
		parent.setLayout(grid1);

		// audio input
		Group outRegionGroup = new Group(parent, SWT.NONE);
		setHorizontalFill(outRegionGroup);
		outRegionGroup.setLayout(grid4);
		outRegionGroup.setText("Input Settings");
		new Label(outRegionGroup, SWT.NONE).setText("Audio In:");
		audioInList = new Combo(outRegionGroup, SWT.BORDER | SWT.READ_ONLY);
		setHorizontalFill(audioInList);
		// insert level meters which spawn all rows
		levelMeter[0] = progressBar(outRegionGroup, 0, 1000);
		setGridSpawnRows(levelMeter[0], 2);
		levelMeter[1] = progressBar(outRegionGroup, 0, 1000);
		setGridSpawnRows(levelMeter[1], 2);
		new Label(outRegionGroup, SWT.NONE).setText("Port:");
		audioInPorts = new Combo(outRegionGroup, SWT.BORDER | SWT.READ_ONLY);
		setHorizontalFill(audioInPorts);
		audioInPorts.addSelectionListener(new SelectionListener() {
			public void widgetSelected(SelectionEvent e) {
				selectPort();
			}

			public void widgetDefaultSelected(SelectionEvent e) {
				widgetSelected(e);
			}
		});

		// CONTROL
		Group ctlGroup = new Group(parent, SWT.NONE);
		setHorizontalFill(ctlGroup);
		ctlGroup.setLayout(grid2);
		ctlGroup.setText("Action");

		bStart = actionButton(ctlGroup, "Start", new IButtonAction() {
			public void activate() {
				start();
			}
		});
		bStop = actionButton(ctlGroup, "Stop", new IButtonAction() {
			public void activate() {
				stop();
			}
		});

		// Wave Recording
		Group wavGroup = new Group(parent, SWT.NONE);
		setHorizontalFill(wavGroup);
		wavGroup.setLayout(grid3);
		wavGroup.setText("Wave Record");
		recorderActive = checkBox(wavGroup, "Write to file",
				new IToggleAction() {
					public void toggle(boolean on) {
						if (noUpdate == 0) doWriteToFile();
					}
				});
		new Label(wavGroup, 0); // gap
		new Label(wavGroup, 0); // gap
		recorderFile = new Text(wavGroup, SWT.BORDER);
		setHorizontalFill(recorderFile);
		recorderBrowse = fileOpenButton(wavGroup, recorderFile, "Wave File",
				"wav", new IFileOpenButtonAction() {
					public void open(String filename) {
						recorderFile.setText(filename);
					}
				});
		recorderBrowse.setText("Browse");
		recorderPos = new Label(wavGroup, SWT.BORDER | SWT.RIGHT);
		recorderPos.setText(fieldDefaultText);


		createPartControlImpl(parent);

		// horizontal filler
		Composite filler = createLayoutPanel(parent);
		setVerticalFill(filler);

		// status field
		Composite statusComposite = createLayoutPanel(parent);
		GridLayout statusGrid = (GridLayout) createNoSpaceGridLayout(2);
		statusGrid.marginLeft = grid2.marginLeft;
		statusGrid.marginRight = grid2.marginRight;
		statusComposite.setLayout(statusGrid);
		setHorizontalFill(statusComposite);
		statusLabel = new Label(statusComposite, SWT.NONE);
		setHorizontalFill(statusLabel);
		actionButton(statusComposite, "Close", new IButtonAction() {
			public void activate() {
				closeWindow(true);
			}
		});

		makeEnabled();
	}

	/**
	 * Create all functional objects for the synth and initialize them. They are
	 * initialized with the last values using the properties.
	 */
	public void init() {
		noUpdate++;
		try {
			status("Gathering audio devices and ports...");

			devList.clear();
			ports.clear();
			int selectedPortIndex = -1;
			Mixer.Info[] infos = AudioSystem.getMixerInfo();
			// go through all audio devices and see if they provide input
			// line(s) and/or ports
			for (Mixer.Info info : infos) {
				Mixer m = AudioSystem.getMixer(info);
				Line.Info[] lineInfos = m.getTargetLineInfo();
				for (Line.Info lineInfo : lineInfos) {
					if (lineInfo instanceof DataLine.Info) {
						// we found a target data line, so we can add this mixer
						// to the list of supported devices
						devList.add(info);
						break;
					} else if (lineInfo instanceof Port.Info) {
						Port.Info pinfo = (Port.Info) lineInfo;
						if (pinfo.isSource()) {
							// microphone port
							int selected = handleSourcePort(m, pinfo);
							if (selected >= 0) {
								selectedPortIndex = selected;
							}
						}
					}
				}
				lineInfos = m.getSourceLineInfo();
				for (Line.Info lineInfo : lineInfos) {
					if (lineInfo instanceof Port.Info) {
						Port.Info pinfo = (Port.Info) lineInfo;
						if (pinfo.isSource()) {
							// microphone port
							int selected = handleSourcePort(m, pinfo);
							if (selected >= 0) {
								selectedPortIndex = selected;
							}
						}
					}
				}
			}

			String[] sa = new String[devList.size()];
			int audioDeviceIndex = -1;
			for (int i = 0; i < devList.size(); i++) {
				sa[i] = "Java Sound: " + devList.get(i).getName();
				if (audioOutParam != ""
						&& devList.get(i).getName().indexOf(audioOutParam) >= 0
						&& audioDeviceIndex < 0) {
					audioDeviceIndex = i;
				}
			}
			audioInList.setItems(sa);
			audioInList.setEnabled(sa.length > 0);

			sa = new String[ports.size()];
			for (int i = 0; i < ports.size(); i++) {
				sa[i] = ports.get(i).toString();
			}
			audioInPorts.setItems(sa);
			if (selectedPortIndex >= 0) {
				audioInPorts.select(selectedPortIndex);
			}
			audioInPorts.setEnabled(ports.size() > 0);

			diskWriter = new DiskWriterSink();

			initImpl();

			applyPropertiesToGUI();

			// if the user overrode the wave device setting by way of the
			// command line, use it
			if (audioDeviceIndex >= 0) {
				audioInList.select(audioDeviceIndex);
			} else if (audioOutParam != "") {
				error(getName()+": Could not find audio device '"+audioOutParam+"' !");
			}

		} finally {
			noUpdate--;
		}
		makeEnabled();

		if (autoStart) {
			timerExec(300, new Runnable() {
				public void run() {
					start();
				}
			});
		}
		if (timeOutInSeconds > 0) {
			timerExec(timeOutInSeconds * 1000, new Runnable() {
				public void run() {
					closeWindow(true);
				}
			});
		}
		status("Init done.");
	}

	protected void timerExec(int timeInMillis, Runnable runner) {
		display.timerExec(timeInMillis, runner);
	}

	protected abstract void initImpl();

	protected void setAutoStart(boolean auto) {
		autoStart = auto;
	}

	protected void setDuration(int timeInSeconds) {
		timeOutInSeconds = timeInSeconds;
	}

	/**
	 * override output device setting in properties file. It is matched as a
	 * substring of the available devices
	 */
	protected void setAudioOutputDevice(String out) {
		audioOutParam = out;
	}

	protected abstract void createPartControlImpl(Composite parent);

	protected boolean isStarted() {
		return started;
	}

	protected boolean isRecording() {
		return (diskWriter != null) && diskWriter.isOpen();
	}

	protected void makeEnabled() {
		bStart.setEnabled(!isStarted());
		bStop.setEnabled(isStarted());
		audioInList.setEnabled(!isStarted());
		recorderBrowse.setEnabled(!isRecording());
		recorderFile.setEnabled(!isRecording());
		levelMeter[0].setVisible(isStarted());
		levelMeter[1].setVisible(isStarted());
	}

	protected void doWriteToFile() {
		if (!isStarted()) return;
		if (recorderActive.getSelection()) {
			startWriteToFile();
		} else {
			stopWriteToFile();
		}
	}

	/** called when pressing the start button */
	protected synchronized void start() {
		stop();
		TargetDataLine tdl = null;
		try {
			// start audio input device
			status("Starting audio device "
					+ devList.get(audioInList.getSelectionIndex()).getName());
			resetLevelMeterData();
			displayLevelMeter();
			tdl = AudioSystem.getTargetDataLine(AUDIO_FORMAT,
					devList.get(audioInList.getSelectionIndex()));
			tdl.open(AUDIO_FORMAT, 12000);
			// wave file writer requires start flag to be set
			started = true;
			doWriteToFile();
			audioReadThread = new AudioReadThread(tdl);
			tdl.start();
			onStart();
			audioReadThread.start();
			makeEnabled();
			displayRecorderPos();
		} catch (Throwable t) {
			if (tdl != null) {
				tdl.close();
			}
			status(t.toString());
			error(t);
			stop();
		}
	}

	/** called directly after starting the audio device */
	protected abstract void onStart();

	/** called when pressing the stop button */
	protected synchronized void stop() {
		started = false;
		if (audioReadThread != null) {
			audioReadThread.doStop();
			audioReadThread = null;
			status("Stopped audio device.");
			onStop();
		}
		resetLevelMeterData();
		displayLevelMeter();
		stopWriteToFile();
		makeEnabled();
	}

	/** called directly after stopping the audio device */
	protected abstract void onStop();

	protected synchronized void startWriteToFile() {
		if (!isRecording()) {
			try {
				diskWriter.open(new File(recorderFile.getText()), AUDIO_FORMAT);
				status("Created file " + recorderFile.getText());
				recorderActive.setSelection(true);
			} catch (Exception e) {
				status(e.getClass().getSimpleName()+": "+e.toString());
				recorderActive.setSelection(false);
			}
			makeEnabled();
		}
		displayRecorderPos();
	}

	protected synchronized void stopWriteToFile() {
		if (isRecording()) {
			diskWriter.close();
			status("Finished writing to file " + recorderFile.getText());
			makeEnabled();
			recorderActive.setSelection(false);
		}
		displayRecorderPos();
	}

	// PORTS

	/**
	 * Select the index port.
	 */
	private void selectPort() {
		// first close any open port
		closePort();
		int sel = audioInPorts.getSelectionIndex();
		if (sel < 0) return;
		PortInfo pi = ports.get(sel);
		if (pi == currPortInfo) return;
		try {
			Port port = (Port) pi.getMixer().getLine(pi.getPortInfo());
			port.open();
			try {
				CompoundControl cc = (CompoundControl) (port.getControls()[pi.controlIndex]);
				javax.sound.sampled.Control[] mc = cc.getMemberControls();
				// FloatControl portVolume = (FloatControl)
				// mc[pi.getVolIndex()];
				BooleanControl portSelect = (BooleanControl) mc[pi.getSelectIndex()];
				portSelect.setValue(true);
				currPortInfo = pi;
			} finally {
				port.close();
			}
		} catch (Throwable t) {
			status(t.toString());
			error(t);
			closePort();
		}
	}

	private void closePort() {
		// if (port != null) {
		// port.close();
		// }
		// currPortInfo = null;
		// port = null;
		// portSelect = null;
		// portVolume = null;
	}

	protected void displayRecorderPos() {
		if (closed) return;
		long time = -1;
		if (diskWriter != null) {
			time = diskWriter.getAudioTime().getMillisTime();
		}
		if (time != -1) {
			recorderPos.setText(formatTime(time));
		} else {
			recorderPos.setText("");
		}
	}

	private final Runnable displayRecorderPosRunner = new Runnable() {
		public void run() {
			displayRecorderPos();
		}
	};

	private void asyncDisplayDiskWriterPos() {
		Display.getDefault().asyncExec(displayRecorderPosRunner);
	}

	// level meter support
	private double[] levelMeterCurr = new double[2];

	/** reset the level meter values. Does not update the display */
	protected void resetLevelMeterData() {
		levelMeterCurr[0] = 0.0;
		levelMeterCurr[1] = 0.0;
	}

	protected void displayLevelMeter() {
		if (closed) return;
		//System.out.println("display level: " + levelMeterCurr[0]);
		for (int i = 0; i < 2; i++) {
			levelMeter[i].setSelection((int) (levelMeterCurr[i] * 1000.0));
		}
	}

	private final Runnable displayLevelMeterRunner = new Runnable() {
		public void run() {
			displayLevelMeter();
		}
	};

	protected void asyncDisplayLevel() {
		Display.getDefault().asyncExec(displayLevelMeterRunner);
	}

	/**
	 * @param m
	 * @param pinfo
	 * @return the currently selected as an index in the ports list, or -1 if no
	 *         controls of these ports are selected
	 */
	private int handleSourcePort(Mixer m, Port.Info pinfo) {
		// walk through all top-level controls
		Port p = null;
		int sel = -1;
		try {
			p = (Port) m.getLine(pinfo);
			p.open();
			javax.sound.sampled.Control[] cs = p.getControls();
			for (int c = 0; c < cs.length; c++) {
				if (cs[c] instanceof CompoundControl) {
					// find indexd for volume and select
					// controls
					int volIndex = -1;
					int selectIndex = -1;
					javax.sound.sampled.Control[] ccs = ((CompoundControl) cs[c]).getMemberControls();
					for (int cc = 0; cc < ccs.length; cc++) {
						javax.sound.sampled.Control thisCtrl = ccs[cc];
						if ((thisCtrl instanceof FloatControl)
								&& thisCtrl.getType().equals(
										FloatControl.Type.VOLUME)
								&& (volIndex < 0)) {
							volIndex = cc;
						}
						if ((thisCtrl instanceof BooleanControl)
								&& thisCtrl.getType().toString().contains(
										"elect") && (selectIndex < 0)) {
							selectIndex = cc;
						}
					}
					if (volIndex >= 0 && selectIndex >= 0) {
						// add this port
						ports.add(new PortInfo(m.getMixerInfo().getName()
								+ ": " + cs[c].getType().toString(), m, pinfo,
								c, volIndex, selectIndex));
						// if this port is currently selected, set the return
						// value
						if (sel < 0
								&& ((BooleanControl) ccs[selectIndex]).getValue()) {
							sel = ports.size() - 1;
						}
					}
				}
			}
		} catch (Throwable t) {
			if (p != null) p.close();
			if (!(t instanceof RuntimeException)) {
				throw (RuntimeException) t;
			}
		}
		return sel;
	}

	public boolean isClosed() {
		return closed;
	}

	/**
	 * Close all devices, i/o, and threads.
	 */
	public synchronized void close() {
		if (closed) return;
		closed = true;
		try {
			saveProperties();
			// clean-up
			stopWriteToFile();
			try {
				stop();
			} catch (Exception e) {
				error(e);
			}
			// close port
			closePort();
		} catch (Throwable t) {
			error(t);
		}
	}

	protected void newAudioBuffer(AudioBuffer buffer) {
		int length = buffer.getSampleCount();
		// calc the current level: maximum of this buffer
		for (int channel = 0; channel < 2; channel++) {
			double level = 0;
			double[] data = buffer.getChannel(channel);
			for (int i = 0; i < length; i++) {
				if (data[i] > level) {
					level = data[i];
				}
			}
			// slow degrade
			if (level < levelMeterCurr[channel] - 0.05) {
				level = levelMeterCurr[channel] - 0.05;
				if (level < 0) {
					level = 0;
				}
			}
			levelMeterCurr[channel] = level;
		}
		if (isRecording()) {
			synchronized (this) {
				diskWriter.write(buffer);
			}
			asyncDisplayDiskWriterPos();
		}
		asyncDisplayLevel();
	}

	// PROPERTIES

	/**
	 * Load properties from file. This is called from the constructor before any
	 * GUI elements or functional classes are created.
	 */
	protected void loadProperties() {
		File file = getPropertiesFile();
		if (file.exists()) {
			if (DEBUG) {
				debug(getName() + ": loading properties from file: " + file);
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
	protected void saveProperties() {
		try {
			// first update the properties with the current GUI values
			storeListToProperty(audioInList, "audioInDevice");
			storeListToProperty(audioInPorts, "audioInPort");
			setProperty("wavefile", recorderFile.getText());

			File file = getPropertiesFile();
			status("writing properties file: " + file);
			props.store(new FileOutputStream(file), getName()
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
	protected void applyPropertiesToGUI() {
		noUpdate++;
		try {
			setListByProperty(audioInList, "audioInDevice", true);
			if (setListByProperty(audioInPorts, "audioInPort", false)) {
				selectPort();
			}
			recorderFile.setText(getStringProperty("wavefile"));
		} finally {
			noUpdate--;
		}
	}

	protected static String removeWhiteSpace(String s) {
		StringBuffer sb = new StringBuffer(s);
		for (int i = sb.length() - 1; i >= 0; i--) {
			if (sb.charAt(i) <= 32) {
				sb.deleteCharAt(i);
			}
		}
		return sb.toString();
	}

	/**
	 * @return the file to which the properties are saved, and from which
	 *         they're read.
	 */
	protected File getPropertiesFile() {
		String home = ".";
		String prefix = "";
		try {
			home = System.getProperty("user.home");
			// if saving to home directory, hide the file name
			prefix = ".";
		} catch (Exception e) {
			debug(e);
		}
		return new File(home, prefix
				+ removeWhiteSpace(getName().toLowerCase())
				+ PROPERTIES_FILE_SUFFIX);
	}

	/**
	 * @param key the key of the requested property
	 * @return the value of the property, or the empty string if the property
	 *         does not exist
	 */
	protected String getStringProperty(String key) {
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

	/**
	 * Create the props object and fill it with a number of non-trivial default
	 * properties.
	 */
	protected void createDefaultProperties() {
		props = new Properties();
		String n = removeWhiteSpace(getName().toLowerCase());
		if (new File("C:\\Windows").isDirectory()) {
			setProperty("wavefile", "C:\\" + n + ".wav");
		} else {
			setProperty("wavefile", n + ".wav");
		}
	}

	/**
	 * @param list
	 * @param key
	 * @param selectDefault
	 * @return true if the property existed in the properties
	 */
	protected boolean setListByProperty(Combo list, String key,
			boolean selectDefault) {
		noUpdate++;
		boolean ret = false;
		try {
			String val = getStringProperty(key);
			int sel = -1;
			if (val.length() > 0) {
				sel = list.indexOf(val);
				ret = true;
			}
			if (selectDefault && sel < 0) sel = 0;
			if (sel >= 0 && sel < list.getItemCount()) {
				list.select(sel);
			}
		} finally {
			noUpdate--;
		}
		return ret;
	}

	protected void storeListToProperty(Combo list, String key) {
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

	/**
	 * @return a String with the number
	 *         <code>num</num> prepended with as many zeroes as necessary to return a string with exactly <code>digits</code> characters.
	 */
	protected static final String formatNum(int num, int digits) {
		String result = Integer.toString(num);
		while (result.length() < digits)
			result = "0" + result;
		return result;
	}

	public static final String formatTime(long timeMillis) {
		return Long.toString(timeMillis / 60000) + ":"
				+ formatNum((int) (timeMillis / 1000) % 60, 2) + "."
				+ Integer.toString((int) (timeMillis / 100) % 10);
	}

	protected static final String formatTime(double timeMillis) {
		return Long.toString((long) timeMillis)
				+ "."
				+ formatNum(
						(int) ((long) Math.round(timeMillis * 1000.0) % 1000),
						3);
	}

	protected void status(String s) {
		if (s == "") s = " ";
		if (statusLabel != null) {
			statusLabel.setText(s);
		}
		if (DEBUG) {
			debug(s);
		}
	}

	protected class AudioReadThread extends Thread {
		private TargetDataLine tdl;
		private volatile boolean stopRequested = false;

		public AudioReadThread(TargetDataLine tdl) {
			super("Audio Read Thread");
			this.tdl = tdl;
		}

		public void doStop() {
			stopRequested = true;
			if (DEBUG) {
				debug(getName() + ": Closing audio device");
			}
			tdl.close();
			try {
				this.join(2000);
			} catch (InterruptedException ie) {
			}
		}

		public void run() {
			if (DEBUG) {
				debug(getName() + ": Start audio read thread");
			}
			byte[] buffer = new byte[tdl.getBufferSize()];
			int frameSize = tdl.getFormat().getFrameSize();
			AudioBuffer ab = new AudioBuffer(tdl.getFormat().getChannels(),
					buffer.length / frameSize, tdl.getFormat().getSampleRate());
			while (!stopRequested) {
				int read = tdl.read(buffer, 0, buffer.length);
				if (!stopRequested && read > frameSize) {
					ab.initFromByteArray(buffer, 0, read, tdl.getFormat());
					if (AMPLIFICATION != 1.0) {
						int localChannelCount = ab.getChannelCount();
						int sampleCount = ab.getSampleCount();
						for (int ch = 0; ch < localChannelCount; ch++) {
							double[] channel = ab.getChannel(ch);
							for (int i = 0; i < sampleCount; i++) {
								channel[i] *= AMPLIFICATION;
							}
						}
					}
					newAudioBuffer(ab);
				}
			}
			if (DEBUG) {
				debug(getName() + ": Quit audio read thread");
			}
		}
	}

	/**
	 * A class for selecting a port
	 */
	private static class PortInfo {
		private String name;
		private Mixer mixer;
		private Port.Info portInfo;
		private int controlIndex, volIndex, selectIndex;

		protected PortInfo(String name, Mixer mixer, Info portInfo,
				int controlIndex, int volIndex, int selectIndex) {
			super();
			this.name = name;
			this.mixer = mixer;
			this.portInfo = portInfo;
			this.controlIndex = controlIndex;
			this.volIndex = volIndex;
			this.selectIndex = selectIndex;
		}

		public String toString() {
			return name;
		}

		/**
		 * @return the controlIndex
		 */
		public int getControlIndex() {
			return controlIndex;
		}

		/**
		 * @return the mixer
		 */
		public Mixer getMixer() {
			return mixer;
		}

		/**
		 * @return the portInfo
		 */
		public Port.Info getPortInfo() {
			return portInfo;
		}

		/**
		 * @return the selectIndex
		 */
		public int getSelectIndex() {
			return selectIndex;
		}

		/**
		 * @return the volIndex
		 */
		public int getVolIndex() {
			return volIndex;
		}

	}

	public static String getDateAndTime() {
		Calendar c = Calendar.getInstance();
		return String.format("%1$tY-%1$tm-%1$te_%1$tH.%1$tM", c);
	}


	// MAIN SUPPORT

	private Display display;
	private Shell shell;

	/** clean way of closing the application */
	protected void closeWindow(boolean disposeWindow) {
		if (display != null && !display.isDisposed()) {
			if (shell != null && !shell.isDisposed()) {
				Point d = shell.getSize();
				setProperty("frameWidth", d.x);
				setProperty("frameHeight", d.y);
			}
		}
		close();
		if (disposeWindow) {
			if (shell != null && !shell.isDisposed()) {
				shell.dispose();
			}
			if (display != null && !display.isDisposed()) {
				display.dispose();
			}
		}
	}

	/* main method */
	protected void mainImpl(int defWidth, int defHeight) {
		try {
			display = new Display();
			shell = new Shell(display);
			shell.setText(getName());
			shell.setLayout(new FillLayout());
			createPartControl(shell);
			shell.addShellListener(new ShellListener() {
				public void shellActivated(ShellEvent e) {
				}

				public void shellClosed(ShellEvent e) {
					closeWindow(false);
				}

				public void shellDeactivated(ShellEvent e) {
				}

				public void shellDeiconified(ShellEvent e) {
				}

				public void shellIconified(ShellEvent e) {
				}
			});
			shell.setSize(getIntProperty("frameWidth", defWidth),
					getIntProperty("frameHeight", defHeight));
			shell.open();
			init(); // Init properties
			while (!shell.isDisposed()) {
				if (!display.readAndDispatch()) {
					display.sleep();
				}
			}
			// make sure we don't call dispose() before the pane
			// has finished the close() operation!
			close();
			if (!display.isDisposed()) {
				display.dispose();
			}
		} catch (Throwable t) {
			error(t);
		}
		if (DEBUG) {
			debug(getName() + ": exit");
		}
	}

}
