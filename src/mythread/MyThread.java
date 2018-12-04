package mythread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;

import client.LFTPClient.MyTimer;
import client.LFTPClient.ReadThread;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import header.LFTPHeader;
import javafx.util.Pair;
import jdk.internal.dynalink.beans.StaticClass;
import sun.launcher.resources.launcher;

public class MyThread extends Thread {
	private static DatagramSocket datagramSocket;
	private DatagramPacket datagramPacket;
	private static InetAddress clientAddress;
	private static int clientPort;
	private String command;
	private String filename;
	private int ACK;
	private int SEQ;
	private int fin;
	private String folder;
	private String path;
	
	private static int TIMEOUT = 1000;
	
	private static byte[] RcvBuffer = new byte[256 * 1024];
	private static int LastByteRead = 0;
	private static int LastByteRcvd = 0;
	private static ArrayBlockingQueue<Integer> empty = new ArrayBlockingQueue<>(256);
	private static List<Pair<Integer, Pair<Integer, Integer> > > rcvNotWrite = new ArrayList<>();
	private static ArrayBlockingQueue<Pair<Integer, Integer>> haveData = new ArrayBlockingQueue<>(256);
	private static Map<Integer, MyTimer> timerList = new HashMap<>();
	
	private static InetAddress serverAddress;
	private static int port;
	
	private static int cwnd;
	private static int rwnd;
	private static int ssthresh;
	
	private static byte[] Buffer = new byte[256 * 1024];
	private static int LastByteSend = 0;
	private static int LastByteAcked = 0;
//	private static ArrayBlockingQueue<Integer> empty = new ArrayBlockingQueue<>(256);
	private static List<Pair<Integer, Pair<Integer, Integer> > > unconfirmed = new ArrayList<>();
//	private static ArrayBlockingQueue<Pair<Integer, Integer>> haveData = new ArrayBlockingQueue<>(256);
	private static boolean readFinished = false;
	private static int nextSeqNum;
	private static int ackNum;	
	private static Map<Integer, Integer> redundancy = new HashMap<>();
	private static Map<Integer, MySendTimer> senderTimerList = new HashMap<>();
	
//	private static int packageNum = 0;
	
	public MyThread(DatagramPacket datagramPacket, int port, String folder) throws Exception {
		// TODO Auto-generated constructor stub
		this.datagramSocket = new DatagramSocket(port);
		this.datagramPacket = datagramPacket;
		this.folder = folder;
		clientAddress = datagramPacket.getAddress();
		clientPort = datagramPacket.getPort();
		if (empty.isEmpty()) {
			for (int i = 0; i < 256; i++) {
				empty.add(i);
			}
		}		
		
		byte[] firstPackage = new byte[128];
		firstPackage = datagramPacket.getData();
		LFTPHeader header = new LFTPHeader(firstPackage);
		ACK = header.getACK();
		SEQ = header.getSEQ();
		String packageData = new String(firstPackage, header.getHeaderLength(), header.getDataLength());
		String[] info = packageData.split(",");
		command = info[0];
		filename = info[1].trim();		
		
		String[] tempStr = filename.split("/");
		filename = tempStr[tempStr.length - 1];
		path = folder + filename;
//		System.out.println("Init Path: " + path);
		System.out.println("Command: " + command);
		System.out.println("Filename: " + filename);
		System.out.println("客户端连接成功");
	}
	
	@Override
	public void run() {
		if (command.equals("lsend")) {
			LFTPHeader header = new LFTPHeader(1, 2, 256);
			byte[] head = header.getHeader();
//			File file = new File(path);
			DatagramPacket sendPacket = new DatagramPacket(head, head.length, clientAddress, clientPort);
			LastByteRcvd = 2;
			LastByteRead = 2;
			try {
				datagramSocket.send(sendPacket);
//				System.out.println("发送包的数量: " + packageNum++);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			recevice();
		} else if (command.equals("lget")) {
			send();
		}
		
	}

	private void send() {
		// TODO Auto-generated method stub
		String string = "lsend," + filename;
		byte[] data_byte = string.getBytes();
		LFTPHeader header = new LFTPHeader(ACK + 1, SEQ + 1, data_byte.length);
		header.setSYN((byte)1); 
		header.setFIN((byte)0); 
		byte[] head = header.getHeader();
		
		byte[] firstPackage = byteJoint(head, data_byte);
		try {
			datagramPacket = new DatagramPacket(firstPackage, firstPackage.length, clientAddress, clientPort);
			datagramSocket.send(datagramPacket);	
//			System.out.println("发送包的数量: " + packageNum++);
			
			byte[] buf = new byte[64];
			DatagramPacket recePacket = new DatagramPacket(buf, buf.length);
			datagramSocket.receive(recePacket);
			LFTPHeader receHeader = new LFTPHeader(recePacket.getData());
			if (receHeader.getSYN() != 1) {
				datagramSocket.send(datagramPacket);
//				System.out.println("发送包的数量: " + packageNum++);
				System.out.println("未连接上客户端，重新发送链接请求");
			}
			ACK = receHeader.getACK();
			SEQ = receHeader.getSEQ();

//			System.out.println("Server port: " + recePacket.getPort());
			System.out.println("客户端开启，开始发送文件");
//			port = recePacket.getPort();
			
			cwnd = 1;
			rwnd = receHeader.getRWND();
			ssthresh = 64;
			readFinished = false;
			ReadThread readThread = new ReadThread(path);
			readThread.start();
			boolean continueSend = true;
			boolean isFirstPackageSend = false;
			nextSeqNum = ACK;
			ackNum = SEQ + 1;
			int alreadySendNum = 0;
			int rcvCorrectly = 0;
			while (!readFinished || !haveData.isEmpty() || !unconfirmed.isEmpty()) {
				int canSendNum = cwnd < rwnd ? cwnd : rwnd;
				if (!haveData.isEmpty() && continueSend) {
					sendNewDataPackage();
					alreadySendNum++;
					isFirstPackageSend = true;
				} else if (!isFirstPackageSend || alreadySendNum < canSendNum) {
					continue;
				}
				
				byte[] ackBack = new byte[LFTPHeader.length];
				DatagramPacket receBack = new DatagramPacket(ackBack, ackBack.length);
				datagramSocket.receive(receBack);
				LFTPHeader backHeader = new LFTPHeader(ackBack);
				rwnd = backHeader.getRWND();
				ackNum = backHeader.getSEQ() + 1;
				if (backHeader.getACK() > LastByteAcked) {    //收到的ACK大于LastByteAcked
					LastByteAcked = backHeader.getACK();
					for (int i = 0; i < unconfirmed.size(); i++) {			//从未确认列表中确认收到的包
						if (unconfirmed.get(i).getKey() < LastByteAcked) {
							empty.add(unconfirmed.get(0).getValue().getKey());
							senderTimerList.get(unconfirmed.get(i).getKey()).cancel();
							senderTimerList.remove(unconfirmed.get(i).getKey());
							unconfirmed.remove(0);
							i--;
							rcvCorrectly++;
						}
					}
					if (unconfirmed.isEmpty()) {     //如果未确认列表为空时，即上一次发送的包全部收到，cwnd增加
						if (cwnd < ssthresh) {
							cwnd *= 2;
						} else {
							cwnd += 1;
						}
					}
					if (LastByteSend > LastByteAcked) {   //如果最后被发送的包的序号大于最后被确认收到的包的序号，即有包未被确认
						continueSend = false;
//						retransmisson(LastByteAcked);
					} else {							//最后被发送的包的序号等于最后被确认收到的包的序号，开始新的发送任务
						nextSeqNum = LastByteAcked;
						if (rcvCorrectly == alreadySendNum) {
							continueSend = true;
							alreadySendNum = 0;
							rcvCorrectly = 0;
						} else {
							continueSend = false;
						}
						continue;
					}
				} else if (backHeader.getACK() == LastByteAcked) {		//收到的包的序号等于最后确认被收到的包的序号，则有冗余的ACK
					continueSend = false;
					int count = 0;
					if (redundancy.get(LastByteAcked) == null) {
						redundancy.put(LastByteAcked, 1);
					} else {
						count = redundancy.get(LastByteAcked);
						redundancy.put(LastByteAcked, count + 1);
					}
					if (count + 1 >= 3) {
						retransmisson(LastByteAcked);
					}
					
				}
			}
			LFTPHeader newHeader = new LFTPHeader(nextSeqNum, ackNum, 0);
			newHeader.setFIN((byte) 1);
			DatagramPacket finalPacket = new DatagramPacket(newHeader.getHeader(), newHeader.getHeader().length, clientAddress, clientPort);
			datagramSocket.send(finalPacket);
			System.out.println("文件发送完毕");

		} catch (SocketTimeoutException e) {
			// TODO: handle exception
			
		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}
	}

	private void recevice() {
		// TODO Auto-generated method stub
		List<Pair<Integer, Integer> > receSeq = new ArrayList<>();
		receSeq.add(new Pair<Integer, Integer>(1, 2));	

		try {			

			fin = 0;
			byte[] receData = new byte[1024];
			FileThread writeThread = new FileThread(path);
			writeThread.start();
			while (fin != 1) {
				DatagramPacket recePacket = new DatagramPacket(receData, receData.length);
				datagramSocket.receive(recePacket);
				
				LFTPHeader receHeader = new LFTPHeader(recePacket.getData());
				fin = receHeader.getFIN();
				if (fin == 1) {
					System.out.println("文件接收完毕");
					if (timerList.get(LastByteRcvd) != null) {
						timerList.get(LastByteRcvd).cancel();
						timerList.remove(LastByteRcvd);
					}
					break;
				}
				
//				datagramSocket.
				if (LastByteRcvd == receHeader.getSEQ()) {
					System.out.println("LastByteRcvd: " + LastByteRcvd);
					if (timerList.get(LastByteRcvd) != null) {
						timerList.get(LastByteRcvd).cancel();
						timerList.remove(LastByteRcvd);
					}
					writeToBuffer(receData, receHeader.getHeaderLength(), receHeader.getDataLength());
					int rwnd = 256 - (LastByteRcvd - LastByteRead);
					LFTPHeader newHeader = new LFTPHeader(receHeader.getACK() + 1, LastByteRcvd, rwnd);
					System.out.println("Return package ack: " + LastByteRcvd);
					DatagramPacket sendBack = new DatagramPacket(newHeader.getHeader(), newHeader.getHeader().length, clientAddress, clientPort);
					datagramSocket.send(sendBack);
					MyTimer myTimer = new MyTimer(TIMEOUT, LastByteRcvd, newHeader.getSEQ() + 1);
					timerList.put(LastByteRcvd, myTimer);
				} else if (LastByteRcvd < receHeader.getSEQ()) {
					justToBuffer(receData, receHeader.getHeaderLength(), receHeader.getDataLength(), receHeader.getSEQ());
					int rwnd = 256 - (LastByteRcvd - LastByteRead);
					retransmisson(receHeader.getACK() + 1, LastByteRead, rwnd);
					
				}
			}
//			bufferedWriter.close();
//			fileOutputStream.close();
//			empty = null;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	private static void writeToBuffer(byte[] data, int offset, int length) {
		while (empty.isEmpty()) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		int index = empty.element();
		System.out.println("index: " + index);
		for (int i = 0; i < length; i++) {
			RcvBuffer[index * 1024 + i] = data[offset + i];
		}
		empty.remove();
		haveData.add(new Pair<Integer, Integer>(index, length));
		LastByteRcvd += length;
		if (!rcvNotWrite.isEmpty()) {
			Collections.sort(rcvNotWrite, (Pair<Integer, Pair<Integer, Integer>> p1, Pair<Integer, Pair<Integer, Integer>> p2) -> p1.getKey() - p2.getKey());
			for (int i = 0; i < rcvNotWrite.size(); i++) {
				if (LastByteRcvd == rcvNotWrite.get(i).getKey()) {
					haveData.add(new Pair<Integer, Integer>(rcvNotWrite.get(i).getValue().getKey(), rcvNotWrite.get(i).getValue().getValue()));
					LastByteRcvd += rcvNotWrite.get(i).getValue().getValue();
				}
			}
		}
	}
	
	public static void justToBuffer(byte[] data, int offset, int length, int seq) {
		int index = empty.element();
		for (int i = 0; i < length; i++) {
			RcvBuffer[index * 1024 + i] = data[offset + i];
		}
		empty.remove();
		Pair<Integer, Integer> pair = new Pair<Integer, Integer>(index, length);
		Pair<Integer, Pair<Integer, Integer>> pair2 = new Pair<>(seq, pair);
		rcvNotWrite.add(pair2);
	}
	
	public class FileThread extends Thread {
		private String path;
		
		public FileThread(String path) {
			// TODO Auto-generated constructor stub
			this.path = path;
		}
		
		@Override
		public void run() {
			File file = new File(path);
			
			try {
				if (!file.exists()) {
					file.createNewFile();
				}
				FileOutputStream fileOutputStream = new FileOutputStream(file);
				while (fin != 1 || LastByteRcvd != LastByteRead) {
					if (!haveData.isEmpty()) {
						System.out.println("Write to file index: " + haveData.element().getKey());
						fileOutputStream.write(RcvBuffer, haveData.element().getKey() * 1024, haveData.element().getValue());
						LastByteRead += haveData.element().getValue();
						System.out.println("LastByteRead: " + LastByteRead);
						empty.add(haveData.element().getKey());
						haveData.remove();		
						
					}
				}
				System.out.println("文件写入完毕");
				fileOutputStream.close();
//				empty = null;
			} catch (Exception e) {
				// TODO: handle exception
				e.printStackTrace();
			}
		}
	}
	
	private void retransmisson(int ack, int seq, int rwnd) throws IOException {
		LFTPHeader newHeader = new LFTPHeader(ack, seq, rwnd);
		DatagramPacket sendBack = new DatagramPacket(newHeader.getHeader(), newHeader.getHeader().length, clientAddress, clientPort);
		datagramSocket.send(sendBack);
		System.out.println("中间有包丢失，重发------------------------------------");
	}
	
	private static void retransmisson(int seq) throws IOException {
		if (!unconfirmed.isEmpty()) {
			for (int i = 0; i < unconfirmed.size(); i++) {
				if (seq == unconfirmed.get(i).getKey()) {
					Pair<Integer, Integer> resend = unconfirmed.get(i).getValue();
					LFTPHeader newHeader = new LFTPHeader(seq, ackNum, resend.getValue());
					byte[] packageData = getDataFromBuffer(resend.getKey(), resend.getValue());
					byte[] newPackage = byteJoint(newHeader.getHeader(), packageData);
					DatagramPacket sendPacket = new DatagramPacket(newPackage, newPackage.length, clientAddress, clientPort);
					System.out.println("clientPort: " + clientPort);
					datagramSocket.send(sendPacket);
					System.out.println("Resend seq: " + seq);
					break;
				}
			}
		}
		ssthresh = cwnd / 2;
		cwnd = 1;
		
	}
	
	private static void sendNewDataPackage() throws IOException {
		Pair<Integer, Integer> next = haveData.element();
		LFTPHeader newHeader = new LFTPHeader(nextSeqNum, ackNum, next.getValue());
		byte[] packageData = getDataFromBuffer(next.getKey(), next.getValue());
		byte[] newPackage = byteJoint(newHeader.getHeader(), packageData);
		DatagramPacket sendPacket = new DatagramPacket(newPackage, newPackage.length, clientAddress, clientPort);
		datagramSocket.send(sendPacket);
//		System.out.println("发送包的数量: " + packageNum++);
		System.out.println("Send seq: " + nextSeqNum);
//		System.out.println("Send package's length " + newHeader.getDataLength());
		MySendTimer myTimer = new MySendTimer(TIMEOUT, nextSeqNum);
		senderTimerList.put(nextSeqNum, myTimer);
		LastByteSend = nextSeqNum + next.getValue();
		unconfirmed.add(new Pair<Integer, Pair<Integer,Integer>>(nextSeqNum, next));
		haveData.remove();
	}
	
	private static byte[] getDataFromBuffer(int offset, int length) {
		byte[] data = new byte[length];
		for (int i = 0; i < length; i++) {
			data[i] = Buffer[offset * 1024 + i];
		}
		return data;
	}
	
	public static class ReadThread extends Thread {
		private String path;
		
		public ReadThread(String path) {
			this.path = path;
		}
		
		@Override
		public void run() {
			File file = new File(path);
			
			try {
				if (!file.exists()) {
					file.createNewFile();
				}
				FileInputStream fileInputStream = new FileInputStream(file);
				System.out.println("Path: " + path);
				int byteRead = 0;
				int dataSize = 1024 - LFTPHeader.length;
				byte[] readData = new byte[dataSize];
				while (byteRead != -1) {
					if (!empty.isEmpty()) {
						byteRead = fileInputStream.read(readData);
						if (byteRead == -1) break;
						int index = empty.element();
//						System.out.println("empty index: " + index);
						for (int i = 0; i < byteRead; i++) {
							Buffer[index * 1024 + i] = readData[i];
						}
						haveData.add(new Pair<Integer, Integer>(index, byteRead));
						System.out.println("index: " + index);
						empty.remove();
					} else {
						Thread.sleep(10);
					}
				}
				System.out.println("文件已全部读取到缓冲区");
				readFinished = true;
				fileInputStream.close();
			} catch (IOException | InterruptedException e) {
				// TODO: handle exception
				e.printStackTrace();
			}
		}
	}
	
	public class MyTimer {
		Timer timer;
		
		public MyTimer(int timeout, int seq, int ack) {
			timer = new Timer();
			timer.schedule(new TimerTask() {
				
				@Override
				public void run() {
					// TODO Auto-generated method stub
					try {
						int rwnd = 256 - (LastByteRcvd - LastByteRead);
						retransmisson(ack, seq, rwnd);
						TIMEOUT *= 2;
						System.out.println("超时重发");
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}, TIMEOUT, TIMEOUT);
		}
		
		public void cancel() {
			timer.cancel();
		}
	}
	
	public static class MySendTimer {
		Timer timer;
		
		public MySendTimer(int timeout, int seq) {
			timer = new Timer();
			timer.schedule(new TimerTask() {
				
				@Override
				public void run() {
					// TODO Auto-generated method stub
					try {
						retransmisson(seq);
						TIMEOUT *= 2;
						System.out.println("超时重发");
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}, TIMEOUT, TIMEOUT);
		}
		
		public void cancel() {
			timer.cancel();
		}
	}
	
	public static int Byte2Int(byte[]bytes) {
		return (bytes[0]&0xff)<<24
			| (bytes[1]&0xff)<<16
			| (bytes[2]&0xff)<<8
			| (bytes[3]&0xff);
	}
	
	public static byte[]IntToByte(int num){
		byte[]bytes=new byte[4];
		bytes[0]=(byte) ((num>>24)&0xff);
		bytes[1]=(byte) ((num>>16)&0xff);
		bytes[2]=(byte) ((num>>8)&0xff);
		bytes[3]=(byte) (num&0xff);
		return bytes;
	}
	
	public static byte[] byteJoint(byte[] first, byte[] second) {
		byte[] dest = new byte[first.length + second.length];
		System.arraycopy(first, 0, dest, 0, first.length);
		System.arraycopy(second, 0, dest, first.length, second.length);
		return dest;
	}
} 
