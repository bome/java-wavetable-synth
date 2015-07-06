/*
 * (C) Copyright IBM Corp. 2005, 2008. All Rights Reserved
 */
package com.ibm.realtime.synth.soundfont2;

/**
 * A zone on instrument level. This zone typically defines the sample to be used
 * by this instrument in this zone.
 * 
 * @author florian
 * 
 */
public class SoundFontInstrumentZone extends SoundFontZone {

	/**
	 * The associated sample for this zone. Will be null for global zones or
	 * preset zones.
	 */
	private SoundFontSample sample;
	
	/**
	 * The associated zone. If this zone is the master, the linked
	 * zone is the master and vice versa.
	 */
	private SoundFontInstrumentZone zoneLink;

	/**
	 * Constructor for an instrument zone
	 * 
	 * @param generators
	 * @param modulators
	 * @param sample
	 */
	public SoundFontInstrumentZone(SoundFontGenerator[] generators,
			SoundFontModulator[] modulators, SoundFontSample sample) {
		super(generators, modulators);
		this.sample = sample;
	}

	/**
	 * @return Returns the sample.
	 */
	public SoundFontSample getSample() {
		return sample;
	}
	
	/**
	 * @return Returns the zoneLink.
	 */
	public SoundFontInstrumentZone getZoneLink() {
		return zoneLink;
	}

	/**
	 * Set this zone as being the master zone in a linked
	 * list of zones (currently only supported for stereo links).
	 * Calling this method will invalidate the other zone so that it
	 * will not be used directly anymore.
	 */
	public void setMasterZone(SoundFontInstrumentZone slaveZone) {
		zoneLink = slaveZone;
		slaveZone.zoneLink = this;
		slaveZone.makeInaccessible();
	}
	
	public boolean isValid() {
		return (sample != null) && (keyMin>=0);
	}

	public boolean isGlobalZone() {
		return (keyMin >= 0) && (sample == null);
	}

	public String toString() {
		String ret = super.toString();
		if (sample != null) {
			ret += " using sample: " + sample.getName();
		}
		return ret;
	}

}
