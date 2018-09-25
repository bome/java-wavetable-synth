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

import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JFrame;
import static com.ibm.realtime.synth.utils.Debug.debug;

/**
 * A quick&dirty visual interface for the SoundFont 2 synthesizer engine.
 * 
 * @author florian
 * 
 */
public class SoundFont2GUI {
	private static final String NAME = "Harmonicon";

	public SoundFont2GUI() {
		JFrame.setDefaultLookAndFeelDecorated(true);
		Toolkit.getDefaultToolkit().setDynamicLayout(true);
		System.setProperty("sun.awt.noerasebackground", "true");
		final JFrame frame = new JFrame();
		frame.setTitle(NAME);
		final SFGPane pane = new SFGPane(NAME);
		WindowAdapter windowAdapter = new WindowAdapter() {
			public void windowClosing(WindowEvent we) {
				if (pane != null) {
					if (frame != null) {
						Dimension d = frame.getSize();
						pane.setProperty("frameWidth", d.width);
						pane.setProperty("frameHeight", d.height);
						frame.setVisible(false);
					}
					pane.close();
				}
				debug(NAME + " exit.");
				System.exit(0);
			}
		};
		frame.addWindowListener(windowAdapter);
		frame.setSize(new Dimension(pane.getIntProperty("frameWidth", 600),
				pane.getIntProperty("frameHeight", 500)));
		
		pane.createGUI();
		frame.getContentPane().add(pane);
		frame.setVisible(true);
		pane.init();
		pane.start();
	}

	public static void main(String[] args) {
		try {
			new SoundFont2GUI();
		} catch (Throwable t) {
			System.err.println(NAME + ": Exception occurred in main():");
			t.printStackTrace();
			System.exit(1);
		}
	}
}
