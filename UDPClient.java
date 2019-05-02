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
            // Checks to see if there's a directory, different sending procedures
            createZipFile(System.getProperty("user.dir") + File.separator + fileName, fileName + ".zip");
            fileName = fileName + ".zip";
            directory = true;
        }

        if(args.length > 2){
            // checks if we're purposefully dropping packets
            String dropString = args[2];
            if (dropString.equals("drop")) drop = true;
            else drop = false;
        }

        if(args.length > 3){
            // if there's a 4th argument, we want sliding windows
            window = true;
        }
        address = Inet6Address.getByName(args[0]);
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

        // this is what a wrq packet looks like

        byte[] fileNameBytes = fileName.getBytes();
        int windowSize = calculateWindowSize(numPackets); // get initial window size
        if (! window){
            // if we're not using sliding window, we don't have a window
            windowSize = 0;
        }

        if (fileNameBytes.length > (DATA_PACKET_SIZE - (ZERO_BYTES - BLOCK_SIZE.length() - (2 * Integer.BYTES)
                - SENDER_WINDOW_SIZE.length() - MODE.length() - OPCODE))) {

            // allocating 6 null bytes, 2 ints for block size and windowsize, arbitrary max size for packet
            System.out.println("File name too large!");
            System.exit(1);
        }
        ByteBuffer b = ByteBuffer.wrap(fileNameBytes);
        fileNameBytes = b.array(); // get fileNameBytes
        // Build wrqPacket
        byte[] modeBytes = MODE.getBytes();
        byte[] blkStringBytes = BLOCK_SIZE.getBytes();
        byte[] swsStringBytes = SENDER_WINDOW_SIZE.getBytes();
        byte zero = 0; // create a var for null bytes because its probably better than casting everything
        short opcode = 2;
        int totalWRQBytes = fileNameBytes.length + modeBytes.length + OPCODE + ZERO_BYTES + BLOCK_SIZE.length() + (2 * Integer.BYTES) + SENDER_WINDOW_SIZE.length(); // the four is for the zero bytes
        // total number of bytes for the packet
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

        buffer.putInt(windowSize); // note it only matters that windowSize sent is > 0
        buffer.put(zero);

        byte[] fullPacket = buffer.array();
        long start, end;
        // send packet
        DatagramPacket requestPacket = new DatagramPacket(fullPacket, fullPacket.length, address, PORT);
        try {
            // try to send wrq packet
            // handle timeouts
            socket.setSoTimeout((int) (2 * currentEstimateRTT));
            start = System.currentTimeMillis();
            socket.send(requestPacket);
            socket.receive(ackPacket);
            end = System.currentTimeMillis();
            long sample = end - start;
            currentEstimateRTT = estimateRTT(sample, currentEstimateRTT);
        } catch (SocketTimeoutException e){
            // hit timeout
            // extend timeout
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

    /*
        Sends a file with sequential acks (Stop & Wait)
        @arg numPackets, total number of packets to send
        @arg fileBytes, all the bytes in a given file
     */
    private static void sendFile(int numPackets, final byte[] fileBytes) throws IOException {
        DatagramPacket dataPacket;

        byte[] dataBytes = new byte[blockSize];
        ByteBuffer buffer = ByteBuffer.wrap(fileBytes); // loads all file bytes into a byte buffer
        ByteBuffer packetBuffer;
        short seqNum = 1; // seqnum is essentially indexed at 1
        short opCode = 3; // opcode for data packet is 03
        int remaining; // bytes remaining in the buffer
        long start, end, startf, endf; // start, end are for recalculating socket timeouts
        // startf, endf are for calculating throughput

        startf = System.nanoTime();

        /*
        iterate through packets, and populate a byte buffer for the packet bytes, then try to send them
         */
        for (int i = 0; i < numPackets; i++) {
            remaining = buffer.remaining();
            if (i == (numPackets - 1)) {
                // final packet
                byte[] remain = new byte[remaining];
                buffer.get(remain);
                packetBuffer = ByteBuffer.allocate(remaining + OPCODE + SEQ_NUM);
                packetBuffer.putShort(opCode);
                packetBuffer.putShort(seqNum);
                packetBuffer.put(remain);
            } else {
                packetBuffer = ByteBuffer.allocate(blockSize + OPCODE + SEQ_NUM);
                buffer.get(dataBytes);
                packetBuffer.putShort(opCode);
                packetBuffer.putShort(seqNum);
                packetBuffer.put(dataBytes);
            }
            byte[] packetBytes = packetBuffer.array();
            // after we're done with the current packet, make sure there's no garbage left
            packetBuffer.clear();

            int currentPacket = i + 1; // value used for purposefully dropping packets
            dataPacket = new DatagramPacket(packetBytes, packetBytes.length, address, PORT);

            try {
                // try to send packets
                // everytime we send a packet successfully, recalculate RTTs
                // if we're dropping 1% of our packets, drop every 100th packet sent
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
                // hit timeout
                // extend timeout by a factor of 2
                // try to send again with same process
                currentEstimateRTT *= 2;
                socket.setSoTimeout((int) (2 * currentEstimateRTT));
                start = System.currentTimeMillis();
                socket.send(dataPacket);
                socket.receive(ackPacket);
                end = System.currentTimeMillis();
                long sample = end - start;
                currentEstimateRTT = estimateRTT(sample, currentEstimateRTT);
            }

            seqNum++; // packet has been sent at this point, increment our seq number

            ByteBuffer seqBuffer = ByteBuffer.wrap(ackPacket.getData()); // populate a bytebuffer for acks
            seqBuffer.getShort();
            short currentSeq = seqBuffer.getShort();

            // ack packets have two shorts
            if (seqNum != currentSeq) {
                // bad change sequence number and iterate again
                seqNum = currentSeq;

                buffer.position(blockSize * (seqNum - 1)); // shift buffer position back to the packet data we want to send
                socket.setSoTimeout((int) (2 * currentEstimateRTT));
                numPackets++; // have to do bad practice for this implementation, we have to send a different packet
                // this also makes an assumption that things can't be too disorderly
                // with stop and wait it shouldn't be a problem
            }



        }

        endf = System.nanoTime();
        long finalTime = endf - startf;
        System.out.println("Throughput: " + fileBytes.length + "/" + finalTime); // print throughput

        if (directory) {
            // if a file is a directory delete
            fileToSend.delete();
        }
        // clean up resources
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
        // determines block size for the amount of data, if theres enough bytes, make block size larger
        long length = fileBytes.length;
        int bSize;
        if (length > 100000) bSize = 1024;
        else bSize = 512;
        return bSize;
    }

    private static void createZipFile(final String dir, final String output) {
        // Creates zip file for a given directory
        ZipUtils appZip = new ZipUtils(dir);
        appZip.generateFileList(new File(dir));
        appZip.zipIt(output);
    }

    private static long estimateRTT(final long sample, final long estimate) {
        // Used a sample algorithm to determine a reasonable socket timeout time, limiting the
        // shortest amount of time to 2 ms
        double beta = 1/8d;
        double alpha = 1 - beta;
        double alphaestimate =  alpha * estimate;
        double samplebeta =  beta * sample;
        long res = (long)(alphaestimate + samplebeta);
        if (res <= 2) res = 2; // upper bound for rtts is 2
        return res;
    }

    /*
        Sends a file with window size waits for one ack, then sends window size again
        @arg numPackets, total number of packets to be sent
        @arg fileBytes, total bytes in a given file
     */
    private static void sendFileWindowed(final int numPackets, final byte[] fileBytes) throws IOException {
        int windowSize = calculateWindowSize(numPackets); // calculates initial window size with a max of 32

        DatagramPacket dataPacket;
        byte[] dataBytes = new byte[blockSize];
        ByteBuffer buffer = ByteBuffer.wrap(fileBytes); // put file bytes in bytebuffer
        ByteBuffer packetBuffer;
        short seqNum = 1, startingSeqNum = 1; // starting seqnum is the initial window position
        // seqnum is current seqnum to be sent
        byte opCode = 3; // opcode for datapacket
        byte finalWindowByte; // final windowbyte is a hack on the first byte of the datapacket
        // = 1 if its the last packet in the window, so the server knows to send an ack
        // = 0 if its not the last packet in the window, so the server shouldn't send anything
        int remaining, remainingPackets; // remaining is for bytes in file, remainingPackets is for total packets left to send
        int count = 1; // for dropping packets on purpose
        long start, end, startf, endf; // start, end for calculating reasonable timeouts. startf, endf, for calculating throughput
        remainingPackets = numPackets;

        startf = System.nanoTime();
        /*
         Iterate over the number of packets, and through the current window size until there are none left
         send packets = to the current window size, and then receive an ack
         if ack.seqnum < seqNum, move buffer and everything else to ack.seqnum, then start the new window at that point
         */
        while (remainingPackets != 0) {
            start = System.currentTimeMillis();
            for (int i = 0; i < windowSize; i++) {
                if (i == windowSize - 1){
                    finalWindowByte = 1;
                } else {
                    finalWindowByte = 0;
                }
                remaining = buffer.remaining();
                if (seqNum == numPackets) {
                    // final packet
                    byte[] remain = new byte[remaining];
                    buffer.get(remain);
                    packetBuffer = ByteBuffer.allocate(remaining + OPCODE + SEQ_NUM);
                    packetBuffer.put(finalWindowByte);
                    packetBuffer.put(opCode);
                    packetBuffer.putShort(seqNum);
                    packetBuffer.put(remain);
                } else {
                    packetBuffer = ByteBuffer.allocate(blockSize + OPCODE + SEQ_NUM);
                    buffer.get(dataBytes);
                    packetBuffer.put(finalWindowByte);
                    packetBuffer.put(opCode);
                    packetBuffer.putShort(seqNum);
                    packetBuffer.put(dataBytes);
                }
                byte[] packetBytes = packetBuffer.array();

                packetBuffer.clear();

                dataPacket = new DatagramPacket(packetBytes, packetBytes.length, address, PORT);
                if (!drop || (count % 101) != 100) {
                    // if drops are enabled don't send packet
                    socket.send(dataPacket);
                }

                count++;
                seqNum++;

            }
            try {
                // try to receive ack packet, if it fails we timeout
                // if it succeeds, increment our window size
                socket.setSoTimeout((int) (2 * currentEstimateRTT));
                socket.receive(ackPacket);
                end = System.currentTimeMillis();
                long sample = end - start;
                currentEstimateRTT = estimateRTT(sample, currentEstimateRTT);
                socket.setSoTimeout((int) (2 * currentEstimateRTT));
                windowSize++;
            } catch (SocketTimeoutException e){
                // Hit timeout
                // decrease windowsize
                // increase timeout
                // send entire window again
                windowSize /= 2;
                currentEstimateRTT *= 4;
                socket.setSoTimeout((int) (2 * currentEstimateRTT));
                seqNum = startingSeqNum; // revert seqnum to initial window position
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
                    } else {
                        packetBuffer = ByteBuffer.allocate(blockSize + OPCODE + SEQ_NUM);
                        buffer.get(dataBytes);
                        packetBuffer.put(finalWindowByte);
                        packetBuffer.put(opCode);
                        packetBuffer.putShort(seqNum);
                        packetBuffer.put(dataBytes);
                    }
                    byte[] packetBytes = packetBuffer.array();

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
                // don't increment window size here
            }

            ByteBuffer seqBuffer = ByteBuffer.wrap(ackPacket.getData()); // process ack packet
            seqBuffer.getShort();
            short currentSeq = seqBuffer.getShort();
            if(seqNum != currentSeq){
                seqNum = currentSeq;
                buffer.position(blockSize * (seqNum - 1));
                // move buffer to ideal position, revert seqnum to whatever ack we receive
                socket.setSoTimeout((int) (2 * currentEstimateRTT));
            }
            startingSeqNum = seqNum; // changee our initial window position

            remainingPackets = numPackets - (seqNum - 1); // remaining packets is used to keep track of final window size
            if(remainingPackets < windowSize){
                // less packets than our current window, change size
                windowSize = remainingPackets;
            }


        }

        endf = System.nanoTime();

        long finalTime = endf - startf;
        System.out.println("Throughput: " + fileBytes.length + "/" + finalTime);


        if (directory) {
            // if its a directory delete the zip
            fileToSend.delete();
        }
        // clean up resources
        socket.close();
    }

    private static int calculateWindowSize (final int numPackets) {
        // calculates largest power of 2 not greater than 32 for window size by arbitrarily dividing the data by 4
        // this is only for initial window size
        int maxSize = 32;

        int size = numPackets / 4;

        if(size > maxSize) return maxSize;
        else return 32 - Integer.numberOfLeadingZeros(size - 1) + 1;
    }

}