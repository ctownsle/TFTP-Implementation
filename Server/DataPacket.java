package Server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import static Constants.Constants.ACK_OPCODE;
import static Constants.Constants.ACK_SIZE;

public class DataPacket implements Comparable<DataPacket>{
    // | Opcode |   Block #  |   Data     | Data Packet
    private short seqNum;
    private byte [] data;
    // need to store data
    public DataPacket(final short seqNum){
        this.seqNum = seqNum;
    }

    public void processAndSendAck(final DatagramSocket socket, final ByteBuffer buffer, final int retPort,
                        final InetAddress address, final byte finalWindowByte,
                                  final int blockSize, final boolean lastPacket, final int windowSize,
                                  final boolean windowDrop) throws IOException{

        byte [] ack;
        ByteBuffer ackBuffer = ByteBuffer.allocate(ACK_SIZE);
        ackBuffer.putShort(ACK_OPCODE);
        if(!windowDrop) {
            // windowDrop checks if we received a seqnum that wasn't expected, if thats the case, send what we expected,
            // not what we expect next
            seqNum += 1;
        }
        ackBuffer.putShort(seqNum);

        data = new byte[blockSize];
        buffer.get(data);
        ack = ackBuffer.array();
        DatagramPacket ackPacket = new DatagramPacket(ack, ack.length, address, retPort);
        if(finalWindowByte == 1 || lastPacket || windowSize == 0) {
            // if its the last packet, final Packet in window, or we're using sequential acks, send ack
            socket.send(ackPacket);
        }
    }

    @Override
    public int compareTo(DataPacket dataPacket) {
        // ended up not using this, here for remembrance of failed sliding window
        if(seqNum == dataPacket.getSeqNum()){
            return 0;
        } else if(seqNum < dataPacket.seqNum){
            return 1;
        } else {
            return 0;
        }

    }

    public short getSeqNum() {
        return seqNum;
    }

    public byte[] getData() {
        return data;
    }
}
