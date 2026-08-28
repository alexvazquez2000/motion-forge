package com.adc.arduino;


import com.fazecast.jSerialComm.SerialPort;
import java.io.InputStream;
import java.io.OutputStream;


/**
 * ESP32 PLC 58 troubleshooting
 * https://www.industrialshields.com/blog/arduino-industrial-1/esp32-based-plc-with-windows-macintosh-troubleshooting-363
 * 
 * Need the CP210x Universal Windows Driver
 * 	https://www.silabs.com/software-and-tools/usb-to-uart-bridge-vcp-drivers?tab=downloads
 * 
 * https://github.com/Fazecast/jSerialComm/wiki/Usage-Examples
 * 
 * @author Alex Vazquez <vazqueza2000@gmail.com>
 */
//Suppress infinite loop warning
@SuppressWarnings({"java:S2189"})
public class SerialCommunication {
	public static void main(String[] args) {
		// 1. List and select available COM ports
		SerialPort[] ports = SerialPort.getCommPorts();
		System.out.println("Available Ports:");
		for (int i = 0; i < ports.length; i++) {
			System.out.println(i + ": " + ports[i].getSystemPortName() + " - " + ports[i].getDescriptivePortName());
		}

		if (ports.length == 0) {
			System.out.println("No serial ports found.");
			return;
		}

		// Select the first available port (e.g., COM3 or /dev/ttyUSB0)
		SerialPort comPort = ports[0];

		// 2. Configure port parameters
		comPort.setBaudRate(115200);
		comPort.setNumDataBits(8);
		comPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
		comPort.setParity(SerialPort.NO_PARITY);

		// 3. Open the port
		if (comPort.openPort()) {
			System.out.println("Successfully opened port: " + comPort.getSystemPortName());
		} else {
			System.err.println("Failed to open the port.");
			return;
		}

		// Set read timeout logic (semi-blocking)
		//comPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 0);
		//comPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 10000, 1000);
		//Nonblocking is the default and not necessary, unless we already had a different mode enabled
		//comPort.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
		
		// 4. Use Input/Output Streams to transfer data
		try (InputStream in = comPort.getInputStream();
				OutputStream out = comPort.getOutputStream()) {

//			// Write data out
//			String greeting = "Hello Device\n";
//			out.write(greeting.getBytes());
//			out.flush();
//			System.out.println("Sent: " + greeting.trim());
//
			while (true) {
				
				while (comPort.bytesAvailable() == 0)
					Thread.sleep(20);

				byte[] readBuffer = new byte[comPort.bytesAvailable()];
				int numRead = comPort.readBytes(readBuffer, readBuffer.length);
				//System.out.println("Read " + numRead + " bytes.");
				String response = new String(readBuffer, 0, numRead);
				System.out.print(response);

			}
			//
//			// Read response data
//			byte[] readBuffer = new byte[1024];
//			int numRead = in.read(readBuffer);
//			if (numRead > 0) {
//				String response = new String(readBuffer, 0, numRead);
//				System.out.println("Received: " + response.trim());
//			}

		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			// 5. Always close the port when done
			comPort.closePort();
			System.out.println("Port closed.");
		}
	}
}
