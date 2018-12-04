package client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;

import javax.sound.sampled.Clip;

import header.LFTPHeader;
import javafx.util.Pair;
import mythread.MyThread;
import mythread.MyThread.FileThread;
import mythread.MyThread.MyTimer;


public class LFTPClient {
	private static DatagramSocket datagramSocket;
	private static DatagramPacket datagramPacket;
	private static InetAddress serverAddress;
	private static int port;
	private static String folder = "E:/ComputerNetWork/client/";
	
	private static int TIMEOUT = 1000;
	private static int cwnd;
	private static int rwnd;
	private static int ssthresh;
	
	private static byte[] Buffer = new byte[256 * 1024];
	private static int LastByteSend = 0;
	private static int LastByteAcked = 0;
//	private static List<Integer> empty = new ArrayList<>();
	private static ArrayBlockingQueue<Integer> empty = new ArrayBlockingQueue<>(256);
	private static List<Pair<Integer, Pair<Integer, Integer> > > unconfirmed = new ArrayList<>();
	private static ArrayBlockingQueue<Pair<Integer, Integer>> haveData = new ArrayBlockingQueue<>(256);
	private static boolean readFinished = false;
	private static int nextSeqNum;
	private static int ackNum;
	
	private static Map<Integer, Integer> redundancy = new HashMap<>();	
	private static Map<Integer, MyTimer> timerList = new HashMap<>();
	
	private static InetAddress clientAddress;
	private static int clientPort;
	private String command;
	private String filename;
	private int ACK;
	private int SEQ;
	private static int fin;
	private static String path;
	
	private static byte[] RcvBuffer = new byte[256 * 1024];
	private static int LastByteRead = 0;
	private static int LastByteRcvd = 0;
	private static List<Pair<Integer, Pair<Integer, Integer> > > rcvNotWrite = new ArrayList<>();
	private static Map<Integer, MyReceviceTimer> rcvTimerList = new HashMap<>();
	
	public static void main(String args[]) throws Exception {
		String operation = null;
		String server_address = null;
		String filename = null;
		if (args.length != 3) {
			System.out.println("Invalid parameters");
		} else {
			operation = args[0];
			server_address = args[1];
			filename = args[2];
		}
		serverAddress = InetAddress.getByName(server_address);
		datagramSocket = new DatagramSocket(20000);
//		datagramSocket.setSoTimeout(TIMEOUT);
		datagramPacket = null;
		if (operation.equals("lsend")) {
			upload(filename, server_address);
		} else if (operation.equals("lget")) {
			download(filename, server_address);
		}
		
	}
	
	private static void upload(String filename, String server_address) {
		String string = "lsend," + filename;
		byte[] data_byte = string.getBytes();
		LFTPHeader header = new LFTPHeader(1, 1, data_byte.length);
		header.setSYN((byte)1); 
		header.setFIN((byte)0); 
		byte[] head = header.getHeader();
		
		byte[] firstPackage = byteJoint(head, data_byte);
		try {
			datagramPacket = new DatagramPacket(firstPackage, firstPackage.length, InetAddress.getByName(server_address), 9092);
			datagramSocket.send(datagramPacket);	
			
			byte[] buf = new byte[64];
			DatagramPacket recePacket = new DatagramPacket(buf, buf.length);
			datagramSocket.receive(recePacket);
			LFTPHeader receHeader = new LFTPHeader(recePacket.getData());
			if (receHeader.getACK() != 2) {
				datagramSocket.send(datagramPacket);
				System.out.println("�����δ���������·�����������");
			}

			System.out.println("Server port: " + recePacket.getPort());
			System.out.println("����˿�������ʼ�����ļ�");
			port = recePacket.getPort();
			
			for (int i = 0; i < 256; i++) {
				empty.add(i);
			}
			cwnd = 1;
			rwnd = receHeader.getRWND();
			ssthresh = 64;
			ReadThread readThread = new ReadThread(filename);
			readThread.start();
			boolean continueSend = true;
			boolean isFirstPackageSend = false;
			nextSeqNum = receHeader.getACK();
			ackNum = receHeader.getSEQ() + 1;
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
				if (backHeader.getACK() > LastByteAcked) {    //�յ���ACK����LastByteAcked
					LastByteAcked = backHeader.getACK();
					for (int i = 0; i < unconfirmed.size(); i++) {			//��δȷ���б���ȷ���յ��İ�
						if (unconfirmed.get(i).getKey() < LastByteAcked) {
							empty.add(unconfirmed.get(0).getValue().getKey());
							timerList.get(unconfirmed.get(i).getKey()).cancel();
							timerList.remove(unconfirmed.get(i).getKey());
							unconfirmed.remove(0);
							i--;
							rcvCorrectly++;
						}
					}
					if (unconfirmed.isEmpty()) {     //���δȷ���б�Ϊ��ʱ������һ�η��͵İ�ȫ���յ���cwnd����
						if (cwnd < ssthresh) {
							cwnd *= 2;
						} else {
							cwnd += 1;
						}
					}
					if (LastByteSend > LastByteAcked) {   //�����󱻷��͵İ�����Ŵ������ȷ���յ��İ�����ţ����а�δ��ȷ��
						continueSend = false;
//						retransmisson(LastByteAcked);
					} else {							//��󱻷��͵İ�����ŵ������ȷ���յ��İ�����ţ���ʼ�µķ�������
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
				} else if (backHeader.getACK() == LastByteAcked) {		//�յ��İ�����ŵ������ȷ�ϱ��յ��İ�����ţ����������ACK
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
			DatagramPacket finalPacket = new DatagramPacket(newHeader.getHeader(), newHeader.getHeader().length, InetAddress.getByName(server_address), port);
			datagramSocket.send(finalPacket);
			System.out.println("�ļ��������");

		} catch (SocketTimeoutException e) {
			// TODO: handle exception
			
		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}
	}
	
	private static void download(String filename, String server_address) {
		path = filename;
		String string = "lget," + filename;
		byte[] data_byte = string.getBytes();
		LFTPHeader header = new LFTPHeader(1, 1, data_byte.length);
		header.setSYN((byte)1); 
		header.setFIN((byte)0); 
		byte[] head = header.getHeader();
		byte[] firstPackage = byteJoint(head, data_byte);
		
		try {
			datagramPacket = new DatagramPacket(firstPackage, firstPackage.length, InetAddress.getByName(server_address), 9092);
			datagramSocket.send(datagramPacket);
			byte[] receData = new byte[1024];
			DatagramPacket packet = new DatagramPacket(receData, receData.length);
			datagramSocket.receive(packet);
			clientAddress = packet.getAddress();
			clientPort = packet.getPort();
			
			LFTPHeader lftpHeader = new LFTPHeader(packet.getData());
			LastByteRcvd = lftpHeader.getACK() + 1;
			LastByteRead = LastByteRcvd;
			
			LFTPHeader newheader = new LFTPHeader(LastByteRcvd, lftpHeader.getSEQ() + 1, 0);
			newheader.setSYN((byte) 1);
			newheader.setRWND(256);
			byte[] newhead = newheader.getHeader();
//			File file = new File(path);
			DatagramPacket sendPacket = new DatagramPacket(newhead, newhead.length, clientAddress, clientPort);
			
			try {
				datagramSocket.send(sendPacket);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			for (int i = 0; i < 256; i++) {
				empty.add(i);
			}
			
			recevice();

		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}
		
		
	}
	
	private static void recevice() {
		// TODO Auto-generated method stub
		List<Pair<Integer, Integer> > receSeq = new ArrayList<>();
		receSeq.add(new Pair<Integer, Integer>(1, 2));	

		try {			

			fin = 0;
			byte[] receData = new byte[1024];
			FileThread writeThread = new FileThread(path);
			writeThread.start();
			while (fin != 1) {
//				System.out.println("�ȴ�����");
				DatagramPacket recePacket = new DatagramPacket(receData, receData.length);
				datagramSocket.receive(recePacket);
				
				LFTPHeader receHeader = new LFTPHeader(recePacket.getData());
				fin = receHeader.getFIN();
				if (fin == 1) {
					System.out.println("�ļ��������");
					if (rcvTimerList.get(LastByteRcvd) != null) {
						rcvTimerList.get(LastByteRcvd).cancel();
						rcvTimerList.remove(LastByteRcvd);
					}
					break;
				}
				
//				datagramSocket.
				if (LastByteRcvd == receHeader.getSEQ()) {
					System.out.println("LastByteRcvd: " + LastByteRcvd);
					if (rcvTimerList.get(LastByteRcvd) != null) {
						rcvTimerList.get(LastByteRcvd).cancel();
						rcvTimerList.remove(LastByteRcvd);
					}
					writeToBuffer(receData, receHeader.getHeaderLength(), receHeader.getDataLength());
					int rwnd = 256 - (LastByteRcvd - LastByteRead);
					LFTPHeader newHeader = new LFTPHeader(receHeader.getACK() + 1, LastByteRcvd, rwnd);
					System.out.println("Return package ack: " + LastByteRcvd);
					DatagramPacket sendBack = new DatagramPacket(newHeader.getHeader(), newHeader.getHeader().length, clientAddress, clientPort);
					datagramSocket.send(sendBack);
					MyReceviceTimer myTimer = new MyReceviceTimer(TIMEOUT, LastByteRcvd, newHeader.getSEQ() + 1);
					rcvTimerList.put(LastByteRcvd, myTimer);
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
		System.out.println("ready to write to buffer " + empty.isEmpty());
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
	
	public static class FileThread extends Thread {
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
				System.out.println("�ļ�д�����");
				fileOutputStream.close();
//				empty = null;
			} catch (Exception e) {
				// TODO: handle exception
				e.printStackTrace();
			}
		}
	}
	
	private static void retransmisson(int ack, int seq, int rwnd) throws IOException {
		LFTPHeader newHeader = new LFTPHeader(ack, seq, rwnd);
		DatagramPacket sendBack = new DatagramPacket(newHeader.getHeader(), newHeader.getHeader().length, clientAddress, clientPort);
		datagramSocket.send(sendBack);
		System.out.println("�м��а���ʧ���ط�------------------------------------");
	}
	
	public static class MyReceviceTimer {
		Timer timer;
		
		public MyReceviceTimer(int timeout, int seq, int ack) {
			timer = new Timer();
			timer.schedule(new TimerTask() {
				
				@Override
				public void run() {
					// TODO Auto-generated method stub
					try {
						int rwnd = 256 - (LastByteRcvd - LastByteRead);
						retransmisson(ack, seq, rwnd);
						TIMEOUT *= 2;
						System.out.println("��ʱ�ط�");
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
	
	
	private static void retransmisson(int seq) throws IOException {
		if (!unconfirmed.isEmpty()) {
			for (int i = 0; i < unconfirmed.size(); i++) {
				if (seq == unconfirmed.get(i).getKey()) {
					Pair<Integer, Integer> resend = unconfirmed.get(i).getValue();
					LFTPHeader newHeader = new LFTPHeader(seq, ackNum, resend.getValue());
					byte[] packageData = getDataFromBuffer(resend.getKey(), resend.getValue());
					byte[] newPackage = byteJoint(newHeader.getHeader(), packageData);
					DatagramPacket sendPacket = new DatagramPacket(newPackage, newPackage.length, serverAddress, port);
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
		DatagramPacket sendPacket = new DatagramPacket(newPackage, newPackage.length, serverAddress, port);
		datagramSocket.send(sendPacket);
		System.out.println("Send seq: " + nextSeqNum);
		MyTimer myTimer = new MyTimer(TIMEOUT, nextSeqNum);
		timerList.put(nextSeqNum, myTimer);
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
						empty.remove();
					} else {
						Thread.sleep(10);
					}
				}
				System.out.println("�ļ���ȫ����ȡ��������");
				readFinished = true;
				fileInputStream.close();
			} catch (IOException | InterruptedException e) {
				// TODO: handle exception
				e.printStackTrace();
			}
		}
	}

	
	public static class MyTimer {
		Timer timer;
		
		public MyTimer(int timeout, int seq) {
			timer = new Timer();
			timer.schedule(new TimerTask() {
				
				@Override
				public void run() {
					// TODO Auto-generated method stub
					try {
						retransmisson(seq);
						TIMEOUT *= 2;
						System.out.println("��ʱ�ط�");
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


