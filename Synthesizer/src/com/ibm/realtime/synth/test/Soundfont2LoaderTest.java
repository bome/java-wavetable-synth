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

import java.io.*;
import com.ibm.realtime.synth.soundfont2.*;

/**
 * Test program to load a SoundFont 2 file and to dump information while parsing
 * the file. See Parser.java for trace options.
 * 
 * @author florian
 * 
 */
public class Soundfont2LoaderTest {

	public static final String sfDir = "E:\\TestSounds\\sf2\\";

	//public static final String filename = sfDir + "Chorium.SF2";
	// public static final String filename = sfDir+"classictechno.sf2";
	// public static final String filename = sfDir+"bh_cello.sf2";
	public static final String filename = sfDir + "RealFont_2_1.sf2";

	public static boolean MEM_TEST = false;

	public static long initialMem = 0;

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		Parser.TRACE = true;
		Parser.TRACE_INFO = true;
		Parser.TRACE_RIFF = true;
		Parser.TRACE_RIFF_MORE = true;
		Parser.TRACE_PRESET = true;
		Parser.TRACE_PROCESSOR = true;
		Parser.TRACE_GENERATORS = true;
		Parser.TRACE_MODULATORS = true;
		Parser.TRACE_SAMPLELINKS = true;


		if (!MEM_TEST) {
			new Parser().load(new FileInputStream(new File(filename)));
		} else {
			// Memory test
			initialMem = printMemory("before", false);
			Parser p = new Parser();
			p.load(new FileInputStream(new File(filename)));
			printMemory("after 1st run", true);
			p = new Parser();
			p.load(new FileInputStream(new File(filename)));
			printMemory("after 2nd run", true);
			p = new Parser();
			p.load(new FileInputStream(new File(filename)));
			printMemory("after 3rd run", true);

		}
	}

	private static long printMemory(String text, boolean printOverhead) {
		Runtime rt = Runtime.getRuntime();
		System.gc();
		System.runFinalization();
		System.gc();
		long mem = (rt.totalMemory() - rt.freeMemory()) / 1024;
		long fileSize = (new File(filename)).length() / 1024;
		System.out.println("--------------------------------------");
		System.out.print("Used memory " + text + ": " + mem + " KB");
		if (printOverhead) {
			System.out.println(", class overhead: "
					+ (mem - fileSize - initialMem) + " KB.");
		} else {
			System.out.println(".");
		}
		System.out.println("--------------------------------------");
		return mem;
	}

}
