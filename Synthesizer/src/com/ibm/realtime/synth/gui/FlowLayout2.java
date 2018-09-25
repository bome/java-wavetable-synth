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

/**
 * A Layout-manager that displays the added components in one line. The height of
 * this layout is determined by the highest component that was added. The width
 * of each component is its preferred width, but at least its minimum width.<BR>
 */
public class FlowLayout2 implements LayoutManager {

	protected int leftEdge;
	protected int topEdge;
	protected int rightEdge;
	protected int bottomEdge;
	protected int spacer;

	public FlowLayout2() {
		this(0, 0, 0, 0);
	}

	/**
	 * Constructs a FlowLayout2 with left, right, top, and bottom edge of
	 * <tt>edge</tt>
	 */
	public FlowLayout2(int edge) {
		this(edge, edge, edge, edge);
	}

	/**
	 * Constructs a FlowLayout2 with the given edges.
	 */
	public FlowLayout2(int leftEdge, int topEdge, int rightEdge, int bottomEdge) {
		this(leftEdge, topEdge, rightEdge, bottomEdge, 0);
	}

	/**
	 * Constructs a FlowLayout2 with the given edges and spacer
	 */
	public FlowLayout2(int leftEdge, int topEdge, int rightEdge,
			int bottomEdge, int spacer) {
		this.leftEdge = leftEdge;
		this.topEdge = topEdge;
		this.rightEdge = rightEdge;
		this.bottomEdge = bottomEdge;
		this.spacer = spacer;
	}

	public void addLayoutComponent(String name, Component comp) {
	}

	public void removeLayoutComponent(Component comp) {
	}

	protected Insets getTotalInsets(Container parent) {
		Insets insets = parent.getInsets();
		insets.left += leftEdge;
		insets.right += rightEdge;
		insets.top += topEdge;
		insets.bottom += bottomEdge;
		return insets;
	}

	private Dimension calcSize(Container parent, boolean preferred) {

		Dimension dim = new Dimension(0, 0);
		int nmembers = parent.getComponentCount();
		Dimension d;
		for (int i = 0; i < nmembers; i++) {
			Component m = parent.getComponent(i);
			if (m.isVisible()) {
				d = m.getMinimumSize();
				if (preferred) {
					// respect minimum width and height
					Dimension p = m.getPreferredSize();
					if (p.width > d.width) {
						d.width = p.width;
					}
					if (p.height > d.height) {
						d.height = p.height;
					}
				}
				if (dim.height < d.height) dim.height = d.height;
				dim.width += d.width;
				if (i < (nmembers - 1)) {
					dim.width += spacer;
				}
			}
		}
		Insets insets = getTotalInsets(parent);
		dim.width += insets.left + insets.right;
		dim.height += insets.top + insets.bottom;
		return dim;
	}

	public Dimension preferredLayoutSize(Container target) {
		return calcSize(target, true);
	}

	public Dimension minimumLayoutSize(Container target) {
		return calcSize(target, false);
	}

	public void layoutContainer(Container target) {
		Insets insets = getTotalInsets(target);

		int x = insets.left;
		int y = insets.top;

		int maxX = target.getWidth() - insets.left - insets.right;
		int height = target.getSize().height - insets.bottom - insets.top;

		int nmembers = target.getComponentCount();
		for (int i = 0; i < nmembers && x < maxX; i++) {
			Component m = target.getComponent(i);
			if (m.isVisible()) {
				int width = m.getPreferredSize().width;
				if (width < m.getMinimumSize().width) {
					width = m.getMinimumSize().width;
				}
				m.setBounds(x, y, width, height);
				x += m.getWidth();
				if (i < (nmembers - 1)) {
					x += spacer;
				}
			}
		}
	}
}
