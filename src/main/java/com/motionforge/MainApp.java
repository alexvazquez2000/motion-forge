package com.motionforge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;

import com.sun.net.httpserver.HttpServer;

import com.motionforge.api.RestApiServer;
import com.motionforge.api.RobotController;
import com.motionforge.kinematics.DHKinematics;
import com.motionforge.kinematics.IKSolver;
import com.motionforge.kinematics.TcpPose;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;

/**
 * Use Launcher to run, or run this class with the JavaFX maven plugin:  mvn javafx:run
 *
 * @author Alex Vazquez <vazqueza2000@gmail.com>
 */
public class MainApp {

	public static void main(String[] args) {
		Application.launch(MainFxApp.class, args);
	}

	public static class MainFxApp extends Application {

		private static final String PREF_ROBOT = "robot";
		private final Preferences prefs = Preferences.userNodeForPackage(MainApp.class);

		private RobotModel robot;
		private RobotType robotType;
		// Robots already built this session, so switching back does not reload STLs
		private final Map<RobotType, RobotModel> robotCache = new EnumMap<>(RobotType.class);
		// Holds the current robot's node inside the world group
		private final Group robotHolder = new Group();

		private Stage stage;
		private Slider zoomSlider;
		private ComboBox<RobotType> robotCombo;
		private Label robotLoadingLabel;

		// Sliders and text fields for joint angles
		private final Slider[]    jointSliders = new Slider[6];
		private final TextField[] jointFields  = new TextField[6];

		// Sliders and text fields for TCP pose: X, Y, Z, Alpha, Beta, Gamma
		private final Slider[]    tcpSliders = new Slider[6];
		private final TextField[] tcpFields  = new TextField[6];

		// Guards against FK→TCP→IK→joint feedback loops
		private boolean updatingFromCode = false;

		private HttpServer httpServer;
		private RobotController controller;
		private RestApiServer restApiServer;
		private Circle statusDot;
		private Label statusLabel;

		@Override
		public void start(Stage stage) {
			this.stage = stage;

			// Fail fast if another instance is already running: try to claim the
			// fixed control port before building any of the 3D scene.
			try {
				httpServer = HttpServer.create(new InetSocketAddress(8500), 0);
			} catch (IOException e) {
				new Alert(AlertType.ERROR,
						"Port 8500 is already in use -- another instance of Motion Forge "
						+ "may already be running. Close it and try again.",
						ButtonType.OK).showAndWait();
				Platform.exit();
				System.exit(1);
				return;
			}
			httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

			Group world = new Group();
			// STLs are in STEP assembly frame (Y-up). Flip to JavaFX Y-down.
			world.getTransforms().add(new Rotate(180, Rotate.Y_AXIS));

			// Start with the robot picked last time (Meca500 on first run)
			robotType = RobotType.fromName(prefs.get(PREF_ROBOT, null), RobotType.MECA500);
			robot = robotType.create();
			robotCache.put(robotType, robot);
			robotHolder.getChildren().setAll(robot.getNode());
			world.getChildren().add(robotHolder);

			PointLight light = new PointLight();
			light.setTranslateX(500);
			light.setTranslateY(-500);
			light.setTranslateZ(500);
			world.getChildren().add(light);
			world.getChildren().add(new AmbientLight());

			PerspectiveCamera cam = new PerspectiveCamera(true);
			cam.setNearClip(0.1);
			cam.setFarClip(10000);
			cam.setTranslateZ(-robot.getCameraDistance());

			SubScene subScene = new SubScene(world, 800, 600, true, SceneAntialiasing.BALANCED);
			subScene.setCamera(cam);

			Pane subSceneWrapper = new Pane(subScene);
			subScene.widthProperty().bind(subSceneWrapper.widthProperty());
			subScene.heightProperty().bind(subSceneWrapper.heightProperty());

			statusDot = new Circle(6, Color.GRAY);
			statusLabel = new Label("Disconnected");
			HBox statusIndicator = new HBox(6, statusDot, statusLabel);
			statusIndicator.setAlignment(Pos.CENTER_RIGHT);
			statusIndicator.setPadding(new Insets(10));
			statusIndicator.setMouseTransparent(true);

			StackPane overlay = new StackPane(subSceneWrapper, statusIndicator);
			StackPane.setAlignment(statusIndicator, Pos.TOP_RIGHT);
			VBox.setVgrow(overlay, Priority.ALWAYS);

			VBox jointCol = buildJointColumn();
			VBox tcpCol    = buildTcpColumn();

			HBox.setHgrow(jointCol, Priority.ALWAYS);
			HBox.setHgrow(tcpCol,    Priority.ALWAYS);

			HBox bottomPanel = new HBox(8, jointCol, tcpCol);

			HBox zoomRow = buildZoomRow(cam);
			HBox robotRow = buildRobotRow();
			HBox.setHgrow(zoomRow, Priority.ALWAYS);
			HBox bottomRow = new HBox(16, robotRow, zoomRow);
			bottomRow.setAlignment(Pos.CENTER_LEFT);
			bottomRow.setPadding(new Insets(4, 8, 8, 8));

			// Size sliders for the robot and initialise TCP fields from FK
			applyRobotToUi();

			VBox root = new VBox(overlay, bottomPanel, bottomRow);
			Scene scene = new Scene(root, 900, 800);

			for (String iconRes : new String[] {"/motion-forge-16.png", "/motion-forge-32.png", "/motion-forge-48.png", "/motion-forge-128.png", "/motion-forge-256.png"}) {
				stage.getIcons().add(new Image(getClass().getResourceAsStream(iconRes)));
			}

			controller = new RobotController(robot, this::applyJointFrame, this::updateConnectionIndicator);
			restApiServer = new RestApiServer(httpServer, controller);
			restApiServer.registerRoutes();
			httpServer.start();

			stage.setScene(scene);
			stage.show();
		}

		@Override
		public void stop() {
			if (httpServer != null) httpServer.stop(0);
		}

		// ------------------------------------------------------------------ //
		//  Joint column                                                      //
		// ------------------------------------------------------------------ //
		private VBox buildJointColumn() {
			VBox col = new VBox(4);
			col.getChildren().add(new Label("Joints"));
			String[] labels = new String[] {"Base", "Shoulder", "Elbow", "Wrist 1", "Wrist 2", "Flange" };
			for (int i = 0; i < 6; i++) {
				int idx = i;

				Slider s = makeSlider(-180, 180, 0, 30, 5);
				TextField tf = makeTextField();
				jointSliders[idx] = s;
				jointFields[idx]  = tf;

				s.valueProperty().addListener((obs, oldV, newV) -> {
					double deg = newV.doubleValue();
					robot.setJointAngle(idx, Math.toRadians(deg));
					tf.setText(String.format("%.0f", deg));

					if (!updatingFromCode) {
						updatingFromCode = true;
						TcpPose pose = DHKinematics.computeTcpPose(
								robot.getDHParameters(), robot.getJointAngles());
						applyTcpPoseToSliders(pose);
						updatingFromCode = false;
					}
				});

				tf.setOnAction(e -> setSliderFromField(tf, s));

				HBox row = new HBox(8, new Label(labels[i]), s, tf);
				HBox.setHgrow(s, Priority.ALWAYS);
				col.getChildren().add(row);
			}
			return col;
		}

		// ------------------------------------------------------------------ //
		//  TCP column (X, Y, Z, Alpha, Beta, Gamma)                           //
		// ------------------------------------------------------------------ //

		private static final String[] TCP_LABELS = {"X (mm)", "Y (mm)", "Z (mm)", "Alpha °", "Beta °", "Gamma °"};
		// X/Y/Z ranges here are placeholders; applyRobotToUi() resizes them per robot.
		private static final double[] TCP_MIN    = {-300, -300, -600, -180,  -90, -180};
		private static final double[] TCP_MAX    = { 300,  300,  600,  180,   90,  180};
		private static final double[] TCP_TICK   = {  50,   50,   50,   30,   30,   30};

		private VBox buildTcpColumn() {
			VBox col = new VBox(4);
			//TCP Pose refers to the exact position (translation) and orientation (rotation) of a robot's Tool Center Point in 3D space
			col.getChildren().add(new Label("TCP Pose"));
			for (int i = 0; i < 6; i++) {
				int idx = i;

				Slider s = makeSlider(TCP_MIN[i], TCP_MAX[i], 0, TCP_TICK[i], 4);
				TextField tf = makeTextField();
				tcpSliders[idx] = s;
				tcpFields[idx]  = tf;

				s.valueProperty().addListener((obs, oldV, newV) -> {
					tf.setText(String.format("%.1f", newV.doubleValue()));

					if (!updatingFromCode) {
						TcpPose target = readTcpFromSliders();
						double[] newAngles = IKSolver.solve(
								robot.getDHParameters(), target,
								robot.getJointAngles(),
								robot.getJointMin(), robot.getJointMax());
						applyJointFrame(newAngles);
					}
				});

				tf.setOnAction(e -> setSliderFromField(tf, s));

				HBox row = new HBox(8, new Label(TCP_LABELS[idx]), s, tf);
				HBox.setHgrow(s, Priority.ALWAYS);
				col.getChildren().add(row);
			}
			return col;
		}

		// ------------------------------------------------------------------ //
		//  Helpers                                                             //
		// ------------------------------------------------------------------ //

		/**
		 * Applies a full set of joint angles (radians) to the robot and both slider
		 * columns, the same way a manual TCP-slider drag does. Used by the TCP-slider
		 * IK listener and by the REST-driven motion animator so both paths stay in
		 * sync. Must only be called on the JavaFX Application Thread.
		 */
		public void applyJointFrame(double[] anglesRad) {
			updatingFromCode = true;
			for (int j = 0; j < 6; j++) {
				double deg = Math.toDegrees(anglesRad[j]);
				robot.setJointAngle(j, anglesRad[j]);
				jointSliders[j].setValue(deg);
				jointFields[j].setText(String.format("%.0f", deg));
			}
			TcpPose achieved = DHKinematics.computeTcpPose(robot.getDHParameters(), robot.getJointAngles());
			applyTcpPoseToSliders(achieved);
			updatingFromCode = false;
		}

		/** Updates the top-right connection indicator. Must only be called on the JavaFX Application Thread. */
		public void updateConnectionIndicator(boolean isConnected) {
			statusDot.setFill(isConnected ? Color.LIMEGREEN : Color.GRAY);
			statusLabel.setText(isConnected ? "Connected" : "Disconnected");
		}

		private void applyTcpPoseToSliders(TcpPose p) {
			double[] vals = { p.x(), p.y(), p.z(), p.alpha(), p.beta(), p.gamma() };
			String[] fmts = { "%.1f","%.1f","%.1f","%.1f","%.1f","%.1f" };
			for (int i = 0; i < 6; i++) {
				tcpSliders[i].setValue(vals[i]);
				tcpFields[i].setText(String.format(fmts[i], vals[i]));
			}
		}

		private TcpPose readTcpFromSliders() {
			return new TcpPose(
					tcpSliders[0].getValue(), tcpSliders[1].getValue(), tcpSliders[2].getValue(),
					tcpSliders[3].getValue(), tcpSliders[4].getValue(), tcpSliders[5].getValue());
		}

		// ------------------------------------------------------------------ //
		//  Zoom row (camera distance along -Z)                                //
		// ------------------------------------------------------------------ //
		private static final double ZOOM_MIN = 100;
		private static final double ZOOM_MAX = 6000;

		private HBox buildZoomRow(PerspectiveCamera cam) {
			// Slider shows distance from the origin; camera sits at z = -distance.
			Slider s = zoomSlider = makeSlider(ZOOM_MIN, ZOOM_MAX, -cam.getTranslateZ(), 500, 4);
			TextField tf = makeTextField();
			tf.setText(String.format("%.0f", s.getValue()));

			cam.translateZProperty().bind(s.valueProperty().negate());
			s.valueProperty().addListener((obs, oldV, newV) ->
					tf.setText(String.format("%.0f", newV.doubleValue())));
			tf.setOnAction(e -> setSliderFromField(tf, s));

			HBox row = new HBox(8, new Label("Zoom (camera distance, mm)"), s, tf);
			HBox.setHgrow(s, Priority.ALWAYS);
			row.setAlignment(Pos.CENTER_LEFT);
			return row;
		}

		// ------------------------------------------------------------------ //
		//  Robot selection                                                    //
		// ------------------------------------------------------------------ //
		private HBox buildRobotRow() {
			robotCombo = new ComboBox<>();
			robotCombo.getItems().setAll(RobotType.values());
			robotCombo.setValue(robotType);
			robotCombo.setOnAction(e -> {
				RobotType picked = robotCombo.getValue();
				if (picked != null && picked != robotType) switchRobot(picked);
			});
			robotLoadingLabel = new Label();

			HBox row = new HBox(8, new Label("Robot"), robotCombo, robotLoadingLabel);
			row.setAlignment(Pos.CENTER_LEFT);
			return row;
		}

		/** Switches to another robot, loading its STLs in the background the first time. */
		private void switchRobot(RobotType type) {
			RobotModel cached = robotCache.get(type);
			if (cached != null) {
				showRobot(type, cached);
				return;
			}

			robotCombo.setDisable(true);
			robotLoadingLabel.setText("Loading " + type.getDisplayName() + "...");

			Task<RobotModel> load = new Task<>() {
				@Override protected RobotModel call() { return type.create(); }
			};
			load.setOnSucceeded(e -> {
				robotCache.put(type, load.getValue());
				showRobot(type, load.getValue());
				robotLoadingLabel.setText("");
				robotCombo.setDisable(false);
			});
			load.setOnFailed(e -> {
				robotLoadingLabel.setText("");
				robotCombo.setDisable(false);
				robotCombo.setValue(robotType); // revert; equals current type so no switch is triggered
				new Alert(AlertType.ERROR, "Could not load " + type.getDisplayName() + ": "
						+ load.getException().getMessage(), ButtonType.OK).show();
			});

			Thread t = new Thread(load, "robot-loader");
			t.setDaemon(true);
			t.start();
		}

		/** Makes the given (already built) robot the active one. FX thread only. */
		private void showRobot(RobotType type, RobotModel model) {
			robotType = type;
			robot = model;
			robotHolder.getChildren().setAll(model.getNode());
			controller.switchRobot(model);
			applyRobotToUi();
			zoomSlider.setValue(model.getCameraDistance());
			prefs.put(PREF_ROBOT, type.name());
		}

		/** Sizes the joint/TCP sliders for the current robot and syncs them to its pose. */
		private void applyRobotToUi() {
			stage.setTitle("Motion Forge - " + robotType.getDisplayName());

			updatingFromCode = true;
			double[] jMin = robot.getJointMin();
			double[] jMax = robot.getJointMax();
			for (int i = 0; i < 6; i++) {
				double lo = Math.max(-360, Math.toDegrees(jMin[i]));
				double hi = Math.min( 360, Math.toDegrees(jMax[i]));
				jointSliders[i].setMin(lo);
				jointSliders[i].setMax(hi);
				jointSliders[i].setMajorTickUnit(hi - lo > 360 ? 90 : 30);
			}

			double[] lim = robot.getTcpPositionLimits();
			for (int i = 0; i < 3; i++) {
				tcpSliders[i].setMin(-lim[i]);
				tcpSliders[i].setMax(lim[i]);
				tcpSliders[i].setMajorTickUnit(lim[i] > 600 ? 500 : 50);
			}
			updatingFromCode = false;

			// Re-apply the robot's own pose (range changes above may have clamped slider values)
			applyJointFrame(robot.getJointAngles());
		}

		private static Slider makeSlider(double min, double max, double val,
				double majorTick, int minorCount) {
			Slider s = new Slider(min, max, val);
			s.setShowTickMarks(true);
			s.setShowTickLabels(true);
			s.setMajorTickUnit(majorTick);
			s.setMinorTickCount(minorCount);
			return s;
		}

		private static TextField makeTextField() {
			TextField tf = new TextField("0");
			tf.setPrefWidth(70);
			tf.setAlignment(Pos.CENTER);
			return tf;
		}

		private static void setSliderFromField(TextField tf, Slider s) {
			try {
				double val = Double.parseDouble(tf.getText());
				if (val >= s.getMin() && val <= s.getMax()) {
					s.setValue(val);
				} else {
					tf.setText(String.format("%.1f", s.getValue()));
				}
			} catch (NumberFormatException ex) {
				tf.setText(String.format("%.1f", s.getValue()));
			}
		}

		public static void printBounds(String name, MeshView meshView) {
			TriangleMesh mesh = (TriangleMesh) meshView.getMesh();
			float[] pts = mesh.getPoints().toArray(null);
			float minX = Float.MAX_VALUE;
			float minY = Float.MAX_VALUE;
			float minZ = Float.MAX_VALUE;
			float maxX = -Float.MAX_VALUE;
			float maxY = -Float.MAX_VALUE;
			float maxZ = -Float.MAX_VALUE;
			for (int i = 0; i < pts.length; i += 3) {
				minX = Math.min(minX, pts[i]);   maxX = Math.max(maxX, pts[i]);
				minY = Math.min(minY, pts[i+1]); maxY = Math.max(maxY, pts[i+1]);
				minZ = Math.min(minZ, pts[i+2]); maxZ = Math.max(maxZ, pts[i+2]);
			}
			System.out.printf("%-20s  X[%7.1f, %7.1f]  Y[%7.1f, %7.1f]  Z[%7.1f, %7.1f]%n",
					name, minX, maxX, minY, maxY, minZ, maxZ);
		}
	}

}
