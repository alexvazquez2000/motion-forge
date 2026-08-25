package com.adc.lighting;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;


/**
 * @author Alex Vazquez <vazqueza2000@gmail.com>
 */
public class TcpClient {

	// Define the destination server address and port number
	private static final String SERVER_HOST = "192.168.18.13"; 
	private static final int SERVER_PORT = 50000;

	public static void main(String[] args) {
		System.out.println("Attempting to connect to the server...");

		// Try-with-resources handles automatic closing of network assets
		try (
				Socket socket = new Socket(SERVER_HOST, SERVER_PORT);

				// Stream to send text data to the server (auto-flush enabled)
				PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

				// Stream to read incoming text data from the server
				BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))
				) {
			System.out.println("Connected successfully to " + SERVER_HOST + ":" + SERVER_PORT);

			// 1. Send a text message to the server
			String messageToSend = "Hello Server! This is a TCP message.";
			out.println(messageToSend);
			System.out.println("Sent: " + messageToSend);

			// 2. Read the server's reply
			String serverResponse = in.readLine();
			if (serverResponse != null) {
				System.out.println("Received from server: " + serverResponse);
			} else {
				System.out.println("Server closed the connection without responding.");
			}

		} catch (UnknownHostException e) {
			System.err.println("Error: Could not find host " + SERVER_HOST);
			e.printStackTrace();
		} catch (IOException e) {
			System.err.println("I/O Error during communication: " + e.getMessage());
			e.printStackTrace();
		}

		System.out.println("Client disconnected.");
	}
}
