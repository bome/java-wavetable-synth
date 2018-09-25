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

import com.ibm.realtime.synth.engine.AudioBuffer;

import java.io.*;

import javax.sound.sampled.AudioFormat;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

// TODO: generate MIDI test signals
// TODO: analyze wave files (offline mode)
// TODO: write out a .trace file

/**
 * Quickly hacked program for analyzing MIDI timing with an audio device. The
 * algorithm will detect an MIDI trigger and its delayed response if
 * <ul>
 * <li>the trigger is not MAX_LATENCY samples before the response
 * <li>the last response is at least MIN_TIME_BETWEEN_EVENTS samples ago
 * <li>an event is detected if the sample value is more than THRESHOLD
 * <li>the first occurrence of an event on either audio channel (left or right)
 * will fix which audio channel carries the trigger and which channel the
 * response. Click the Reset button to re-initialize this setting.
 * </ul>
 * That means that a quick succession of events will not be recognized as a
 * trigger-response and will be ignored completely.
 * 
 * @author florian
 */
public class LatencyTester extends AudioCaptureGUIBase {

	public static boolean LOGGING = true;

	static {
		SAMPLING_RATE = 96000.0;
		AUDIO_FORMAT = new AudioFormat(
			(float) SAMPLING_RATE, 16, 2, true, false);
		AMPLIFICATION = 5.0;
	}
	
	/** analysis: maximum latency detected as a coherent event */
	private static final long MAX_LATENCY = (long) SAMPLING_RATE / 2;

	/** analysis: threshold over which an event is detected */
	private static final double THRESHOLD = 0.1;

	/** analysis: minimum time between events in samples */
	private static final long MIN_TIME_BETWEEN_EVENTS = (long) SAMPLING_RATE / 10;

	private Text latCurr, latMin, latMax, latAvg;
	private boolean displayLogHeader = false;
	private boolean startAudioCapture = false;
	private boolean startLogToFile = false;
	
	private Text csvFile;
	private Button csvActive;
	private Button csvBrowse;
	private PrintStream csvWriter = null;

	private int numEvents;
	private double currLatency; // in msecs
	private double minLatency; // in msecs
	private double maxLatency; // in msecs
	private double avgLatency; // in msecs
	private double sumLatency; // in msecs

	/**
	 * Create an instance of the SoundFontGUI pane and load the persistence
	 * properties.
	 * 
	 * @param name the title of this pane
	 */
	public LatencyTester(String name, String[] args) {
		super(name);
		parseArguments(args);
	}

	@Override
	protected void createPartControlImpl(Composite parent) {
		// STATS
		Group statGroup = new Group(parent, SWT.NONE);
		setHorizontalFill(statGroup);
		statGroup.setLayout(grid1);
		statGroup.setText("Statistics");
		Button cb = checkBox(statGroup, "Log to stdout (csv)",
				new IToggleAction() {
					public void toggle(boolean on) {
						if (noUpdate == 0) LOGGING = on;
					}
				});
		cb.setSelection(LOGGING);

		Composite csvGroup = createLayoutPanel(statGroup);
		setHorizontalFill(csvGroup);
		csvGroup.setLayout(createNoSpaceGridLayout(2));
		csvActive = checkBox(csvGroup, "Write csv file:",
				new IToggleAction() {
					public void toggle(boolean on) {
						if (noUpdate == 0) doWriteCSVFile();
					}
				});
		csvActive.setSelection(startLogToFile);
		setGridSpawnCols(csvActive, 2);
		csvFile = new Text(csvGroup, SWT.BORDER);
		setHorizontalFill(csvFile);
		csvBrowse = fileOpenButton(csvGroup, csvFile, "CSV File",
				"csv", new IFileOpenButtonAction() {
					public void open(String filename) {
						csvFile.setText(filename);
					}
				});
		csvBrowse.setText("Browse");

		
		Composite anaGroup = createLayoutPanel(statGroup);
		anaGroup.setLayout(createNoSpaceGridLayout(2));
		new Label(anaGroup, SWT.NONE).setText("Current Latency: ");
		latCurr = new Text(anaGroup, SWT.BORDER | SWT.READ_ONLY);

		new Label(anaGroup, SWT.NONE).setText("Minimum Latency: ");
		latMin = new Text(anaGroup, SWT.BORDER | SWT.READ_ONLY);
		new Label(anaGroup, SWT.NONE).setText("Average Latency: ");
		latAvg = new Text(anaGroup, SWT.BORDER | SWT.READ_ONLY);
		new Label(anaGroup, SWT.NONE).setText("Maximum Latency: ");
		latMax = new Text(anaGroup, SWT.BORDER | SWT.READ_ONLY);
		
		actionButton(statGroup, "Reset", new IButtonAction() {
			public void activate() {
				resetAnalysis();
			}
		});
	}

	@Override
	public void init() {
		super.init();
		displayStatistics();
	}
	
	@Override
	protected void initImpl() {
		// activate recording if filename was set
		recorderActive.setSelection(startAudioCapture);
	}

	/** called directly after starting the audio device */
	@Override
	protected void onStart() {
		resetAnalysisData();
		resetAnalysis();
		doWriteCSVFile();
	}

	@Override
	public synchronized void onStop() {
		stopWriteCSVFile();
	}

	/**
	 * must be called from GUI thread
	 */
	private void resetAnalysis() {
		numEvents = 0;
		currLatency = 0.0;
		minLatency = 0;
		maxLatency = 0;
		avgLatency = 0;
		sumLatency = 0;
		secondStream = -1;
		displayStatistics();
	}

	private void displayStatistics() {
		if (isClosed()) return;
		latCurr.setText(formatTime(currLatency));
		latMin.setText(formatTime(minLatency));
		latMax.setText(formatTime(maxLatency));
		latAvg.setText(formatTime(avgLatency));
	}

	private final Runnable displayStatisticsRunner = new Runnable() {
		public void run() {
			displayStatistics();
		}
	};

	private void asyncDisplayStatistics() {
		Display.getDefault().asyncExec(displayStatisticsRunner);
	}

	// ANALYSIS

	/** current analysis position, in samples */
	private long anaCurrPos = 0;

	/** begin of last event */
	private long[] anaLastStart = new long[2];
	/** last event found */
	private long[] anaLast = new long[2];

	/** if the left channel is recording the delayed data, this is 0, otherwise 1 */
	private int secondStream = -1;

	private void resetAnalysisData() {
		anaCurrPos = 0;
		anaLastStart = new long[2];
		anaLastStart[0] = -2*MAX_LATENCY;
		anaLastStart[1] = -2*MAX_LATENCY;
		anaLast = new long[2];
		secondStream = -1;
		displayLogHeader = true;
	}

	private static final String sep = ",";
	
	private void printHeader(PrintStream ps) {
		ps.println("# Latency Tester CSV output");
		ps.println("# Test run started " + getDateAndTime());
		ps.println("# Trigger (sample)" + sep
				+ "Reaction (sample)" + sep + "Latency (samples)" + sep
				+ "Latency (ms)");
	}

	/** called when a pair of events is detected on left and right side */
	private void foundMatchingEvents(long firstEvent, long secondEvent) {
		int latencySamples = (int) (secondEvent - firstEvent);
		currLatency = ((double) latencySamples) / SAMPLING_RATE * 1000.0;
		if (DEBUG) {
			debug("Found event: " + firstEvent + " and " + secondEvent
					+ "  latency: " + latencySamples + " samples");
		}
		if (LOGGING || isWritingCSV()) {
			if (displayLogHeader && LOGGING) {
				printHeader(System.out);
				displayLogHeader = false;
			}
			String s = Long.toString(firstEvent) + sep
			+ Long.toString(secondEvent) + sep
			+ Long.toString(latencySamples) + sep
			+ Double.toString(currLatency);
			if (LOGGING) {
				System.out.println(s);
			}
			if (isWritingCSV()) {
				csvWriter.println(s);
			}
		}
		if (currLatency < minLatency || minLatency == 0.0) {
			minLatency = currLatency;
		}
		if (currLatency > maxLatency) {
			maxLatency = currLatency;
		}
		sumLatency += currLatency;
		numEvents++;
		avgLatency = (sumLatency / ((double) numEvents));
		asyncDisplayStatistics();
	}

	/**
	 * find events in data
	 * 
	 * @param data the absolute value of the current sample
	 */
	private void analyze(double data, int index) {
		if (data >= THRESHOLD) {
			if (anaCurrPos - anaLast[index] >= MIN_TIME_BETWEEN_EVENTS) {
				// found an event
				anaLastStart[index] = anaCurrPos;
				// can we match an event on the other channel?
				if (anaCurrPos - anaLastStart[1 - index] <= MAX_LATENCY) {
					// yes: found a pair!
					if (secondStream == -1) {
						// initialize who is the second stream
						secondStream = index;
					}
					if (secondStream == index) {
						foundMatchingEvents(anaLastStart[1 - index], anaCurrPos);
						// don't refind this event
						anaLastStart[0] = 0;
						anaLastStart[1] = 0;
					}
				}
			}
			anaLast[index] = anaCurrPos;
		}
	}

	@Override
	protected void newAudioBuffer(AudioBuffer buffer) {
		double[] data1 = buffer.getChannel(0);
		double[] data2 = buffer.getChannel(1);
		int length = buffer.getSampleCount();
		
		for (int i = 0; i < length; i++) {
			analyze(Math.abs(data1[i]), 0);
			analyze(Math.abs(data2[i]), 1);
			anaCurrPos++;
		}
		super.newAudioBuffer(buffer);
	}
	
	// CSV SUPPORT
	
	/** toggle start/stop writing the analysis to a csv file */
	private void doWriteCSVFile() {
		if (!isStarted()) return;
		if (csvActive.getSelection()) {
			startWriteCSVFile();
		} else {
			stopWriteCSVFile();
		}
	}
	
	protected boolean isWritingCSV() {
		return (csvWriter != null);
	}
	
	protected synchronized void startWriteCSVFile() {
		if (!isWritingCSV()) {
			try {
				csvWriter = new PrintStream(new FileOutputStream(new File(csvFile.getText())));
				printHeader(csvWriter);
				status("Created file " + csvFile.getText());
				csvActive.setSelection(true);
			} catch (Exception e) {
				status(e.getClass().getSimpleName()+": "+e.toString());
				csvActive.setSelection(false);
			}
		}
		makeEnabled();
	}

	protected synchronized void stopWriteCSVFile() {
		if (isWritingCSV()) {
			csvWriter.close();
			status("Finished writing to file " + csvFile.getText());
			csvActive.setSelection(false);
			csvWriter = null;
		}
		makeEnabled();
	}

	@Override
	protected void makeEnabled() {
		super.makeEnabled();
		csvBrowse.setEnabled(!isWritingCSV());
		csvFile.setEnabled(!isWritingCSV());
	}

	/**
	 * Create the props object and fill it with a number of non-trivial default
	 * properties.
	 */
	@Override
	protected void createDefaultProperties() {
		super.createDefaultProperties();
		String n = removeWhiteSpace(getName().toLowerCase());
		if (new File("C:\\Windows").isDirectory()) {
			setProperty("logfile", "C:\\" + n + ".csv");
		} else {
			setProperty("logfile", n + ".csv");
		}
	}

	/**
	 * Save the current state in the properties and save them to the properties
	 * file. This method should be called before any objects are closed.
	 */
	@Override
	protected void saveProperties() {
		try {
			setProperty("logfile", csvFile.getText());
		} catch (Exception e) {
			error(e);
		}
		super.saveProperties();
	}

	@Override
	protected void applyPropertiesToGUI() {
		super.applyPropertiesToGUI();
		noUpdate++;
		try {
			csvFile.setText(getStringProperty("logfile"));
		} finally {
			noUpdate--;
		}
	}
	
	/**
	 * 
	 * @param fn
	 * @param ext the extension with dot
	 * @return
	 */
	private String getTimestampedFilename(String fn, String ext) {
		// remove extension
		//int e = fn.lastIndexOf('.');
		//if (e >= 1) {
		//	ext = fn.substring(e);
		//	fn = fn.substring(0, e);
		//}
		return fn + "_" + getDateAndTime() + ext;
	}

	private void initLogfile(String filename) {
		setProperty("logfile", filename);
		startLogToFile = true;
	}

	private void initWavefile(String filename) {
		setProperty("wavefile", filename);
		startAudioCapture = true;
	}

	private void initTestRun(String testRunName) {
		initLogfile(getTimestampedFilename(testRunName, ".csv"));
		initWavefile(getTimestampedFilename(testRunName, ".wav"));
		setAutoStart(true);
	}

	/* main method */
	public static void main(String[] args) {
		(new LatencyTester("Latency Tester", args)).mainImpl(400, 600);
	}

	/** will be called before setting up the GUI */
	private void parseArguments(String[] args) {
		if (args != null) {
			int argi = 0;
			while (argi < args.length) {
				String arg = args[argi];
				if (arg.equals("-h")) {
					printUsageAndExit();
				} else if (arg.equals("-v")) {
					DEBUG = true;
				} else if (arg.equals("-start")) {
					setAutoStart(true);
				} else if (arg.equals("-duration")) {
					argi++;
					if (argi >= args.length) {
						printUsageAndExit(arg);
					}
					setDuration(Integer.parseInt(args[argi]));
				} else if (arg.equals("-a")) {
					argi++;
					if (argi >= args.length) {
						printUsageAndExit(arg);
					}
					setAudioOutputDevice(args[argi]);
				} else if (arg.equals("-log")) {
					argi++;
					if (argi >= args.length) {
						printUsageAndExit(arg);
					}
					initLogfile(args[argi]);
				} else if (arg.equals("-capture")) {
					argi++;
					if (argi >= args.length) {
						printUsageAndExit(arg);
					}
					initWavefile(args[argi]);
				} else if (arg.equals("-testrun")) {
					argi++;
					if (argi >= args.length) {
						printUsageAndExit(arg);
					}
					initTestRun(args[argi]);
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
		out("java LatencyTester [options]");
		out("Options:");
		out("-start              : start measuring directly");
		out("-h                  : display this help message");
		out("-v                  : verbose");
		out("-a <audio dev>      : override audio output device");
		out("-duration <sec>     : quit this program after <sec> seconds");
		out("-log <file>         : write log file name and activate logging to csv file");
		out("-capture <file>     : capture file name and start capturing wave file");
		out("-testrun <file>     : autostart: write csv log file and write wave file.");
		out("                    : <file> is the base filename without extension. It will be expanded with timestamp and extension.");
		System.exit(1);
	}

}
