package com.alex.meca500;

import java.util.function.Supplier;

/**
 * Robots selectable in the simulator's dropdown.
 *
 * @author Alex Vazquez <vazqueza2000@gmail.com>
 */
public enum RobotType {

	MECA500("Meca500", Meca500::new),
	UR10G_1750("UR10g-1750", UR10g1750::new);

	private final String displayName;
	private final Supplier<RobotModel> factory;

	RobotType(String displayName, Supplier<RobotModel> factory) {
		this.displayName = displayName;
		this.factory = factory;
	}

	/** Builds a new instance (loads its STLs, which can take a few seconds). */
	public RobotModel create() {
		return factory.get();
	}

	public String getDisplayName() {
		return displayName;
	}

	/** Parses a saved name, falling back to the given default if it is missing or unknown. */
	public static RobotType fromName(String name, RobotType fallback) {
		if (name == null) return fallback;
		try {
			return valueOf(name);
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	@Override
	public String toString() {
		return displayName;
	}

}
