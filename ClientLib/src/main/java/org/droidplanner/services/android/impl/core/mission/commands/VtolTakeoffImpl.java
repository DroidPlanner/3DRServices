package org.droidplanner.services.android.impl.core.mission.commands;

import com.MAVLink.common.msg_mission_item;
import com.MAVLink.enums.MAV_CMD;
import com.MAVLink.enums.MAV_FRAME;

import org.droidplanner.services.android.impl.core.mission.MissionImpl;
import org.droidplanner.services.android.impl.core.mission.MissionItemImpl;
import org.droidplanner.services.android.impl.core.mission.MissionItemType;

import java.util.List;

public class VtolTakeoffImpl extends MissionCMD {

    public static final double DEFAULT_TAKEOFF_ALTITUDE = 10.0;

    private double finishedAlt = 10;

    public VtolTakeoffImpl(MissionItemImpl item) {
        super(item);
    }

    public VtolTakeoffImpl(msg_mission_item msg, MissionImpl missionImpl) {
        super(missionImpl);
        unpackMAVMessage(msg);
    }

    public VtolTakeoffImpl(MissionImpl missionImpl, double altitude) {
        super(missionImpl);
        this.finishedAlt = altitude;
    }

    public VtolTakeoffImpl(MissionImpl missionImpl, double altitude, double pitch) {
        super(missionImpl);
        this.finishedAlt = altitude;
    }

    @Override
    public List<msg_mission_item> packMissionItem() {
        List<msg_mission_item> list = super.packMissionItem();
        msg_mission_item mavMsg = list.get(0);
        mavMsg.command = MAV_CMD.MAV_CMD_NAV_VTOL_TAKEOFF;
        mavMsg.frame = MAV_FRAME.MAV_FRAME_GLOBAL_RELATIVE_ALT;
        mavMsg.z = (float) finishedAlt;
        return list;
    }

    @Override
    public void unpackMAVMessage(msg_mission_item mavMsg) {
        finishedAlt = mavMsg.z;
    }

    @Override
    public MissionItemType getType() {
        return MissionItemType.VTOL_TAKEOFF;
    }

    public double getFinishedAlt() {
        return finishedAlt;
    }

    public void setFinishedAlt(double finishedAlt) {
        this.finishedAlt = finishedAlt;
    }
}
