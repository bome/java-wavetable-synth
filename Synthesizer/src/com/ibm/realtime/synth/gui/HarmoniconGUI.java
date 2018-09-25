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

import org.eclipse.swt.events.ShellListener;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.events.ShellEvent;
import org.eclipse.swt.graphics.Point;
import static com.ibm.realtime.synth.utils.Debug.*;

public class HarmoniconGUI {

	private static HarmoniconPane harmoniconPane = null;
	private static Display display = null;
	private static Shell shell = null;
	
	private static void closeWindowImpl(boolean disposeWindow) {
		if (!display.isDisposed()) {
			if (harmoniconPane != null) {
				if (shell != null && !shell.isDisposed()) {
					Point d = shell.getSize();
					harmoniconPane.setProperty("frameWidth", d.x);
					harmoniconPane.setProperty("frameHeight", d.y);
				}
				harmoniconPane.close(); // Kill harmonicon threads
			}
		}
		if (disposeWindow) {
			if (shell != null && !shell.isDisposed()) {
				shell.dispose();
			}
			if (display != null && !display.isDisposed()) {
				display.dispose();
			}
		}
	}

	public static void main(String[] args) {
		try {
			harmoniconPane = new HarmoniconPane("Harmonicon", args);
			display = new Display();
			shell = new Shell(display);
			shell.setSize(800, 800);
			shell.setText("Harmonicon");
			shell.setLayout(new FillLayout());
			harmoniconPane.createPartControl(shell);
			shell.addShellListener(new ShellListener() {
				public void shellActivated(ShellEvent e) {
				}

				public void shellClosed(ShellEvent e) {
					closeWindowImpl(false);
				}

				public void shellDeactivated(ShellEvent e) {
				}

				public void shellDeiconified(ShellEvent e) {
				}

				public void shellIconified(ShellEvent e) {
				}
			});
			harmoniconPane.setCloseListener(new HarmoniconPane.CloseListener() {
				public void closeWindow() {
						closeWindowImpl(true);
				}
			});
			shell.setSize(harmoniconPane.getIntProperty("frameWidth", 400),
					harmoniconPane.getIntProperty("frameHeight", 500));
			shell.open();
			harmoniconPane.init(); // Init properties
			harmoniconPane.start(); // Start rendering threads
			while (!shell.isDisposed()) {
				if (!display.readAndDispatch()) {
					display.sleep();
				}
			}
			// make sure we don't call dispose() before the harmoniconPane
			// has finished the close() operation!
			harmoniconPane.close();
			if (!display.isDisposed()) {
				display.dispose();
			}
		} catch (Throwable t) {
			error(t);
		}
	}
}
