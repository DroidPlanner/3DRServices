package org.droidplanner.services.android.core.drone;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import com.MAVLink.MAVLinkPacket;
import com.MAVLink.Messages.MAVLinkMessage;
import com.MAVLink.ardupilotmega.msg_mag_cal_progress;
import com.MAVLink.ardupilotmega.msg_mag_cal_report;
import com.MAVLink.common.msg_command_ack;
import com.MAVLink.enums.MAV_SEVERITY;
import com.o3dr.android.client.apis.CapabilityApi;
import com.o3dr.services.android.lib.coordinate.LatLong;
import com.o3dr.services.android.lib.coordinate.LatLongAlt;
import com.o3dr.services.android.lib.drone.action.CapabilityActions;
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent;
import com.o3dr.services.android.lib.drone.attribute.AttributeEventExtra;
import com.o3dr.services.android.lib.drone.attribute.AttributeType;
import com.o3dr.services.android.lib.drone.attribute.error.CommandExecutionError;
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent;
import com.o3dr.services.android.lib.drone.attribute.AttributeEventExtra;
import com.o3dr.services.android.lib.drone.companion.solo.SoloControllerMode;
import com.o3dr.services.android.lib.drone.companion.solo.action.SoloLinkActions;
import com.o3dr.services.android.lib.drone.companion.solo.tlv.SoloButtonSettingSetter;
import com.o3dr.services.android.lib.drone.companion.solo.tlv.TLVMessageTypes;
import com.o3dr.services.android.lib.drone.connection.ConnectionParameter;
import com.o3dr.services.android.lib.drone.connection.ConnectionType;
import com.o3dr.services.android.lib.drone.connection.DroneSharePrefs;
import com.o3dr.services.android.lib.drone.mission.action.MissionActions;
import com.o3dr.services.android.lib.drone.mission.item.complex.CameraDetail;
import com.o3dr.services.android.lib.drone.property.DroneAttribute;
import com.o3dr.services.android.lib.gcs.action.FollowMeActions;
import com.o3dr.services.android.lib.gcs.follow.FollowType;
import com.o3dr.services.android.lib.model.ICommandListener;
import com.o3dr.services.android.lib.model.action.Action;

import org.droidplanner.services.android.core.MAVLink.MAVLinkStreams;
import org.droidplanner.services.android.core.MAVLink.MavLinkMsgHandler;
import org.droidplanner.services.android.core.drone.variables.calibration.MagnetometerCalibrationImpl;
import org.droidplanner.services.android.core.firmware.FirmwareType;
import org.droidplanner.services.android.core.gcs.GCSHeartbeat;
import org.droidplanner.services.android.core.gcs.follow.Follow;
import org.droidplanner.services.android.core.gcs.follow.FollowAlgorithm;
import org.droidplanner.services.android.core.helpers.coordinates.Coord2D;
import org.droidplanner.services.android.core.helpers.coordinates.Coord3D;
import org.droidplanner.services.android.core.parameters.Parameter;
import org.droidplanner.services.android.utils.CommonApiUtils;
import org.droidplanner.services.android.api.MavLinkServiceApi;
import org.droidplanner.services.android.communication.connection.DroneshareClient;
import org.droidplanner.services.android.communication.service.MAVLinkClient;
import org.droidplanner.services.android.communication.service.UploaderService;
import org.droidplanner.services.android.drone.companion.solo.SoloComp;
import com.o3dr.services.android.lib.drone.companion.solo.button.ButtonPacket;
import com.o3dr.services.android.lib.drone.companion.solo.tlv.SoloButtonSetting;
import com.o3dr.services.android.lib.drone.companion.solo.tlv.TLVPacket;
import org.droidplanner.services.android.core.drone.autopilot.ArduCopter;
import org.droidplanner.services.android.core.drone.autopilot.ArduPlane;
import org.droidplanner.services.android.core.drone.autopilot.ArduRover;
import org.droidplanner.services.android.core.drone.autopilot.ArduSolo;
import org.droidplanner.services.android.core.drone.autopilot.Drone;
import org.droidplanner.services.android.core.drone.autopilot.MavLinkDrone;
import org.droidplanner.services.android.exception.ConnectionException;
import org.droidplanner.services.android.core.gcs.location.FusedLocation;
import org.droidplanner.services.android.utils.AndroidApWarningParser;
import org.droidplanner.services.android.utils.analytics.GAUtils;
import org.droidplanner.services.android.utils.prefs.DroidPlannerPrefs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge between the communication channel, the drone instance(s), and the connected client(s).
 */
public class DroneManager implements Drone, MAVLinkStreams.MavlinkInputStream, DroneInterfaces.OnDroneListener,
        DroneInterfaces.OnParameterManagerListener, LogMessageListener, MagnetometerCalibrationImpl.OnMagnetometerCalibrationListener {

    private static final String TAG = DroneManager.class.getSimpleName();

    private final ConcurrentHashMap<String, DroneEventsListener> connectedApps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DroneshareClient> tlogUploaders = new ConcurrentHashMap<>();

    private final Context context;
    private final Handler handler;

    private MavLinkDrone drone;
    private Follow followMe;

    private final MAVLinkClient mavClient;
    private final MavLinkMsgHandler mavLinkMsgHandler;
    private final DroneCommandTracker commandTracker;
    private final ConnectionParameter connectionParameter;

    private final SoloComp soloComp;
    private final SoloComp.SoloCompListener soloCompListener = new SoloComp.SoloCompListener() {
        @Override
        public void onConnected() {
            if(isConnected()) {
                drone.notifyDroneEvent(DroneInterfaces.DroneEventsType.CONNECTED);
            }
        }

        @Override
        public void onDisconnected() {
            drone.notifyDroneEvent(DroneInterfaces.DroneEventsType.DISCONNECTED);
        }

        @Override
        public void onTlvPacketReceived(TLVPacket packet) {
            //TODO: filter the message that are broadcast.
            switch(packet.getMessageType()){
                case TLVMessageTypes.TYPE_ARTOO_INPUT_REPORT_MESSAGE:
                    //Drop this message as only the battery info is enabled, and that info is already
                    //available from the autopilot.
                    break;

                case TLVMessageTypes.TYPE_SOLO_GET_BUTTON_SETTING:
                case TLVMessageTypes.TYPE_SOLO_SET_BUTTON_SETTING:
                    //Drop these messages as they are already being handled by the 'onPresetButtonLoaded(...)' method.
                    break;

                default:
                    final Bundle messageInfo = new Bundle();
                    messageInfo.putParcelable(AttributeEventExtra.EXTRA_SOLOLINK_MESSAGE_DATA, packet);

                    notifyDroneAttributeEvent(AttributeEvent.SOLOLINK_MESSAGE_RECEIVED, messageInfo);
                    break;
            }
        }

        @Override
        public void onPresetButtonLoaded(int buttonType, SoloButtonSetting buttonSettings) {
            notifyDroneAttributeEvent(AttributeEvent.SOLOLINK_BUTTON_SETTINGS_UPDATED, null);
        }

        @Override
        public void onWifiInfoUpdated(String wifiName, String wifiPassword) {
            notifyDroneAttributeEvent(AttributeEvent.SOLOLINK_WIFI_SETTINGS_UPDATED, null);
        }

        @Override
        public void onButtonPacketReceived(ButtonPacket packet) {
            final Bundle eventInfo = new Bundle();
            eventInfo.putParcelable(AttributeEventExtra.EXTRA_SOLOLINK_BUTTON_EVENT, packet);
            notifyDroneAttributeEvent(AttributeEvent.SOLOLINK_BUTTON_EVENT, eventInfo);
        }
    };

    private final GCSHeartbeat gcsHeartbeat;

    private final List<CameraDetail> cameraDetails;

    public DroneManager(Context context, ConnectionParameter connParams, final Handler handler,
                        MavLinkServiceApi mavlinkApi, List<CameraDetail> cameraDetails) {
        this.context = context;
        this.handler = handler;
        this.connectionParameter = connParams;
        this.cameraDetails = cameraDetails;

        commandTracker = new DroneCommandTracker(handler);

        mavClient = new MAVLinkClient(context, this, connParams, mavlinkApi);
        mavClient.setCommandTracker(commandTracker);

        this.gcsHeartbeat = new GCSHeartbeat(mavClient, 1);

        this.mavLinkMsgHandler = new MavLinkMsgHandler(this);

        soloComp = new SoloComp(context, handler);
        soloComp.setListener(soloCompListener);
    }

    public void onVehicleTypeReceived(FirmwareType type) {
        if (drone != null) {
            return;
        }

        final DroidPlannerPrefs dpPrefs = new DroidPlannerPrefs(context);

        final DroneInterfaces.Handler dpHandler = new DroneInterfaces.Handler() {
            @Override
            public void removeCallbacks(Runnable thread) {
                handler.removeCallbacks(thread);
            }

            @Override
            public void post(Runnable thread) {
                handler.post(thread);
            }

            @Override
            public void postDelayed(Runnable thread, long timeout) {
                handler.postDelayed(thread, timeout);
            }
        };

        switch (type) {
            case ARDU_COPTER:
                this.drone = new ArduCopter(context, mavClient, dpHandler, dpPrefs, new AndroidApWarningParser(), this);
                break;

            case ARDU_SOLO:
                this.drone = new ArduSolo(context, mavClient, dpHandler, dpPrefs, new AndroidApWarningParser(), this);
                break;

            case ARDU_PLANE:
                this.drone = new ArduPlane(context, mavClient, dpHandler, dpPrefs, new AndroidApWarningParser(), this);
                break;

            case ARDU_ROVER:
                this.drone = new ArduRover(context, mavClient, dpHandler, dpPrefs, new AndroidApWarningParser(), this);
                break;
        }

        this.drone.getStreamRates().setRates(dpPrefs.getRates());

        this.followMe = new Follow(this.drone, handler, new FusedLocation(context, handler));

        drone.addDroneListener(this);
        drone.getParameters().setParameterListener(this);
        drone.getMagnetometerCalibration().setListener(this);
    }

    public SoloComp getSoloComp() {
        return soloComp;
    }
    
    private void destroyAutopilot(){
        if(drone == null)
            return;

        drone.removeDroneListener(this);
        drone.getParameters().setParameterListener(null);
        drone.getMagnetometerCalibration().setListener(null);

        drone = null;
    }

    public void destroy() {
        Log.d(TAG, "Destroying drone manager.");

        destroyAutopilot();
        disconnect();

        soloComp.destroy();

        connectedApps.clear();
        tlogUploaders.clear();

        if (followMe != null && followMe.isEnabled())
            followMe.toggleFollowMeState();
    }

    public void connect(String appId, DroneEventsListener listener) throws ConnectionException {
        if (listener == null || TextUtils.isEmpty(appId))
            return;

        connectedApps.put(appId, listener);

        if(isCompanionComputerEnabled() && !soloComp.isConnected()){
            soloComp.start();
        }
        
        if (!mavClient.isConnected()) {
            mavClient.openConnection();
        } else {
            if (isConnected()) {

                listener.onDroneEvent(DroneInterfaces.DroneEventsType.CONNECTED, drone);
                if (!drone.isConnectionAlive())
                    listener.onDroneEvent(DroneInterfaces.DroneEventsType.HEARTBEAT_TIMEOUT, drone);

                notifyConnected(appId, listener);
            }
        }

        mavClient.addLoggingFile(appId);
    }

    private void disconnect() {
        if(isCompanionComputerEnabled())
            soloComp.stop();

        if (!connectedApps.isEmpty()) {
            for (String appId : connectedApps.keySet()) {
                try {
                    disconnect(appId);
                } catch (ConnectionException e) {
                    Log.e(TAG, e.getMessage(), e);
                }
            }
        }
    }

    /**
     * @return True if we can expect to find a companion computer on the connected channel.
     */
    public boolean isCompanionComputerEnabled(){
        return this.connectionParameter.getConnectionType() == ConnectionType.TYPE_UDP && soloComp.isAvailable();
    }

    public int getConnectedAppsCount() {
        return connectedApps.size();
    }

    public void disconnect(String appId) throws ConnectionException {
        if (TextUtils.isEmpty(appId))
            return;

        Log.d(TAG, "Disconnecting client " + appId);
        DroneEventsListener listener = connectedApps.remove(appId);

        if (listener != null) {
            mavClient.removeLoggingFile(appId);

            listener.onDroneEvent(DroneInterfaces.DroneEventsType.DISCONNECTED, drone);
            notifyDisconnected(appId, listener);
        }

        if (mavClient.isConnected() && connectedApps.isEmpty()) {
            mavClient.closeConnection();
        }
    }

    @Override
    public void notifyStartingConnection() {
        if(drone != null)
            onDroneEvent(DroneInterfaces.DroneEventsType.CONNECTING, drone);
    }

    private void notifyConnected(String appId, DroneEventsListener listener) {
        if (TextUtils.isEmpty(appId) || listener == null)
            return;

        final DroneSharePrefs droneSharePrefs = listener.getDroneSharePrefs();

        //TODO: restore live upload functionality when issue
        // 'https://github.com/diydrones/droneapi-java/issues/2' is fixed.
        boolean isLiveUploadEnabled = false; //droneSharePrefs.isLiveUploadEnabled();
        if (droneSharePrefs != null && isLiveUploadEnabled && droneSharePrefs.areLoginCredentialsSet()) {

            Log.i(TAG, "Starting live upload for " + appId);
            try {
                DroneshareClient uploader = tlogUploaders.get(appId);
                if (uploader == null) {
                    uploader = new DroneshareClient();
                    tlogUploaders.put(appId, uploader);
                }

                uploader.connect(droneSharePrefs.getUsername(), droneSharePrefs.getPassword());
            } catch (Exception e) {
                Log.e(TAG, "DroneShare uploader error for " + appId, e);
            }
        } else {
            Log.i(TAG, "Skipping live upload for " + appId);
        }
    }

    @Override
    public void notifyConnected() {
        this.gcsHeartbeat.setActive(true);

        // Start a new ga analytics session. The new session will be tagged
        // with the mavlink connection mechanism, as well as whether the user has an active droneshare account.
        GAUtils.startNewSession(null);

        if (!connectedApps.isEmpty()) {
            for (Map.Entry<String, DroneEventsListener> entry : connectedApps.entrySet()) {
                notifyConnected(entry.getKey(), entry.getValue());
            }
        }
    }

    public void kickStartDroneShareUpload() {
        // See if we can at least do a delayed upload
        if (!connectedApps.isEmpty()) {
            for (Map.Entry<String, DroneEventsListener> entry : connectedApps.entrySet()) {
                kickStartDroneShareUpload(entry.getKey(), entry.getValue().getDroneSharePrefs());
            }
        }
    }

    private void kickStartDroneShareUpload(String appId, DroneSharePrefs prefs) {
        if (TextUtils.isEmpty(appId) || prefs == null)
            return;

        UploaderService.kickStart(context, appId, prefs);
    }

    private void notifyDisconnected(String appId, DroneEventsListener listener) {
        if (TextUtils.isEmpty(appId) || listener == null)
            return;

        kickStartDroneShareUpload(appId, listener.getDroneSharePrefs());

        DroneshareClient uploader = tlogUploaders.remove(appId);
        if (uploader != null) {
            try {
                uploader.close();
            } catch (Exception e) {
                Log.e(TAG, "Error while closing the drone share upload handler.", e);
            }
        }
    }

    @Override
    public void notifyDisconnected() {
        this.gcsHeartbeat.setActive(false);

        if (!connectedApps.isEmpty()) {
            for (Map.Entry<String, DroneEventsListener> entry : connectedApps.entrySet()) {
                notifyDisconnected(entry.getKey(), entry.getValue());
            }
        }

        notifyDroneEvent(DroneInterfaces.DroneEventsType.DISCONNECTED);
    }

    private void notifyDroneEvent(DroneInterfaces.DroneEventsType event){
        if(drone != null){
            drone.notifyDroneEvent(event);
        }
    }

    private void handleCommandAck(msg_command_ack ack) {
        if (ack != null) {
            commandTracker.onCommandAck(msg_command_ack.MAVLINK_MSG_ID_COMMAND_ACK, ack);
        }
    }

    @Override
    public void notifyReceivedData(MAVLinkPacket packet) {
        MAVLinkMessage receivedMsg = packet.unpack();

        if (receivedMsg.msgid == msg_command_ack.MAVLINK_MSG_ID_COMMAND_ACK) {
            final msg_command_ack commandAck = (msg_command_ack) receivedMsg;
            handleCommandAck(commandAck);
        } else {
            this.mavLinkMsgHandler.receiveData(receivedMsg);
            if (this.drone != null) {
                this.drone.onMavLinkMessageReceived(receivedMsg);
            }
        }

        if (!connectedApps.isEmpty()) {
            for (DroneEventsListener droneEventsListener : connectedApps.values()) {
                droneEventsListener.onReceivedMavLinkMessage(receivedMsg);
            }
        }

        if (!tlogUploaders.isEmpty()) {
            final byte[] packetData = packet.encodePacket();
            for (DroneshareClient uploader : tlogUploaders.values()) {
                try {
                    uploader.filterMavlink(uploader.interfaceNum, packetData);
                } catch (Exception e) {
                    Log.e(TAG, e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public void onStreamError(String errorMsg) {
        if (connectedApps.isEmpty())
            return;

        for (DroneEventsListener droneEventsListener : connectedApps.values()) {
            droneEventsListener.onConnectionFailed(errorMsg);
        }
    }

    public MavLinkDrone getDrone() {
        return this.drone;
    }

    public Follow getFollowMe() {
        return followMe;
    }

    public boolean isConnected() {
        return drone != null && drone.isConnected() && (!isCompanionComputerEnabled() || soloComp.isConnected());
    }

    @Override
    public DroneAttribute getAttribute(String attributeType) {
        if(drone == null)
            return null;

        switch(attributeType){
            case AttributeType.FOLLOW_STATE:
                return CommonApiUtils.getFollowState(followMe);

            case AttributeType.CAMERA:
                return CommonApiUtils.getCameraProxy(drone, cameraDetails);

            case AttributeType.SOLOLINK_STATE:
                return CommonApiUtils.getSoloLinkState(this);

            default:
                return drone.getAttribute(attributeType);
        }
    }

    @Override
    public void executeAsyncAction(Action action, final ICommandListener listener) {
        final String type = action.getType();
        Bundle data = action.getData();

        switch(type){
            // MISSION ACTIONS
            case MissionActions.ACTION_GENERATE_DRONIE:
                final float bearing = CommonApiUtils.generateDronie(drone);
                if (bearing != -1) {
                    Bundle bundle = new Bundle(1);
                    bundle.putFloat(AttributeEventExtra.EXTRA_MISSION_DRONIE_BEARING, bearing);
                    notifyDroneAttributeEvent(AttributeEvent.MISSION_DRONIE_CREATED, bundle);
                }
                break;

            case MissionActions.ACTION_BUILD_COMPLEX_MISSION_ITEM:
                CommonApiUtils.buildComplexMissionItem(drone, data);
                break;

            //FOLLOW-ME ACTIONS
            case FollowMeActions.ACTION_ENABLE_FOLLOW_ME:
                data.setClassLoader(FollowType.class.getClassLoader());
                FollowType followType = data.getParcelable(FollowMeActions.EXTRA_FOLLOW_TYPE);
                CommonApiUtils.enableFollowMe(this, handler, followType);
                break;

            case FollowMeActions.ACTION_UPDATE_FOLLOW_PARAMS:
                if(followMe != null) {
                    data.setClassLoader(LatLong.class.getClassLoader());

                    final FollowAlgorithm followAlgorithm = followMe.getFollowAlgorithm();
                    if (followAlgorithm != null) {
                        Map<String, Object> paramsMap = new HashMap<>();
                        Set<String> dataKeys = data.keySet();

                        for (String key : dataKeys) {
                            if (FollowType.EXTRA_FOLLOW_ROI_TARGET.equals(key)) {
                                LatLong target = data.getParcelable(key);
                                if (target != null) {
                                    final Coord2D roiTarget;
                                    if (target instanceof LatLongAlt) {
                                        roiTarget = new Coord3D(target.getLatitude(), target.getLongitude(),
                                                ((LatLongAlt) target).getAltitude());
                                    } else {
                                        roiTarget = new Coord2D(target.getLatitude(), target.getLongitude());
                                    }
                                    paramsMap.put(key, roiTarget);
                                }
                            } else
                                paramsMap.put(key, data.get(key));
                        }

                        followAlgorithm.updateAlgorithmParams(paramsMap);
                    }
                }
                break;

            case FollowMeActions.ACTION_DISABLE_FOLLOW_ME:
                CommonApiUtils.disableFollowMe(followMe);
                break;

            //************ SOLOLINK ACTIONS *************//
            case SoloLinkActions.ACTION_SEND_MESSAGE:
                final TLVPacket messageData = data.getParcelable(SoloLinkActions.EXTRA_MESSAGE_DATA);
                if (messageData != null) {
                    CommonApiUtils.sendSoloLinkMessage(this, messageData, listener);
                }
                break;

            case SoloLinkActions.ACTION_UPDATE_WIFI_SETTINGS:
                final String wifiSsid = data.getString(SoloLinkActions.EXTRA_WIFI_SSID);
                final String wifiPassword = data.getString(SoloLinkActions.EXTRA_WIFI_PASSWORD);
                CommonApiUtils.updateSoloLinkWifiSettings(this, wifiSsid, wifiPassword, listener);
                break;

            case SoloLinkActions.ACTION_UPDATE_BUTTON_SETTINGS:
                final SoloButtonSettingSetter buttonSettings = data.getParcelable(SoloLinkActions.EXTRA_BUTTON_SETTINGS);
                if (buttonSettings != null) {
                    CommonApiUtils.updateSoloLinkButtonSettings(this, buttonSettings, listener);
                }
                break;

            case SoloLinkActions.ACTION_UPDATE_CONTROLLER_MODE:
                final @SoloControllerMode.ControllerMode int mode = data.getInt(SoloLinkActions.EXTRA_CONTROLLER_MODE);
                CommonApiUtils.updateSoloLinkControllerMode(this, mode, listener);
                break;

            //**************** CAPABILITY ACTIONS **************//
            case CapabilityActions.ACTION_CHECK_FEATURE_SUPPORT:
                if (listener != null) {
                    final String featureId = data.getString(CapabilityActions.EXTRA_FEATURE_ID);
                    if (!TextUtils.isEmpty(featureId)) {
                        switch (featureId) {
                            case CapabilityApi.FeatureIds.SOLOLINK_VIDEO_STREAMING:
                            case CapabilityApi.FeatureIds.COMPASS_CALIBRATION:
                                if (this.isCompanionComputerEnabled()) {
                                    CommonApiUtils.postSuccessEvent(listener);
                                } else {
                                    CommonApiUtils.postErrorEvent(CommandExecutionError.COMMAND_UNSUPPORTED, listener);
                                }
                                break;

                            default:
                                CommonApiUtils.postErrorEvent(CommandExecutionError.COMMAND_UNSUPPORTED, listener);
                                break;
                        }
                    }
                }
                break;

            default:
                if(drone != null){
                    drone.executeAsyncAction(action, listener);
                }
                else{
                    CommonApiUtils.postErrorEvent(CommandExecutionError.COMMAND_FAILED, listener);
                }
                break;
        }
    }

    public void notifyDroneAttributeEvent(String attributeEvent, Bundle eventInfo){
        if(TextUtils.isEmpty(attributeEvent) || connectedApps.isEmpty())
            return;

        for(DroneEventsListener listener: connectedApps.values()){
            listener.onAttributeEvent(attributeEvent, eventInfo);
        }
    }

    @Override
    public void onDroneEvent(DroneInterfaces.DroneEventsType event, MavLinkDrone drone) {
        switch(event){
            case HEARTBEAT_FIRST:
            case CONNECTED:
                if(isCompanionComputerEnabled()) {
                    //Try connecting the companion computer
                    if(!soloComp.isConnected()) {
                        soloComp.start();
                        return;
                    }
                }

                event = DroneInterfaces.DroneEventsType.CONNECTED;
                break;

            case DISCONNECTED:
                if(isCompanionComputerEnabled()){
                    if(soloComp.isConnected()){
                        soloComp.stop();
                        return;
                    }
                }
                break;
        }

        if (connectedApps.isEmpty())
            return;

        for (DroneEventsListener droneEventsListener : connectedApps.values()) {
            droneEventsListener.onDroneEvent(event, drone);
        }
    }

    @Override
    public void onBeginReceivingParameters() {
        if (connectedApps.isEmpty())
            return;

        for (DroneEventsListener droneEventsListener : connectedApps.values()) {
            droneEventsListener.onBeginReceivingParameters();
        }
    }

    @Override
    public void onParameterReceived(Parameter parameter, int index, int count) {
        if (connectedApps.isEmpty())
            return;

        for (DroneEventsListener droneEventsListener : connectedApps.values()) {
            droneEventsListener.onParameterReceived(parameter, index, count);
        }
    }

    @Override
    public void onEndReceivingParameters() {
        if (connectedApps.isEmpty())
            return;

        for (DroneEventsListener droneEventsListener : connectedApps.values()) {
            droneEventsListener.onEndReceivingParameters();
        }
    }

    public ConnectionParameter getConnectionParameter() {
        return connectionParameter;
    }

    @Override
    public void onMessageLogged(int mavSeverity, String message) {
        if (connectedApps.isEmpty())
            return;

        final int logLevel;
        switch (mavSeverity) {
            case MAV_SEVERITY.MAV_SEVERITY_ALERT:
            case MAV_SEVERITY.MAV_SEVERITY_CRITICAL:
            case MAV_SEVERITY.MAV_SEVERITY_EMERGENCY:
            case MAV_SEVERITY.MAV_SEVERITY_ERROR:
                logLevel = Log.ERROR;
                break;

            case MAV_SEVERITY.MAV_SEVERITY_WARNING:
                logLevel = Log.WARN;
                break;

            case MAV_SEVERITY.MAV_SEVERITY_NOTICE:
                logLevel = Log.INFO;
                break;

            default:
            case MAV_SEVERITY.MAV_SEVERITY_INFO:
                logLevel = Log.VERBOSE;
                break;

            case MAV_SEVERITY.MAV_SEVERITY_DEBUG:
                logLevel = Log.DEBUG;
                break;
        }

        for (DroneEventsListener listener : connectedApps.values()) {
            listener.onMessageLogged(logLevel, message);
        }
    }

    @Override
    public void onCalibrationCancelled() {
        if (connectedApps.isEmpty())
            return;

        for (DroneEventsListener listener : connectedApps.values())
            listener.onCalibrationCancelled();
    }

    @Override
    public void onCalibrationProgress(msg_mag_cal_progress progress) {
        if (connectedApps.isEmpty())
            return;

        for (DroneEventsListener listener : connectedApps.values())
            listener.onCalibrationProgress(progress);
    }

    @Override
    public void onCalibrationCompleted(msg_mag_cal_report report) {
        if (connectedApps.isEmpty())
            return;

        for (DroneEventsListener listener : connectedApps.values())
            listener.onCalibrationCompleted(report);
    }
}
