package org.droidplanner.core.MAVLink.connection;

import com.MAVLink.MAVLinkPacket;
import com.MAVLink.Parser;

import org.droidplanner.core.model.Logger;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base for mavlink connection implementations.
 */
public abstract class MavLinkConnection {

    private static final String TAG = MavLinkConnection.class.getSimpleName();

    /*
     * MavLink connection states
     */
    public static final int MAVLINK_DISCONNECTED = 0;
    public static final int MAVLINK_CONNECTING = 1;
    public static final int MAVLINK_CONNECTED = 2;

    /**
     * Size of the buffer used to read messages from the mavlink connection.
     */
    private static final int READ_BUFFER_SIZE = 4096;

    /**
     * Set of listeners subscribed to this mavlink connection. We're using a
     * ConcurrentSkipListSet because the object will be accessed from multiple
     * threads concurrently.
     */
    private final ConcurrentHashMap<String, MavLinkConnectionListener> mListeners = new ConcurrentHashMap<String, MavLinkConnectionListener>();

    /**
     * Queue the set of packets to send via the mavlink connection. A thread
     * will be blocking on it until there's element(s) available to send.
     */
    private final LinkedBlockingQueue<MAVLinkPacket> mPacketsToSend = new LinkedBlockingQueue<MAVLinkPacket>();

    private final AtomicInteger mConnectionStatus = new AtomicInteger(MAVLINK_DISCONNECTED);
    private final AtomicLong mConnectionTime = new AtomicLong(-1);

    /**
     * Listen for incoming data on the mavlink connection.
     */
    private final Runnable mConnectingTask = new Runnable() {

        @Override
        public void run() {
            Thread sendingThread = null;

            // Load the connection specific preferences
            loadPreferences();

            try {
                // Open the connection
                openConnection();
                mConnectionStatus.set(MAVLINK_CONNECTED);

                final long connectionTime = System.currentTimeMillis();
                mConnectionTime.set(connectionTime);
                reportConnect(connectionTime);

                // Launch the 'Sending' threads
                sendingThread = new Thread(mSendingTask, "MavLinkConnection-Sending Thread");
                sendingThread.start();

                final Parser parser = new Parser();
                parser.stats.mavlinkResetStats();

                final byte[] readBuffer = new byte[READ_BUFFER_SIZE];

                while (mConnectionStatus.get() == MAVLINK_CONNECTED) {
                    int bufferSize = readDataBlock(readBuffer);
                    handleData(parser, bufferSize, readBuffer);
                }
            } catch (IOException e) {
                // Ignore errors while shutting down
                if (mConnectionStatus.get() != MAVLINK_DISCONNECTED) {
                    reportComError(e.getMessage());
                    mLogger.logErr(TAG, e);
                }
            } finally {
                if (sendingThread != null && sendingThread.isAlive()) {
                    sendingThread.interrupt();
                }

                disconnect();
            }
        }

        private void handleData(Parser parser, int bufferSize, byte[] buffer) {
            if (bufferSize < 1) {
                return;
            }

            for (int i = 0; i < bufferSize; i++) {
                MAVLinkPacket receivedPacket = parser.mavlink_parse_char(buffer[i] & 0x00ff);
                if (receivedPacket != null) {
                    reportReceivedPacket(receivedPacket);
                }
            }
        }
    };

    /**
     * Blocks until there's packet(s) to send, then dispatch them.
     */
    private final Runnable mSendingTask = new Runnable() {
        @Override
        public void run() {
            try {
                while (mConnectionStatus.get() == MAVLINK_CONNECTED) {
                    final MAVLinkPacket packet = mPacketsToSend.take();
                    byte[] buffer = packet.encodePacket();

                    try {
                        sendBuffer(buffer);
                    } catch (IOException e) {
                        reportComError(e.getMessage());
                        mLogger.logErr(TAG, e);
                    }
                }
            } catch (InterruptedException e) {
                mLogger.logVerbose(TAG, e.getMessage());
            } finally {
                disconnect();
            }
        }
    };

    protected final Logger mLogger = initLogger();

    private Thread mTaskThread;

    /**
     * Establish a mavlink connection. If the connection is successful, it will
     * be reported through the MavLinkConnectionListener interface.
     */
    public void connect() {
        if (mConnectionStatus.compareAndSet(MAVLINK_DISCONNECTED, MAVLINK_CONNECTING)) {
            mTaskThread = new Thread(mConnectingTask, "MavLinkConnection-Connecting Thread");
            mTaskThread.start();
        }
    }

    /**
     * Disconnect a mavlink connection. If the operation is successful, it will
     * be reported through the MavLinkConnectionListener interface.
     */
    public void disconnect() {
        if (mConnectionStatus.get() == MAVLINK_DISCONNECTED || mTaskThread == null) {
            return;
        }

        try {
            final long disconnectTime = System.currentTimeMillis();

            mConnectionStatus.set(MAVLINK_DISCONNECTED);
            mConnectionTime.set(-1);
            if (mTaskThread.isAlive() && !mTaskThread.isInterrupted()) {
                mTaskThread.interrupt();
            }

            closeConnection();
            reportDisconnect(disconnectTime);
        } catch (IOException e) {
            mLogger.logErr(TAG, e);
            reportComError(e.getMessage());
        }
    }

    public int getConnectionStatus() {
        return mConnectionStatus.get();
    }

    public void sendMavPacket(MAVLinkPacket packet) {
        if (!mPacketsToSend.offer(packet)) {
            mLogger.logErr(TAG, "Unable to send mavlink packet. Packet queue is full!");
        }
    }

    /**
     * Adds a listener to the mavlink connection.
     *
     * @param listener
     * @param tag      Listener tag
     */
    public void addMavLinkConnectionListener(String tag, MavLinkConnectionListener listener) {
        mListeners.put(tag, listener);

        if (getConnectionStatus() == MAVLINK_CONNECTED) {
            listener.onConnect(mConnectionTime.get());
        }
    }

    /**
     * @return the count of connection listeners.
     */
    public int getMavLinkConnectionListenersCount() {
        return mListeners.size();
    }

    /**
     * Used to query the presence of a connection listener.
     *
     * @param tag connection listener tag
     * @return true if the tag is present in the listeners list.
     */
    public boolean hasMavLinkConnectionListener(String tag) {
        return mListeners.containsKey(tag);
    }

    /**
     * Removes the specified listener.
     *
     * @param tag Listener tag
     */
    public void removeMavLinkConnectionListener(String tag) {
        mListeners.remove(tag);
    }

    /**
     * Removes all the connection listeners.
     */
    public void removeAllMavLinkConnectionListeners() {
        mListeners.clear();
    }

    protected abstract Logger initLogger();

    protected abstract void openConnection() throws IOException;

    protected abstract int readDataBlock(byte[] buffer) throws IOException;

    protected abstract void sendBuffer(byte[] buffer) throws IOException;

    protected abstract void closeConnection() throws IOException;

    protected abstract void loadPreferences();

    /**
     * @return The type of this mavlink connection.
     */
    public abstract int getConnectionType();

    protected Logger getLogger() {
        return mLogger;
    }

    /**
     * Utility method to notify the mavlink listeners about communication
     * errors.
     *
     * @param errMsg
     */
    protected void reportComError(String errMsg) {
        if (mListeners.isEmpty())
            return;

        for (MavLinkConnectionListener listener : mListeners.values()) {
            listener.onComError(errMsg);
        }
    }

    /**
     * Utility method to notify the mavlink listeners about a successful
     * connection.
     */
    protected void reportConnect(long connectionTime) {
        for (MavLinkConnectionListener listener : mListeners.values()) {
            listener.onConnect(connectionTime);
        }
    }

    /**
     * Utility method to notify the mavlink listeners about a connection
     * disconnect.
     */
    protected void reportDisconnect(long disconnectTime) {
        if (mListeners.isEmpty())
            return;

        for (MavLinkConnectionListener listener : mListeners.values()) {
            listener.onDisconnect(disconnectTime);
        }
    }

    /**
     * Utility method to notify the mavlink listeners about received messages.
     *
     * @param packet received mavlink packet
     */
    private void reportReceivedPacket(MAVLinkPacket packet) {
        if (mListeners.isEmpty())
            return;

        for (MavLinkConnectionListener listener : mListeners.values()) {
            listener.onReceivePacket(packet);
        }
    }

}
