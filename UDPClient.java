import ZipProcess.ZipUtils;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;

import static Constants.Constants.*;

public class UDPClient {

    protected static DatagramSocket socket = null;
    private static final String MODE = "octet";
    private static InetAddress address;
    private static String fileName;
    private static boolean window = false;
    private static DatagramPacket ackPacket;
    private static File fileToSend;
    private static int blockSize = 0;
    private static boolean directory = false;
    private static long currentEstimateRTT = 64;
    private static boolean drop = false;


    // ----------------------------------
    // | Opcode |   Block #  |   Data     | Data Packet
    // ----------------------------------


    public static void main(String[] args) throws IOException {
        socket = new DatagramSocket();
        fileName = args[1];
        if (!fileName.contains(".")) {
            createZipFile(System.getProperty("user.dir") + File.separator + fileName, fileName + ".zip");
            fileName = fileName + ".zip";
            directory = true;
        }

        if(args.length > 2){
            String dropString = args[2];
            if (dropString.equals("drop")) drop = true;
            else drop = false;
        }

        if(args.length > 3){
            window = true;
        }
        address = Inet6Address.getByName(args[0]);
        System.out.println("Address: " + address.getHostName());
        // Create ack packet
        byte[] ack = new byte[ACK_SIZE];
        ackPacket = new DatagramPacket(ack, ack.length);
        // establish connection with server

        processFile();
        File f = new File(fileName);
        byte[] fileBytes = Files.readAllBytes(f.toPath());
        int numPackets = (fileBytes.length - 1) / blockSize + 1;
        sendWRQ(numPackets);
        // received wrq
        if(!window) {
            sendFile(numPackets, fileBytes);
        } else sendFileWindowed(numPackets, fileBytes);

    }

    private static void sendWRQ(final int numPackets) throws IOException {

        //+-------+---~~---+---+---~~---+---+---~~---+---+---~~---+---+-->
//        |  opc  |filename| 0 |  mode  | 0 |  blksize  | 0 | value1 | 0 | <
//      +-------+---~~---+---+---~~---+---+---~~---+---+---~~---+---+-->
        //>-------+---+---~~---+---+
        //  <  sws  | 0 | value2 | 0 |
        byte[] fileNameBytes = fileName.getBytes();
        int windowSize = calculateWindowSize(numPackets);
        if (! window){
            windowSize = 0;
        }
        System.out.println(fileNameBytes);
        if (fileNameBytes.length > (DATA_PACKET_SIZE - (Long.BYTES - 4 - BLOCK_SIZE.length()))) {

            // allocating 4 null bytes for stop & wait
            System.out.println("File name too large!");
            System.exit(1);
        }
        ByteBuffer b = ByteBuffer.wrap(fileNameBytes);// extra two bytes for filename size
        //b.putInt(fileNameBytes.length);
        b.put(fileNameBytes);
        fileNameBytes = b.array();
        byte[] modeBytes = MODE.getBytes();
        byte[] blkStringBytes = BLOCK_SIZE.getBytes();
        byte[] swsStringBytes = SENDER_WINDOW_SIZE.getBytes();
        byte zero = 0;
        short opcode = 2;
        int totalWRQBytes = fileNameBytes.length + modeBytes.length + OPCODE + ZERO_BYTES + BLOCK_SIZE.length() + (2 * Integer.BYTES) + SENDER_WINDOW_SIZE.length(); // the four is for the zero bytes

        // populate buffer
        ByteBuffer buffer = ByteBuffer.allocate(totalWRQBytes);
        buffer.putShort(opcode);
        buffer.put(fileNameBytes);
        buffer.put(zero);
        buffer.put(modeBytes);
        buffer.put(zero);
        buffer.put(blkStringBytes);
        buffer.put(zero);
        buffer.putInt(blockSize);
        buffer.put(zero);
        buffer.put(swsStringBytes);
        buffer.put(zero);

        buffer.putInt(windowSize);
        buffer.put(zero);

        byte[] fullPacket = buffer.array();
        long start, end;
        // send packet
        DatagramPacket requestPacket = new DatagramPacket(fullPacket, fullPacket.length, address, PORT);
        try {
            socket.setSoTimeout((int) (2 * currentEstimateRTT));
            start = System.currentTimeMillis();
            socket.send(requestPacket);
            socket.receive(ackPacket);
            end = System.currentTimeMillis();
            long sample = end - start;
            currentEstimateRTT = estimateRTT(sample, currentEstimateRTT);
        } catch (SocketTimeoutException e){
            currentEstimateRTT *= 2;
            socket.setSoTimeout((int) (2 * currentEstimateRTT));
            start = System.currentTimeMillis();
            socket.send(requestPacket);
            socket.receive(ackPacket);
            end = System.currentTimeMillis();
            long sample = end - start;
            currentEstimateRTT = estimateRTT(sample, currentEstimateRTT);
        }

    }

    private static void sendFile(final int numPackets, final byte[] fileBytes) throws IOException {
        System.out.println("Sending file");
        DatagramPacket dataPacket;

        System.out.println("NUMPACKETS: " + numPackets);
        byte[] dataBytes = new byte[blockSize];
        ByteBuffer buffer = ByteBuffer.wrap(fileBytes);
        ByteBuffer packetBuffer;
        short seqNum = 1;
        short opCode = 3;
        int remaining;
        long start, end;

        for (int i = 0; i < numPackets; i++) {
            remaining = buffer.remaining();
            System.out.println("Remaining bytes in buffer: " + remaining);
            if (i == (numPackets - 1)) {
                byte[] remain = new byte[remaining];
                buffer.get(remain);
                packetBuffer = ByteBuffer.allocate(remaining + OPCODE + SEQ_NUM);
                packetBuffer.putShort(opCode);
                packetBuffer.putShort(seqNum);
                packetBuffer.put(remain);
                System.out.println("FINAL PACKET: " + remaining);
            } else {
                packetBuffer = ByteBuffer.allocate(blockSize + OPCODE + SEQ_NUM);
                buffer.get(dataBytes);
                packetBuffer.putShort(opCode);
                packetBuffer.putShort(seqNum);
                packetBuffer.put(dataBytes);
            }
            byte[] packetBytes = packetBuffer.array();
            System.out.println(packetBytes.length);

            packetBuffer.clear();

            int currentPacket = i + 1;
            System.out.println("Sending packet#: " + currentPacket);
            dataPacket = new DatagramPacket(packetBytes, packetBytes.length, address, PORT);

            try {
                socket.setSoTimeout((int) (2 * currentEstimateRTT));
                start = System.currentTimeMillis();
                if (!drop || (currentPacket % 101) != 100) {
                    socket.send(dataPacket);
                }
                socket.receive(ackPacket);
                end = System.currentTimeMillis();
                long sample = end - start;
                currentEstimateRTT = estimateRTT(sample, currentEstimateRTT);

            } catch (SocketTimeoutException e){
                currentEstimateRTT *= 2;
                socket.setSoTimeout((int) (2 * currentEstimateRTT));
                System.out.println("CURRENT TIMEOUT: " + socket.getSoTimeout());
                start = System.currentTimeMillis();
                socket.send(dataPacket);
                socket.receive(ackPacket);
                end = System.currentTimeMillis();
                long sample = end - start;
                currentEstimateRTT = estimateRTT(sample, currentEstimateRTT);
            }

            seqNum++;

            ByteBuffer seqBuffer = ByteBuffer.wrap(ackPacket.getData());
            seqBuffer.getShort();
            short currentSeq = seqBuffer.getShort();
            System.out.println(currentSeq);
            System.out.println(seqNum);

            // ack packets have two shorts
            while (seqNum != currentSeq) {
                // bad, resend
                try {
                    socket.setSoTimeout((int) (2 * currentEstimateRTT));
                    start = System.currentTimeMillis();
                    socket.send(dataPacket);
                    socket.receive(ackPacket);
                    end = System.currentTimeMillis();
                    long sample = end - start;
                    currentEstimateRTT = estimateRTT(sample, currentEstimateRTT);
                } catch (SocketTimeoutException e){
                    currentEstimateRTT *= 2;
                    socket.setSoTimeout((int) (2 * currentEstimateRTT));
                    start = System.currentTimeMillis();
                    socket.send(dataPacket);
                    socket.receive(ackPacket);
                    end = System.currentTimeMillis();
                    long sample = end - start;
                    currentEstimateRTT = estimateRTT(sample, currentEstimateRTT);
                }
                seqBuffer.clear();
                seqBuffer.put(ackPacket.getData());
                System.out.println(seqBuffer.position());
                seqBuffer.getShort();
                currentSeq = seqBuffer.getShort();
            }


            seqBuffer.clear();

        }

        if (directory) {
            fileToSend.delete();
        }

        socket.close();

    }

    private static void processFile() throws IOException {
        // set values for processing file before sending wrq
        // ideally this could maybe be done in parallel but idk how
        fileToSend = new File(fileName);
        byte[] fileBytes = Files.readAllBytes(fileToSend.toPath());
        blockSize = calculateBlockSize(fileBytes);
    }

    private static int calculateBlockSize(final byte[] fileBytes) {
        long length = fileBytes.length;
        int bSize;
        if (length > 100000) bSize = 1024;
        else bSize = 512;
        return bSize;
    }

    private static void createZipFile(final String dir, final String output) {
        ZipUtils appZip = new ZipUtils(dir);
        appZip.generateFileList(new File(dir));
        appZip.zipIt(output);
    }

    private static long estimateRTT(final long sample, final long estimate) {
        double beta = 1/8d;
        double alpha = 1 - beta;
        System.out.println("BETA: " + beta);
        // alpha * estimate + beta * sample
        double alphaestimate =  alpha * estimate;
        double samplebeta =  beta * sample;
        System.out.println("AlphaEstimate: " + alphaestimate);
        long res = (long)(alphaestimate + samplebeta);
        if (res <= 2) res = 2;
        return res;
    }

    private static void sendFileWindowed(final int numPackets, final byte[] fileBytes) throws IOException {
        int windowSize = calculateWindowSize(numPackets);
        System.out.println("WINDOW SIZE: " + windowSize);
        DatagramPacket dataPacket;
        System.out.println("NUMPACKETS: " + numPackets);
        byte[] dataBytes = new byte[blockSize];
        ByteBuffer buffer = ByteBuffer.wrap(fileBytes);
        ByteBuffer packetBuffer;
        short seqNum = 1, startingSeqNum = 1;
        byte opCode = 3;
        byte finalWindowByte;
        int remaining, remainingPackets;
        int count = 1;
        long start, end;
        remainingPackets = numPackets;

        while (remainingPackets != 0) {
            System.out.println("currentEstimateRTT: " + currentEstimateRTT);
            start = System.currentTimeMillis();
            for (int i = 0; i < windowSize; i++) {
                if (i == windowSize - 1){
                    finalWindowByte = 1;
                } else {
                    finalWindowByte = 0;
                }
                remaining = buffer.remaining();
                if (seqNum == numPackets) {
                    byte[] remain = new byte[remaining];
                    buffer.get(remain);
                    packetBuffer = ByteBuffer.allocate(remaining + OPCODE + SEQ_NUM);
                    packetBuffer.put(finalWindowByte);
                    packetBuffer.put(opCode);
                    packetBuffer.putShort(seqNum);
                    packetBuffer.put(remain);
                    System.out.println("FINAL PACKET: " + remaining);
                } else {
                    packetBuffer = ByteBuffer.allocate(blockSize + OPCODE + SEQ_NUM);
                    buffer.get(dataBytes);
                    packetBuffer.put(finalWindowByte);
                    packetBuffer.put(opCode);
                    packetBuffer.putShort(seqNum);
                    packetBuffer.put(dataBytes);
                }
                byte[] packetBytes = packetBuffer.array();
               // System.out.println(packetBytes.length);

                packetBuffer.clear();

                dataPacket = new DatagramPacket(packetBytes, packetBytes.length, address, PORT);
                if (!drop || (count % 101) != 100) {
                    socket.send(dataPacket);
                }
               // System.out.println("Sent 1 packet");
                count++;
                seqNum++;

            }
            try {
                socket.setSoTimeout((int) (2 * currentEstimateRTT));
                socket.receive(ackPacket);
                end = System.currentTimeMillis();
                long sample = end - start;
                currentEstimateRTT = estimateRTT(sample, currentEstimateRTT);
                socket.setSoTimeout((int) (2 * currentEstimateRTT));
                windowSize++;
            } catch (SocketTimeoutException e){
                System.out.println("HIT TIMEOUT");
                windowSize /= 2;
                currentEstimateRTT *= 4;
                socket.setSoTimeout((int) (2 * currentEstimateRTT));
                seqNum = startingSeqNum;
                buffer.position(blockSize * (seqNum - 1));
                for (int i = 0; i < windowSize; i++) {
                    if (i == windowSize - 1){
                        finalWindowByte = 1;
                    } else {
                        finalWindowByte = 0;
                    }
                    remaining = buffer.remaining();
                    if (seqNum == numPackets) {
                        byte[] remain = new byte[remaining];
                        buffer.get(remain);
                        packetBuffer = ByteBuffer.allocate(remaining + OPCODE + SEQ_NUM);
                        packetBuffer.put(finalWindowByte);
                        packetBuffer.put(opCode);
                        packetBuffer.putShort(seqNum);
                        packetBuffer.put(remain);
                        System.out.println("FINAL PACKET: " + remaining);
                    } else {
                        packetBuffer = ByteBuffer.allocate(blockSize + OPCODE + SEQ_NUM);
                        buffer.get(dataBytes);
                        packetBuffer.put(finalWindowByte);
                        packetBuffer.put(opCode);
                        packetBuffer.putShort(seqNum);
                        packetBuffer.put(dataBytes);
                    }
                    byte[] packetBytes = packetBuffer.array();
                   // System.out.println(packetBytes.length);

                    packetBuffer.clear();

                    dataPacket = new DatagramPacket(packetBytes, packetBytes.length, address, PORT);

                    socket.send(dataPacket);
                    seqNum++;
                }
                socket.receive(ackPacket);
                end = System.currentTimeMillis();
                long sample = end - start;
                currentEstimateRTT = estimateRTT(sample, currentEstimateRTT);
                socket.setSoTimeout((int) (2 * currentEstimateRTT));
            }

            ByteBuffer seqBuffer = ByteBuffer.wrap(ackPacket.getData());
            seqBuffer.getShort();
            short currentSeq = seqBuffer.getShort();
            //System.out.println("Current seq: " + seqNum);
            //System.out.println("Received val: " + currentSeq);
            if(seqNum != currentSeq){
              //  System.out.println("MIMSATCH");
                seqNum = currentSeq;
               // System.out.println("SEQNUM MOVED HERE: " + currentSeq);
                buffer.position(blockSize * (seqNum - 1));
               // System.out.println("Buffer Position" + buffer.position());
                socket.setSoTimeout((int) (2 * currentEstimateRTT));
            }
            startingSeqNum = seqNum;

            remainingPackets = numPackets - (seqNum - 1);
           // System.out.println("Remaining Packets: " + remainingPackets);
            if(remainingPackets < windowSize){
                windowSize = remainingPackets;
            }


        }

        if (directory) {
            fileToSend.delete();
        }
        socket.close();
    }

    private static int calculateWindowSize (final int numPackets) {
        int maxSize = 32;

        int size = numPackets / 4;

        if(size > maxSize) return maxSize;
        else return 32 - Integer.numberOfLeadingZeros(size - 1) + 1;
    }

}