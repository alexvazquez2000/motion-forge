package com.motionforge;

import com.motionforge.kinematics.KinematicsModel;

import javafx.scene.Group;

/**
 * A robot the simulator can display: kinematics plus its 3D node and a few UI defaults.
 *
 * @author Alex Vazquez <vazqueza2000@gmail.com>
 */
public interface RobotModel extends KinematicsModel {

	/** Root node of the robot's 3D model. */
	Group getNode();

	/** Default camera distance (mm) that frames the whole robot. */
	double getCameraDistance();

	/** Half-ranges (mm) for the TCP X, Y, Z sliders: each slider spans [-limit, +limit]. */
	double[] getTcpPositionLimits();

}
