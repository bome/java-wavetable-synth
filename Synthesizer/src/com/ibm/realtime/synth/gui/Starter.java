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

import java.awt.*;
import java.awt.event.*;

import javax.swing.*;

/**
 * A Starter app for Harmonicon: manage all components
 * used by Harmonicon.
 * 
 * @author florian
 *
 */
public class Starter extends JPanel {
	// implements ItemListener, ActionListener, ChangeListener

	private static final String NAME = "Harmonicon Starter";
	
	private JFrame frame;
	
	public Starter(String[] args) {
		createFrame();
		createGUI();
		this.setOpaque(true);
		frame.setContentPane(this);
		frame.setVisible(true);
		init();
	}
	
	private void createGUI() {
		
	}
	
	private void init() {
		
	}
	
	public void close() {
		
	}

	private void createFrame() {
		JFrame.setDefaultLookAndFeelDecorated(true);
	Toolkit.getDefaultToolkit().setDynamicLayout(true);
	System.setProperty("sun.awt.noerasebackground", "true");
	frame = new JFrame();
	frame.setTitle(NAME);
	WindowAdapter windowAdapter = new WindowAdapter() {
		public void windowClosing(WindowEvent we) {
				if (frame != null) {
					//Dimension d = frame.getSize();
					//pane.setProperty("frameWidth", d.width);
					//pane.setProperty("frameHeight", d.height);
					frame.setVisible(false);
				}
				Starter.this.close();
			
			System.exit(0);
		}
	};
	frame.addWindowListener(windowAdapter);
	//frame.setSize(new Dimension(getIntProperty("frameWidth", 400),
	//		getIntProperty("frameHeight", 500)));
	
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		new Starter(args);
	}

	private static final long serialVersionUID = 0;
}
