package server;

import java.util.ArrayList;
import java.util.List;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import mythread.MyThread;

public class LFTPServer {
	private static DatagramPacket datagramPacket;
	public static DatagramSocket datagramSocket;
	private static List<String> clientAddress;
	private static List<MyThread> threadList;
	private static int nextPort;
	private static String folder = "E:/ComputerNetWork/server/";
	
	public static void main(String args[]) throws Exception {
		datagramPacket = null;
		datagramSocket = null;
		datagramSocket = new DatagramSocket(9092);
		nextPort = 10000;
		threadList = new ArrayList<>();
		clientAddress = new ArrayList<>();
		
		byte[] receData = new byte[1024];
		datagramPacket = new DatagramPacket(receData, receData.length);
		System.out.println("等待客户端连接……");
		while (true) {
			datagramSocket.receive(datagramPacket);
			String receString = new String(datagramPacket.getData(), 0, datagramPacket.getLength());
			String[] string = receString.split(",");

			System.out.println("There are a new client connected");
//			System.out.println("Port: " + nextPort);
			MyThread myThread = new MyThread(datagramPacket, nextPort, folder);
			myThread.start();
			nextPort++;
			
			
			if (string[0].equals("-1")) break;
		}
		
		datagramSocket.close();

//		}
	}
	
}
