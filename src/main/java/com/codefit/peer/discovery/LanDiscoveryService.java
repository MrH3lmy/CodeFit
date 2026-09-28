package com.codefit.peer.discovery;

import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.transport.PeerAddress;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Optional, opt-in LAN peer discovery (#182; ADR-0001 §4). <strong>Off by default</strong> — nothing in
 * this class runs unless a caller explicitly constructs and starts it, which is exactly what "opt-in"
 * requires. Announcements identify no one to a LAN stranger: see {@link LanAnnouncementCodec} for the
 * recognition-tag scheme. This uses link-local (administratively-scoped, RFC 2365) multicast only — it
 * never contacts any address outside the local network, and it is not a discovery/bootstrap service:
 * peers must already be paired contacts to be recognized at all.
 */
public final class LanDiscoveryService implements AutoCloseable {
    /** Administratively-scoped (site-local) multicast address (RFC 2365), never routed off the LAN. */
    private static final String MULTICAST_GROUP = "239.192.42.99";
    private static final int MULTICAST_PORT = 52735;
    private static final int RECEIVE_BUFFER_LENGTH = 512;
    private static final long ANNOUNCE_INTERVAL_MILLIS = LanAnnouncementCodec.SLOT_SECONDS * 1000 / 2;

    private final MulticastSocket socket;
    private final InetSocketAddress group;
    private final NetworkInterface networkInterface;
    private final IdentityId localIdentityId;
    private final Supplier<List<IdentityId>> pairedContactIds;
    private final int tcpListenPort;
    private final Consumer<DiscoveredPeer> onDiscovered;
    private final Thread announceThread;
    private final Thread listenThread;
    private volatile boolean closing;

    public LanDiscoveryService(IdentityId localIdentityId, Supplier<List<IdentityId>> pairedContactIds, int tcpListenPort,
                                Consumer<DiscoveredPeer> onDiscovered) throws IOException {
        this.localIdentityId = localIdentityId;
        this.pairedContactIds = pairedContactIds;
        this.tcpListenPort = tcpListenPort;
        this.onDiscovered = onDiscovered;

        this.group = new InetSocketAddress(InetAddress.getByName(MULTICAST_GROUP), MULTICAST_PORT);
        this.networkInterface = preferredInterface();
        this.socket = new MulticastSocket(MULTICAST_PORT);
        socket.setSoTimeout(1000);
        socket.setLoopbackMode(false); // do not disable loopback: same-host peers (and tests) must see each other
        socket.joinGroup(group, networkInterface);

        this.announceThread = new Thread(this::announceLoop, "codefit-lan-discovery-announce");
        announceThread.setDaemon(true);
        this.listenThread = new Thread(this::listenLoop, "codefit-lan-discovery-listen");
        listenThread.setDaemon(true);
        announceThread.start();
        listenThread.start();
    }

    private static NetworkInterface preferredInterface() throws IOException {
        NetworkInterface loopback = null;
        var interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface candidate = interfaces.nextElement();
            if (!candidate.isUp() || !candidate.supportsMulticast()) {
                continue;
            }
            if (candidate.isLoopback()) {
                loopback = candidate;
                continue;
            }
            return candidate;
        }
        if (loopback != null) {
            return loopback;
        }
        throw new IOException("No multicast-capable network interface is available.");
    }

    private void announceLoop() {
        while (!closing) {
            try {
                long slot = LanAnnouncementCodec.currentTimeSlot(Instant.now());
                for (IdentityId contactId : safeSnapshot()) {
                    byte[] key = LanAnnouncementCodec.recognitionKey(localIdentityId, contactId);
                    byte[] tag = LanAnnouncementCodec.computeTag(key, slot, localIdentityId);
                    byte[] packetBytes = LanAnnouncementCodec.encode(slot, tcpListenPort, tag);
                    socket.send(new DatagramPacket(packetBytes, packetBytes.length, group.getAddress(), MULTICAST_PORT));
                }
                Thread.sleep(ANNOUNCE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                if (closing) {
                    return;
                }
                // A transient send failure (e.g. interface went down) is not fatal; try again next cycle.
            }
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_LENGTH];
        while (!closing) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException expected) {
                continue;
            } catch (IOException e) {
                if (closing) {
                    return;
                }
                continue;
            }
            InetSocketAddress sender = new InetSocketAddress(packet.getAddress(), packet.getPort());
            LanAnnouncementCodec.decode(java.util.Arrays.copyOf(packet.getData(), packet.getLength()), packet.getLength(), sender, Instant.now())
                    .ifPresent(this::matchAgainstKnownContacts);
        }
    }

    private void matchAgainstKnownContacts(LanAnnouncement announcement) {
        Instant now = Instant.now();
        for (IdentityId contactId : safeSnapshot()) {
            byte[] key = LanAnnouncementCodec.recognitionKey(localIdentityId, contactId);
            if (LanAnnouncementCodec.recognizes(key, announcement, contactId, now)) {
                onDiscovered.accept(new DiscoveredPeer(contactId,
                        new PeerAddress(announcement.senderHost(), announcement.senderPort()), now));
                return;
            }
        }
    }

    private List<IdentityId> safeSnapshot() {
        try {
            return List.copyOf(pairedContactIds.get());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @Override
    public void close() {
        closing = true;
        try {
            socket.leaveGroup(group, networkInterface);
        } catch (IOException ignored) {
            // best-effort
        }
        socket.close();
        joinQuietly(announceThread);
        joinQuietly(listenThread);
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
