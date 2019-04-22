import Server.DataPacket;
import Server.WRQPacket;
import ZipProcess.UnzipUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;


import static Constants.Constants.*;

public class UDPServer {

    protected static DatagramSocket socket = null;
    private static int slidingWindow = 0;
    private static File file;
    // each char for mode is 1 byte (mode is 5 bytes), because it's always "octet"
    //| Opcode |  Filename  |   0  |    Mode    |   0  | RWQ packet
    // ----------------------------------
    // | Opcode |   Block #  |   Data     | Data Packet

   //  ---------------------
   //          | Opcode |   Block #  | ack Packet
   //         ---------------------

    // ----------------------------------
    public static void main(String[] args) throws IOException{
        socket = new DatagramSocket(PORT);
        int blockSize = 0;
        int wrqSize = OPCODE + DATA_SIZE + MODE + ZERO_BYTES + BLOCK_SIZE.length() + (2 * Integer.BYTES) + SENDER_WINDOW_SIZE.length(); // leaves enough room for data packets as well
        byte [] potentialPacket = new byte[wrqSize];
        byte [] dataPacket;
        WRQPacket wrqPacket = null;
        DatagramPacket packet = new DatagramPacket(potentialPacket, potentialPacket.length);
        socket.receive(packet);
        byte [] data = packet.getData();
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.get();
        byte opCode = buffer.get();
        if (opCode == 2){
            // write request
            wrqPacket = new WRQPacket();
            wrqPacket.sendACK(socket, buffer, packet.getPort(), packet.getAddress(), packet.getLength());
            blockSize = wrqPacket.getBlockSize();
            slidingWindow = wrqPacket.getWindowSize();

        } else if(opCode == 3){
            System.out.println("Connection refused");
            System.exit(1);
        } else {
            // either not supported or nonexistent implementation
            System.out.println("Operation not supported");
            System.exit(1);
        }
        int dataInPacket = blockSize + SEQ_NUM + OPCODE;
        int sum = 0;
        dataPacket = new byte[dataInPacket];
        ArrayList<DataPacket> dps = new ArrayList<>();
        short currentSeq = 1;
        int expectedData = blockSize + OPCODE + SEQ_NUM;
        boolean finalPacket = false;

        while (dataInPacket ==  expectedData){
            System.out.println(dataInPacket);
            packet = new DatagramPacket(dataPacket, dataPacket.length);
            int wrqCount = 0;
            socket.receive(packet);
            data = packet.getData();
            dataInPacket = packet.getLength();
            if (dataInPacket < expectedData) finalPacket = true;
            System.out.println(dataInPacket);
            int currentBlockSize = dataInPacket - (OPCODE + SEQ_NUM);
            sum += currentBlockSize;
            buffer = ByteBuffer.wrap(data);
            byte finalWindowByte = buffer.get();
            opCode = buffer.get();
            if(opCode == 3){
                 //good
                short seqNum = buffer.getShort();
                if(seqNum == currentSeq) {

                    DataPacket dp = new DataPacket(seqNum);
                    dp.processAndSendAck(socket, buffer, packet.getPort(), packet.getAddress(), finalWindowByte, currentBlockSize, finalPacket, slidingWindow, false);
                    dps.add(dp);
                    currentSeq++;

                } else {

                    DataPacket dp = new DataPacket(currentSeq);
                    dp.processAndSendAck(socket, buffer, packet.getPort(), packet.getAddress(), finalWindowByte, currentBlockSize, finalPacket, slidingWindow, true);
                }

            } else if(opCode == 2){
                wrqCount++;
                if(wrqCount >= 2){
                    // send ack
                    wrqPacket = new WRQPacket();
                    wrqPacket.sendACK(socket, buffer, packet.getPort(), packet.getAddress(), packet.getLength());
                    blockSize = wrqPacket.getBlockSize();
                    slidingWindow = wrqPacket.getWindowSize();
                }
            } else {
                System.out.println("ERROR : System closing");
                System.exit(1);
            }
        }

        socket.close();
        ByteBuffer fileBuf = ByteBuffer.allocate(sum);
        String dir = System.getProperty("user.dir");
        file = new File(dir, wrqPacket.getFileName());
        for (DataPacket dp:
             dps) {
            fileBuf.put(dp.getData());
        }


        try (FileOutputStream fileOuputStream = new FileOutputStream(file)){
            fileOuputStream.write(fileBuf.array());
        }

        if (file.getName().contains(".zip")){
            UnzipUtils unzipUtils = new UnzipUtils(file.getAbsolutePath(), dir);
            unzipUtils.unzip();
            file.delete();
        }
    }
}
