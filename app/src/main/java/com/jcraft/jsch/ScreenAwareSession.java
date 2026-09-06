package com.jcraft.jsch;

import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;
import java.net.Socket;

/**
 * Narrow adapter for JSch 2.28.7's package-private packet reader. A successful
 * socket write is not a liveness check: both SSH global-request success and
 * failure replies prove that the authenticated server has responded.
 * Keep this adapter covered when upgrading JSch.
 */
public class ScreenAwareSession extends Session {
    private volatile boolean maintenanceEnabled = true;
    private final AtomicReference<Runnable> probeReply = new AtomicReference<>();
    private final AtomicReference<Socket> transport = new AtomicReference<>();
    private volatile boolean retired;

    public ScreenAwareSession(JSch jsch, String username, String host, int port)
            throws JSchException {
        super(jsch, username, host, port);
    }

    /** Own the socket before connect(), so screen-off can cancel a handshake. */
    public void trackSocket(Socket socket) throws IOException {
        transport.set(socket);
        if (retired) {
            socket.close();
            throw new IOException("Session was retired");
        }
    }

    @Override
    public void connect(int timeout) throws JSchException {
        if (retired) throw new JSchException("Session was retired");
        super.connect(timeout);
    }

    @Override
    public void disconnect() {
        retired = true;
        maintenanceEnabled = false;
        cancelProbe();
        Socket socket = transport.getAndSet(null);
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        super.disconnect();
    }

    public void pauseMaintenance() throws JSchException {
        maintenanceEnabled = false;
        cancelProbe();
        setServerAliveInterval(0);
    }

    public void resumeMaintenance(int intervalMillis) throws JSchException {
        setServerAliveInterval(intervalMillis);
        maintenanceEnabled = true;
    }

    @Override
    public void sendKeepAliveMsg() throws Exception {
        // An already-blocked read can still time out once after setSoTimeout(0).
        if (maintenanceEnabled) super.sendKeepAliveMsg();
    }

    public void prepareProbe(Runnable onReply) {
        probeReply.set(onReply);
    }

    public void cancelProbe() {
        probeReply.set(null);
    }

    @Override
    Buffer read(Buffer buffer) throws Exception {
        Buffer result = super.read(buffer);
        int command = result.getCommand() & 0xff;
        if (command == SSH_MSG_REQUEST_SUCCESS || command == SSH_MSG_REQUEST_FAILURE) {
            Runnable callback = probeReply.getAndSet(null);
            if (callback != null) callback.run();
        }
        return result;
    }
}
