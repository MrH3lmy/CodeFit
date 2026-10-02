package com.codefit.peer.discovery;

import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.transport.ListenerBindAddress;
import com.codefit.peer.transport.PeerAddress;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
 *
 * <p><strong>What is announced is something a peer can actually dial.</strong> An announcement carries only
 * the TCP port; the receiver pairs it with the datagram's source address. That pair is reachable exactly
 * when the TCP listener accepts connections on that address, so announcements are sent and received only on
 * interfaces the listener's {@link ListenerBindAddress} actually covers (every non-loopback multicast
 * interface for the wildcard; only the interface owning the address for a specific bind), and each
 * datagram is sent out of the interface it is meant to describe rather than whichever one the OS defaults
 * to. A loopback-only listener cannot be reached by any LAN peer, so announcing it would only publish a
 * dead address: that combination is rejected.
 */
public final class LanDiscoveryService implements AutoCloseable {
    /** Administratively-scoped (site-local) multicast address (RFC 2365), never routed off the LAN. */
    private static final String MULTICAST_GROUP = "239.192.42.99";
    private static final int MULTICAST_PORT = 52735;
    private static final int RECEIVE_BUFFER_LENGTH = 512;
    private static final long ANNOUNCE_INTERVAL_MILLIS = LanAnnouncementCodec.SLOT_SECONDS * 1000 / 2;

    private final MulticastSocket socket;
    private final InetSocketAddress group;
    private final List<NetworkInterface> networkInterfaces;
    private final IdentityId localIdentityId;
    private final Supplier<List<IdentityId>> pairedContactIds;
    private final int tcpListenPort;
    private final Consumer<DiscoveredPeer> onDiscovered;
    private final Thread announceThread;
    private final Thread listenThread;
    private volatile boolean closing;

    public LanDiscoveryService(IdentityId localIdentityId, Supplier<List<IdentityId>> pairedContactIds, int tcpListenPort,
                                Consumer<DiscoveredPeer> onDiscovered) throws IOException {
        this(localIdentityId, pairedContactIds, tcpListenPort, onDiscovered, ListenerBindAddress.wildcard());
    }

    /**
     * @param listenerBinding how the TCP listener whose port is announced is bound
     * @throws IOException no multicast-capable interface the listener is reachable on exists, or the
     *                     listener is loopback-only (nothing on the LAN could ever dial it)
     */
    public LanDiscoveryService(IdentityId localIdentityId, Supplier<List<IdentityId>> pairedContactIds, int tcpListenPort,
                                Consumer<DiscoveredPeer> onDiscovered, ListenerBindAddress listenerBinding) throws IOException {
        this.localIdentityId = localIdentityId;
        this.pairedContactIds = pairedContactIds;
        this.tcpListenPort = tcpListenPort;
        this.onDiscovered = onDiscovered;

        this.group = new InetSocketAddress(InetAddress.getByName(MULTICAST_GROUP), MULTICAST_PORT);
        List<NetworkInterface> candidates = announceableInterfaces(listenerBinding);
        this.socket = new MulticastSocket(MULTICAST_PORT);
        socket.setSoTimeout(1000);
        socket.setLoopbackMode(false); // do not disable loopback: same-host peers (and tests) must see each other
        List<NetworkInterface> joined = new ArrayList<>();
        for (NetworkInterface candidate : candidates) {
            try {
                socket.joinGroup(group, candidate);
                joined.add(candidate);
            } catch (IOException cannotJoinOnThisInterface) {
                // skip it: announce and listen on the interfaces that did work
            }
        }
        if (joined.isEmpty()) {
            socket.close();
            throw new IOException("No multicast-capable network interface is available.");
        }
        this.networkInterfaces = List.copyOf(joined);

        this.announceThread = new Thread(this::announceLoop, "codefit-lan-discovery-announce");
        announceThread.setDaemon(true);
        this.listenThread = new Thread(this::listenLoop, "codefit-lan-discovery-listen");
        listenThread.setDaemon(true);
        announceThread.start();
        listenThread.start();
    }

    /**
     * Interfaces to join/announce on, given how the listener is bound. Non-loopback interfaces that are up,
     * multicast-capable and carry an IPv4 address the listener covers; the loopback interface only as a
     * same-host fallback when the listener's bind covers it and nothing else qualifies.
     */
    static List<NetworkInterface> announceableInterfaces(ListenerBindAddress listenerBinding) throws IOException {
        if (listenerBinding.isLoopbackOnly()) {
            throw new IOException("The listener is bound to loopback only, so no LAN peer could dial an announced address. "
                    + "Bind it to the wildcard or a LAN interface address before enabling LAN discovery.");
        }
        List<NetworkInterface> lan = new ArrayList<>();
        NetworkInterface loopback = null;
        for (NetworkInterface candidate : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!candidate.isUp() || !candidate.supportsMulticast()) {
                continue;
            }
            boolean coversListener = Collections.list(candidate.getInetAddresses()).stream()
                    .anyMatch(address -> address instanceof java.net.Inet4Address && listenerBinding.covers(address));
            if (!coversListener) {
                continue;
            }
            if (candidate.isLoopback()) {
                loopback = candidate;
            } else {
                lan.add(candidate);
            }
        }
        if (!lan.isEmpty()) {
            return lan;
        }
        if (loopback != null && listenerBinding.isWildcard()) {
            return List.of(loopback);
        }
        throw new IOException("No multicast-capable network interface the listener is reachable on is available.");
    }

    private void announceLoop() {
        while (!closing) {
            long slot = LanAnnouncementCodec.currentTimeSlot(Instant.now());
            for (IdentityId contactId : safeSnapshot()) {
                byte[] key = LanAnnouncementCodec.recognitionKey(localIdentityId, contactId);
                byte[] tag = LanAnnouncementCodec.computeTag(key, slot, localIdentityId);
                byte[] packetBytes = LanAnnouncementCodec.encode(slot, tcpListenPort, tag);
                for (NetworkInterface outgoing : networkInterfaces) {
                    try {
                        socket.setNetworkInterface(outgoing);
                        socket.send(new DatagramPacket(packetBytes, packetBytes.length, group.getAddress(), MULTICAST_PORT));
                    } catch (IOException e) {
                        if (closing) {
                            return;
                        }
                        // A transient send failure (e.g. this interface went down) must neither stop the other
                        // interfaces nor skip the sleep below; try again next cycle.
                    }
                }
            }
            try {
                Thread.sleep(ANNOUNCE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
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
        for (NetworkInterface joinedInterface : networkInterfaces) {
            try {
                socket.leaveGroup(group, joinedInterface);
            } catch (IOException ignored) {
                // best-effort
            }
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
