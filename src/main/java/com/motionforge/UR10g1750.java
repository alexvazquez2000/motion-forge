package com.motionforge;

import com.motionforge.MainApp.MainFxApp;
import com.motionforge.kinematics.DHParameter;

import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Sphere;

/**
 * Universal Robots UR10g-1750 6-DOF arm — visual model and kinematics model.
 *
 * STLs are exported from the assembly STEP by src/main/python/convert_ur10_step_stl.py.
 * The STEP is Z-up; the export rotates it -90 deg about X so the files are Y-up on disk,
 * like the Meca500 STLs. StlMeshImporter then flips meshes on load, (x, y, z) -> (x, -y, -z),
 * so in JavaFX a STEP point (x, y, z) sits at (x, -z, y): up = -Y.
 * Pivot coordinates below are in that loaded frame (mm).
 *
 * Visual zero (all joints 0) is the CAD pose: arm pointing straight up.
 *
 * DH kinematics are expressed in the robot's base frame (STEP frame, Z-up), using the
 * standard UR DH layout. The theta offsets on J2 and J4 (-90 deg) make the DH zero pose
 * match the CAD pose, so slider value 0 means the same thing visually and kinematically.
 *
 * @author Alex Vazquez <vazqueza2000@gmail.com>
 */
public class UR10g1750 implements RobotModel {

	// Measured from the STEP joint axes (see convert_ur10_step_stl.py):
	private static final double D1 = 218.6;   // base plane -> J2 axis
	private static final double A2 = 898.9;   // J2 axis -> J3 axis (upper arm)
	private static final double A3 = 714.9;   // J3 axis -> J4 axis (forearm)
	private static final double D4 = 182.44;  // lateral offset base axis -> J5 axis
	private static final double D5 = 136.1;   // J4 axis -> J6 axis
	private static final double D6 = 143.96;  // J5 axis -> flange face

	// Standard DH: a (mm), alpha (rad), d (mm), theta_offset (rad)
	private static final DHParameter[] DH_PARAMS = {
			new DHParameter(  0,  Math.PI / 2, D1,            0),
			new DHParameter(-A2,            0,  0, -Math.PI / 2),
			new DHParameter(-A3,            0,  0,            0),
			new DHParameter(  0,  Math.PI / 2, D4, -Math.PI / 2),
			new DHParameter(  0, -Math.PI / 2, D5,            0),
			new DHParameter(  0,            0, D6,            0)
	};

	private static final double LIM = Math.toRadians(360);
	private static final double[] JOINT_MIN = { -LIM, -LIM, -LIM, -LIM, -LIM, -LIM };
	private static final double[] JOINT_MAX = {  LIM,  LIM,  LIM,  LIM,  LIM,  LIM };

	// Rotation axes in the loaded mesh frame. Each is the DH z-axis of that joint,
	// so a positive visual rotation matches a positive kinematic rotation.
	private static final Point3D AXIS_UP    = new Point3D(0, -1, 0); // J1, J5 (vertical in CAD pose)
	private static final Point3D AXIS_SIDE  = new Point3D(0, 0, -1); // J2, J3, J4, J6

	private final double[] currentAngles = new double[6]; // radians

	// Set to true to show colored spheres at each joint pivot for debugging
	public static final boolean SHOW_PIVOTS = true;

	private static final Color[] PIVOT_COLORS = {
			Color.RED, Color.ORANGE, Color.YELLOW,
			Color.GREEN, Color.CYAN, Color.MAGENTA
	};

	private RobotPart[] joints = new RobotPart[6];
	private Group root = new Group();


	public UR10g1750() {
		RobotPart[] parts = new RobotPart[7];
		parts[0] = new RobotPart(STLLoader.load("/UR10/Link0_base.stl"));   // base + cable connector
		parts[1] = new RobotPart(STLLoader.load("/UR10/Link1.stl"));        // shoulder
		parts[2] = new RobotPart(STLLoader.load("/UR10/Link2.stl"));        // upper arm
		parts[3] = new RobotPart(STLLoader.load("/UR10/Link3.stl"));        // forearm
		parts[4] = new RobotPart(STLLoader.load("/UR10/Link4.stl"));        // wrist 1
		parts[5] = new RobotPart(STLLoader.load("/UR10/Link5.stl"));        // wrist 2
		parts[6] = new RobotPart(STLLoader.load("/UR10/Link6_flange.stl")); // wrist 3 + tool connector

		String[] names = {"link0_base","link1","link2","link3","link4","link5","link6_flange"};
		for (int i = 0; i < parts.length; i++) MainFxApp.printBounds(names[i], parts[i].getMesh());

		// --- Joint parameters: (axis, pivotX, pivotY, pivotZ) ---
		// Exact values from the coaxial cylindrical faces in the STEP (not bounding-box estimates).
		// Pivots are in the loaded mesh frame (mm, up = -Y).
		// For Y-axis joints only pivotX/pivotZ matter; for Z-axis joints only pivotX/pivotY matter.
		configureJoint(parts[1], AXIS_UP,   0,     0,       0);       // J1 base
		configureJoint(parts[2], AXIS_SIDE, 0,  -218.6f,    0);       // J2 shoulder
		configureJoint(parts[3], AXIS_SIDE, 0, -1117.5f,    0);       // J3 elbow
		configureJoint(parts[4], AXIS_SIDE, 0, -1832.4f,    0);       // J4 wrist 1
		configureJoint(parts[5], AXIS_UP,   0, -1904.8f, -182.44f);   // J5 wrist 2 (y = wrist1/wrist2 seam, free)
		configureJoint(parts[6], AXIS_SIDE, 0, -1968.5f,    0);       // J6 wrist 3 / flange (face at z=-326.4)

		// Build parent-child hierarchy
		root.getChildren().add(parts[0].getNode());
		for (int i = 1; i < parts.length; i++) {
			parts[i - 1].getNode().getChildren().add(parts[i].getNode());
		}

		for (int i = 0; i < 6; i++) {
			joints[i] = parts[i + 1];
		}

		if (SHOW_PIVOTS) addPivotMarkers(parts);
	}

	private void configureJoint(RobotPart part, Point3D axis,
			float px, float py, float pz) {
		part.setJointParams(axis, px, py, pz);
	}

	private void addPivotMarkers(RobotPart[] parts) {
		for (int i = 0; i < 6; i++) {
			Sphere s = new Sphere(15); // UR10 is ~7x the size of the Meca500
			s.setMaterial(new PhongMaterial(PIVOT_COLORS[i]));
			// Place the sphere at the pivot position inside the link's local frame
			float[] pivot = parts[i + 1].getPivot();
			s.setTranslateX(pivot[0]);
			s.setTranslateY(pivot[1]);
			s.setTranslateZ(pivot[2]);
			parts[i + 1].getNode().getChildren().add(s);
		}
	}

	@Override
	public Group getNode() {
		return root;
	}

	@Override
	public double getCameraDistance() { return 5000; }

	// Fully stretched the flange is ~2.05 m from the base origin.
	@Override
	public double[] getTcpPositionLimits() { return new double[] { 2100, 2100, 2100 }; }

	/** Sets joint i (0-based) in degrees and updates the visual model. */
	public void setJoint(int i, double angle) {
		joints[i].setRotation(angle);
	}

	// --- KinematicsModel ---

	@Override
	public DHParameter[] getDHParameters() { return DH_PARAMS; }

	@Override
	public double[] getJointAngles() { return currentAngles.clone(); }

	@Override
	public void setJointAngle(int i, double angleRad) {
		currentAngles[i] = angleRad;
		setJoint(i, Math.toDegrees(angleRad));
	}

	@Override
	public double[] getJointMin() { return JOINT_MIN; }

	@Override
	public double[] getJointMax() { return JOINT_MAX; }

}
