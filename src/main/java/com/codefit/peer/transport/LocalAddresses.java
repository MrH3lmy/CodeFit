package com.codefit.peer.transport;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Works out which {@link PeerAddress}es a <em>remote</em> peer could actually use to reach a listener
 * bound with a given {@link ListenerBindAddress} on a given port: the local interface addresses the bind
 * covers, minus anything a remote machine can never dial (loopback, link-local - which needs an interface
 * scope an invitation cannot carry - wildcard, multicast). This is what an invitation or a LAN
 * announcement should advertise, so the address and port a peer is told always correspond to a socket that
 * is really listening there. Pure local inspection of this machine's interfaces: no network I/O, no DNS.
 */
public final class LocalAddresses {
    private LocalAddresses() {
    }

    /**
     * @param max upper bound on the number of addresses returned (invitations carry at most 4)
     * @return reachable candidates, site-local IPv4 first, then other IPv4, then IPv6; for a loopback-only
     *         bind, just that loopback address (useful to same-machine peers only); never {@code null}
     */
    public static List<PeerAddress> reachable(ListenerBindAddress bind, int port, int max) {
        if (bind.isLoopbackOnly()) {
            return List.of(new PeerAddress(bind.specificAddress().orElseThrow().getHostAddress(), port));
        }
        List<InetAddress> candidates = new ArrayList<>();
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (isDialableByRemotePeer(address) && bind.covers(address)) {
                        candidates.add(address);
                    }
                }
            }
        } catch (SocketException e) {
            return List.of();
        }
        candidates.sort(Comparator.comparingInt(LocalAddresses::preference));
        List<PeerAddress> result = new ArrayList<>();
        for (InetAddress candidate : candidates) {
            if (result.size() >= max) {
                break;
            }
            String host = candidate.getHostAddress();
            int scope = host.indexOf('%');
            result.add(new PeerAddress(scope < 0 ? host : host.substring(0, scope), port));
        }
        return List.copyOf(result);
    }

    static boolean isDialableByRemotePeer(InetAddress address) {
        return !address.isLoopbackAddress() && !address.isLinkLocalAddress() && !address.isAnyLocalAddress()
                && !address.isMulticastAddress() && (address instanceof Inet4Address || address instanceof Inet6Address);
    }

    private static int preference(InetAddress address) {
        if (address instanceof Inet4Address) {
            return address.isSiteLocalAddress() ? 0 : 1;
        }
        return 2;
    }
}
