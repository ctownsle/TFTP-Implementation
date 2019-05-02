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
        int blockSize = 0; // size of block component of data packet
        int wrqSize = OPCODE + DATA_SIZE + MODE + ZERO_BYTES + BLOCK_SIZE.length() + (2 * Integer.BYTES) + SENDER_WINDOW_SIZE.length(); // max size for a wrq packet, with some buffer room
        byte [] potentialPacket = new byte[wrqSize];
        byte [] dataPacket;
        WRQPacket wrqPacket = null; // WRQPacket handles sending ack for receiving data essentially
        DatagramPacket packet = new DatagramPacket(potentialPacket, potentialPacket.length);
        socket.receive(packet);
        byte [] data = packet.getData();
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.get(); // removes inital byte
        byte opCode = buffer.get();
        if (opCode == 2){
            // write request
            // send ack, and initialize important variables
            // NOTE: I should probably build the ackPacket here; however, i pass it to the WrqPacket object to handle all of that stuff
            // parameters get messy
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
        int dataInPacket = blockSize + SEQ_NUM + OPCODE; // allocating for data packet, this value changes
        int sum = 0; // used to calculate total bytes received
        dataPacket = new byte[dataInPacket];
        ArrayList<DataPacket> dps = new ArrayList<>();
        short currentSeq = 1;
        int expectedData = blockSize + OPCODE + SEQ_NUM; // this value does not change
        boolean finalPacket = false;

        while (dataInPacket ==  expectedData){
            packet = new DatagramPacket(dataPacket, dataPacket.length);
            int wrqCount = 0; // for receiving a rediculous amount of wrqpackets in a row
            socket.receive(packet);
            data = packet.getData();
            dataInPacket = packet.getLength(); // total data without buffer
            if (dataInPacket < expectedData) finalPacket = true; // final packet is used for sending an ack
            int currentBlockSize = dataInPacket - (OPCODE + SEQ_NUM);
            sum += currentBlockSize;
            buffer = ByteBuffer.wrap(data);
            byte finalWindowByte = buffer.get(); // either 1 or 0, if 1 send ack, if 0 don't, unless it's not a sliding window
            opCode = buffer.get();
            if(opCode == 3){
                 //good, we expected and got a data packet
                short seqNum = buffer.getShort();
                if(seqNum == currentSeq) {
                    // we got what we expected, increment our number after "sending an ack", because that's where i decided to handle it
                    // the flag at the end of the packet indicates whether or not something is seemingly dropped, always send that ack
                    // if false, increment seqnum
                    // if true, something was dropped, don't do that
                    DataPacket dp = new DataPacket(seqNum);
                    dp.processAndSendAck(socket, buffer, packet.getPort(), packet.getAddress(), finalWindowByte, currentBlockSize, finalPacket, slidingWindow, false);
                    dps.add(dp);
                    currentSeq++;

                } else {
                    // we didn't get what we expected, if its a final window Packet, send an ack with what we expected
                    DataPacket dp = new DataPacket(currentSeq);
                    dp.processAndSendAck(socket, buffer, packet.getPort(), packet.getAddress(), finalWindowByte, currentBlockSize, finalPacket, slidingWindow, true);
                }

            } else if(opCode == 2){
                wrqCount++;
                if(wrqCount >= 2){
                    // send ack
                    // attempt to respond to repeated wrqs
                    wrqPacket = new WRQPacket();
                    wrqPacket.sendACK(socket, buffer, packet.getPort(), packet.getAddress(), packet.getLength());
                    blockSize = wrqPacket.getBlockSize();
                    slidingWindow = wrqPacket.getWindowSize();
                }
            } else {
                // something went very wrong with processing data
                System.out.println("ERROR : System closing");
                System.exit(1);
            }
        }

        // clean up resources
        socket.close();
        ByteBuffer fileBuf = ByteBuffer.allocate(sum); // build a buffer for the file
        String dir = System.getProperty("user.dir");
        file = new File(dir, wrqPacket.getFileName());
        for (DataPacket dp:
             dps) {
            fileBuf.put(dp.getData()); // put all data blocks in buffer
        }


        try (FileOutputStream fileOuputStream = new FileOutputStream(file)){
            // write file
            fileOuputStream.write(fileBuf.array());
        }

        if (file.getName().contains(".zip")){
            // if it's a zip file, unzip into directory, then delete
            UnzipUtils unzipUtils = new UnzipUtils(file.getAbsolutePath(), dir);
            unzipUtils.unzip();
            file.delete();
        }
    }
}
