package Server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import static Constants.Constants.*;

public class WRQPacket {

//+-------+---~~---+---+---~~---+---+---~~---+---+---~~---+---+-->
//        |  opc  |filename| 0 |  mode  | 0 |  opt1  | 0 | value1 | 0 | <
//      +-------+---~~---+---+---~~---+---+---~~---+---+---~~---+---+-->
    //>-------+---+---~~---+---+
    //  <  optN  | 0 | valueN | 0 |


    // Store important variables like windowSize, fileName, blockSize
    private short seqNum;
    private int blockSize;
    private String fileName;
    private int windowSize;

    public WRQPacket(){
        blockSize = 0;
    }

    public void sendACK(final DatagramSocket socket,final ByteBuffer buffer, final int retPort,
                        final InetAddress address, final int totalDataInPacket) throws IOException{
        seqNum = 0;
        byte [] ack;
        ByteBuffer ackBuffer = ByteBuffer.allocate(ACK_SIZE);
        ackBuffer.putShort(ACK_OPCODE);
        ackBuffer.putShort(seqNum);
        byte [] senderWindowBytes = new byte[SENDER_WINDOW_SIZE.length()];
        int fileNameSize = totalDataInPacket - (MODE + ZERO_BYTES + BLOCK_SIZE.getBytes().length + (2 * Integer.BYTES) + OPCODE + SENDER_WINDOW_SIZE.length() );
        byte [] fileNameBytes = new byte[fileNameSize];
        byte [] modeBytes = new byte[MODE];
        byte [] blk = new byte[BLOCK_SIZE.length()];
        // process buffer
        buffer.get(fileNameBytes);
        buffer.get();
        buffer.get(modeBytes);
        buffer.get();
        buffer.get(blk);
        buffer.get();

        blockSize = buffer.getInt();
        buffer.get();
        buffer.get(senderWindowBytes);
        buffer.get();

        windowSize = buffer.getInt();
        fileName = new String(fileNameBytes);
        ack = ackBuffer.array();
        // always send ack
        DatagramPacket ackPacket = new DatagramPacket(ack, ack.length, address, retPort);
        socket.send(ackPacket);

    }


    public int getBlockSize() {
        return blockSize;
    }

    public String getFileName() {
        return fileName;
    }

    public int getWindowSize(){
        return windowSize;
    }
}
