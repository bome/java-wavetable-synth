/*
 * From jsresources.org:
 *
 * (c) 1997-2002 by Florian Bomers
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.ibm.realtime.synth.gui;

import java.awt.*;

/**
 * A Layout-manager that displays the added components in lines. The width of
 * this layout is determined by the widest component that was added. The height
 * of each stripe is the preferred height of the respective component.<BR>
 */
public class StripeLayout implements LayoutManager {

	protected int leftEdge;
	protected int topEdge;
	protected int rightEdge;
	protected int bottomEdge;
	protected int spacer;

	public StripeLayout() {
		this(0, 0, 0, 0);
	}

	/**
	 * Constructs a StripeLayout with left, right, top, and bottom edge of
	 * <tt>edge</tt>
	 */
	public StripeLayout(int edge) {
		this(edge, edge, edge, edge);
	}

	/**
	 * Constructs a StripeLayout with the given edges.
	 */
	public StripeLayout(int leftEdge, int topEdge, int rightEdge, int bottomEdge) {
		this(leftEdge, topEdge, rightEdge, bottomEdge, 0);
	}

	/**
	 * Constructs a StripeLayout with the given edges and spacer
	 */
	public StripeLayout(int leftEdge, int topEdge, int rightEdge,
			int bottomEdge, int spacer) {
		this.leftEdge = leftEdge;
		this.topEdge = topEdge;
		this.rightEdge = rightEdge;
		this.bottomEdge = bottomEdge;
		this.spacer = spacer;
	}

	public void addLayoutComponent(String name, Component comp) {
	}

	/**
	 * enleve le layout.
	 */
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

	/**
	 * calcul les dimensions du layout.
	 * 
	 * @param preferred pour utilisation des dimensions preferees
	 * @return the dimension of this layout
	 */
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
				if (dim.width < d.width) dim.width = d.width;
				dim.height += d.height;
				if (i < (nmembers - 1)) {
					dim.height += spacer;
				}
			}
		}
		Insets insets = getTotalInsets(parent);
		dim.width += insets.left + insets.right;
		dim.height += insets.top + insets.bottom;
		return dim;
	}

	/**
	 * retourne les dimensions preferees pour le layout. compte tenu de la
	 * taille des composants Les places dans le container cible
	 * 
	 * @param target le composant a appliquer sur le layout
	 */
	public Dimension preferredLayoutSize(Container target) {
		return calcSize(target, true);
	}

	/**
	 * retourne les dimensions minimales pour le layout. compte tenu de la
	 * taille des composants. Les places dans le container cible
	 * 
	 * @param target le composant a appliquer sur le layout *
	 * @see #preferredLayoutSize
	 */

	public Dimension minimumLayoutSize(Container target) {
		return calcSize(target, false);
	}

	/**
	 * application du layout sur le container.
	 * 
	 * @param target le composant a appliquer sur le layout
	 */
	public void layoutContainer(Container target) {
		// insanity check,
		Insets insets = getTotalInsets(target);

		int x = insets.left;
		int y = insets.top;

		int width = target.getWidth() - insets.left - insets.right;
		int maxY = target.getSize().height - insets.bottom - insets.top;

		int nmembers = target.getComponentCount();
		for (int i = 0; i < nmembers && y < maxY; i++) {
			Component m = target.getComponent(i);
			if (m.isVisible()) {
				int height = m.getPreferredSize().height;
				if (height < m.getMinimumSize().height) {
					height = m.getMinimumSize().height;
				}
				m.setBounds(x, y, width, height);
				y += m.getHeight();
				if (i < (nmembers - 1)) {
					y += spacer;
				}
			}
		}
	}
}
