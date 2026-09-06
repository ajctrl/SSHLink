package com.jcraft.jsch;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenAwareSessionTest {
    @Test public void retirementClosesConnectingSocketAndRejectsLateSockets() throws Exception {
        ScreenAwareSession session = session();
        Socket connecting = new Socket();
        session.trackSocket(connecting);
        session.disconnect();
        assertTrue(connecting.isClosed());
        Socket late = new Socket();
        assertThrows(IOException.class, () -> session.trackSocket(late));
        assertTrue(late.isClosed());
        assertThrows(JSchException.class, () -> session.connect(100));
    }
    private ScreenAwareSession session() throws Exception {
        return new ScreenAwareSession(new JSch(), "test", "localhost", 22);
    }

    private void receive(ScreenAwareSession session, int command) throws Exception {
        // Unencrypted packet reader fixture; no socket or server is required.
        byte[] packet = new byte[16];
        packet[3] = 12;
        packet[4] = 10;
        packet[5] = (byte) command;
        IO io = new IO();
        io.setInputStream(new ByteArrayInputStream(packet));
        Field field = Session.class.getDeclaredField("io");
        field.setAccessible(true);
        field.set(session, io);
        session.read(new Buffer());
    }

    @Test public void bothGlobalReplyTypesCompleteProbeOnce() throws Exception {
        for (int command : new int[] {Session.SSH_MSG_REQUEST_SUCCESS, Session.SSH_MSG_REQUEST_FAILURE}) {
            ScreenAwareSession session = session();
            AtomicInteger replies = new AtomicInteger();
            session.prepareProbe(replies::incrementAndGet);
            receive(session, command);
            receive(session, command);
            assertEquals(1, replies.get());
        }
    }

    @Test public void unrelatedPacketDoesNotConfirmLiveness() throws Exception {
        ScreenAwareSession session = session();
        AtomicInteger replies = new AtomicInteger();
        session.prepareProbe(replies::incrementAndGet);
        receive(session, Session.SSH_MSG_CHANNEL_EOF);
        assertEquals(0, replies.get());
        receive(session, Session.SSH_MSG_REQUEST_FAILURE);
        assertEquals(1, replies.get());
    }

    @Test public void pauseSuppressesStaleTimeoutSendAndCancelsProbe() throws Exception {
        ScreenAwareSession session = session();
        session.setServerAliveInterval(30_000);
        AtomicInteger replies = new AtomicInteger();
        session.prepareProbe(replies::incrementAndGet);
        session.pauseMaintenance();
        // Would fail trying to write to the absent socket if not suppressed.
        session.sendKeepAliveMsg();
        assertEquals(0, session.getServerAliveInterval());
        receive(session, Session.SSH_MSG_REQUEST_FAILURE);
        assertEquals(0, replies.get());
        session.resumeMaintenance(60_000);
        assertEquals(60_000, session.getServerAliveInterval());
        session.prepareProbe(replies::incrementAndGet);
        receive(session, Session.SSH_MSG_REQUEST_FAILURE);
        assertEquals(1, replies.get());
    }
}
