/* AUTO-GENERATED FILE.  DO NOT MODIFY.
 *
 * This class was automatically generated by the
 * java mavlink generator tool. It should not be modified by hand.
 */

package com.MAVLink.uAvionix;

import java.util.HashMap;
import java.util.Map;

/**
 * X.25 CRC calculation for MAVlink messages. The checksum must be initialized,
 * updated with witch field of the message, and then finished with the message
 * id.
 */
public class CRC {
    private static final Map<Integer, Integer> MAVLINK_MESSAGE_CRCS;
    private static final int CRC_INIT_VALUE = 0xffff;
    private int crcValue;

    static {
        MAVLINK_MESSAGE_CRCS = new HashMap<>();
        MAVLINK_MESSAGE_CRCS.put(10001, 209);
        MAVLINK_MESSAGE_CRCS.put(10002, 186);
        MAVLINK_MESSAGE_CRCS.put(10003, 4);
        
    }

    /**
     * Accumulate the X.25 CRC by adding one char at a time.
     *
     * The checksum function adds the hash of one char at a time to the 16 bit
     * checksum (uint16_t).
     *
     * @param data new char to hash
     **/
    public void update_checksum(int data) {
        data = data & 0xff; //cast because we want an unsigned type
        int tmp = data ^ (crcValue & 0xff);
        tmp ^= (tmp << 4) & 0xff;
        crcValue = ((crcValue >> 8) & 0xff) ^ (tmp << 8) ^ (tmp << 3) ^ ((tmp >> 4) & 0xf);
    }

    /**
     * Finish the CRC calculation of a message, by running the CRC with the
     * Magic Byte.
     *
     * @param msgid The message id number
     */
    public void finish_checksum(int msgid) {
        if(MAVLINK_MESSAGE_CRCS.containsKey(msgid))
            update_checksum(MAVLINK_MESSAGE_CRCS.get(msgid));
    }

    /**
     * Initialize the buffer for the X.25 CRC
     */
    public void start_checksum() {
        crcValue = CRC_INIT_VALUE;
    }

    public int getMSB() {
        return ((crcValue >> 8) & 0xff);
    }

    public int getLSB() {
        return (crcValue & 0xff);
    }

    public CRC() {
        start_checksum();
    }

}
        