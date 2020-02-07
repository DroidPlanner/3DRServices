/* AUTO-GENERATED FILE.  DO NOT MODIFY.
 *
 * This class was automatically generated by the
 * java mavlink generator tool. It should not be modified by hand.
 */

// MESSAGE AOA_SSA PACKING
package com.MAVLink.ardupilotmega;
import com.MAVLink.MAVLinkPacket;
import com.MAVLink.Messages.MAVLinkMessage;
import com.MAVLink.Messages.MAVLinkPayload;
        
/**
 * Angle of Attack and Side Slip Angle.
 */
public class msg_aoa_ssa extends MAVLinkMessage {

    public static final int MAVLINK_MSG_ID_AOA_SSA = 11020;
    public static final int MAVLINK_MSG_LENGTH = 16;
    private static final long serialVersionUID = MAVLINK_MSG_ID_AOA_SSA;


      
    /**
     * Timestamp (since boot or Unix epoch).
     */
    public long time_usec;
      
    /**
     * Angle of Attack.
     */
    public float AOA;
      
    /**
     * Side Slip Angle.
     */
    public float SSA;
    

    /**
     * Generates the payload for a mavlink message for a message of this type
     * @return
     */
    public MAVLinkPacket pack() {
        MAVLinkPacket packet = new MAVLinkPacket(MAVLINK_MSG_LENGTH,isMavlink2);
        packet.sysid = 255;
        packet.compid = 190;
        packet.msgid = MAVLINK_MSG_ID_AOA_SSA;
        
        packet.payload.putUnsignedLong(time_usec);
        
        packet.payload.putFloat(AOA);
        
        packet.payload.putFloat(SSA);
        
        if(isMavlink2) {
            
        }
        return packet;
    }

    /**
     * Decode a aoa_ssa message into this class fields
     *
     * @param payload The message to decode
     */
    public void unpack(MAVLinkPayload payload) {
        payload.resetIndex();
        
        this.time_usec = payload.getUnsignedLong();
        
        this.AOA = payload.getFloat();
        
        this.SSA = payload.getFloat();
        
        if(isMavlink2) {
            
        }
    }

    /**
     * Constructor for a new message, just initializes the msgid
     */
    public msg_aoa_ssa() {
        msgid = MAVLINK_MSG_ID_AOA_SSA;
    }

    /**
     * Constructor for a new message, initializes the message with the payload
     * from a mavlink packet
     *
     */
    public msg_aoa_ssa(MAVLinkPacket mavLinkPacket) {
        this.sysid = mavLinkPacket.sysid;
        this.compid = mavLinkPacket.compid;
        this.msgid = MAVLINK_MSG_ID_AOA_SSA;
        this.isMavlink2 = mavLinkPacket.isMavlink2;
        unpack(mavLinkPacket.payload);        
    }

          
    /**
     * Returns a string with the MSG name and data
     */
    public String toString() {
        return "MAVLINK_MSG_ID_AOA_SSA - sysid:"+sysid+" compid:"+compid+" time_usec:"+time_usec+" AOA:"+AOA+" SSA:"+SSA+"";
    }
}
        