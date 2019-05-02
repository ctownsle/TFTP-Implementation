package Constants;

public class Constants {


    public static final int PORT = 2709;
    public static final int DATA_SIZE = 512;
    public static final int DATA_PACKET_SIZE = 516;// filename max field = 512
    public static final int OPCODE = 2; // two bytes is short
    public static final int SEQ_NUM = 2;
    public static final int ZERO_BYTES = 6;
    public static final int MODE = 5;
    public static final int ACK_SIZE = 4;
    public static final short ACK_OPCODE = 4;
    public static final String BLOCK_SIZE = "blksize";
    public static final String SENDER_WINDOW_SIZE = "sws";
    // each char for mode is 1 byte (mode is 5 bytes), because it's always "octet"

    private Constants (){
    }

}
