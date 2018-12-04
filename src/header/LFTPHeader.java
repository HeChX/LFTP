package header;

public class LFTPHeader {
//	byte[] header;
	byte[] seq;
	byte[] ack;
	byte syn;
	byte fin;
	byte[] rwnd;
	byte[] checksum;
	byte[] dataLength;
	public static int length = 18;
	
	public LFTPHeader(int seq, int ack, int dataLength) {
//		header = new byte[14];
		this.seq = IntToByte(seq);
		this.ack = IntToByte(ack);
		this.rwnd = new byte[2];
		this.checksum = new byte[2];
		this.dataLength = IntToByte(dataLength);
	}
	
	public LFTPHeader(int seq, int ack, byte syn, byte fin, int rwnd, byte[] checksum, int dataLength) {
		this.seq = IntToByte(seq);
		this.ack = IntToByte(ack);
		this.syn = syn;
		this.fin = fin;
		this.rwnd = IntToByte(rwnd);
		this.checksum = checksum;
		this.dataLength = IntToByte(dataLength);
	}
	
	public LFTPHeader(byte[] header) {
		seq = new byte[4];
		ack = new byte[4];
		rwnd = new byte[2];
		checksum = new byte[2];
		dataLength = new byte[4];
		for (int i = 0; i < 4; i++) {
			seq[i] = header[i];
		}
		for (int i = 0; i < 4; i++) {
			ack[i] = header[i + 4];
		}
		syn = header[8];
		fin = header[9];
		for (int i = 0; i < 2; i++) {
			rwnd[i] = header[i + 10];
		}
		for (int i = 0; i < 2; i++) {
			checksum[i] = header[i + 12];
		}
		for (int i = 0; i < 4; i++) {
			dataLength[i] = header[i + 14];
		}
	}
	
	public void setSYN(byte syn) {
		this.syn = syn;
//		header[8] = syn;
	}
	
	public void setFIN(byte fin) {
		this.fin = fin;
//		header[9] = fin;
	}
	
	public void setRWND(int rwnd) {
		byte[] rWindow = IntToByte(rwnd);
		for (int i = 0; i < 2; i++) {
			this.rwnd[i] = rWindow[i + 2];
		}
	}
	
	public void setChecksum(byte[] checksum) {
		this.checksum = checksum;
	}
	
	public void setDataLength(int dataLength) {
		this.dataLength = IntToByte(dataLength);
	}
	
	public int getACK() {
		return Byte2Int(ack);
	}
	
	public int getSEQ() {
		return Byte2Int(seq);
	}
	
	public int getFIN() {
		byte[] temp = new byte[4];
		temp[3] = fin;
		return Byte2Int(temp);
	}
	
	public int getSYN() {
		byte[] temp = new byte[4];
		temp[3] = syn;
		return Byte2Int(temp);
	}
	
	public int getRWND() {
		byte[] temp = new byte[4];
		temp[0] = 0;
		temp[1] = 0;
		for (int i = 0; i < 2; i++) {
			temp[i + 2] = rwnd[i];
		}
		return Byte2Int(temp);
	}
	
	public int getDataLength() {
		return Byte2Int(dataLength);
	}
	
	public byte[] getHeader() {
		byte[] header= new byte[18];
		for (int i = 0; i < 4; i++) {
			header[i] = seq[i];
		}
		for (int i = 0; i < 4; i++) {
			header[i + 4] = ack[i];
		}
		header[8] = syn;
		header[9] = fin;
		for (int i = 0; i < 2; i++) {
			header[i + 10] = rwnd[i];
		}
		for (int i = 0; i < 2; i++) {
			header[i + 12] = checksum[i];
		}
		for (int i = 0; i < 4; i++) {
			header[i + 14] = dataLength[i];
		}
		return header;
	}
	
	public int getHeaderLength() {
		return getHeader().length;
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
}
