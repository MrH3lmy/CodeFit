package com.codefit.peer.discovery;

import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.transport.ListenerBindAddress;
import com.codefit.peer.transport.LocalAddresses;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * LAN discovery must only announce an address/port a peer can actually dial. An announcement carries the TCP
 * listen port and the receiver pairs it with the datagram's source address, so announcements may only
 * travel on interfaces the TCP listener is really reachable on.
 */
class LanDiscoveryInterfaceSelectionTest {

    @Test
    void aLoopbackOnlyListenerIsNeverAnnouncedBecauseNoLanPeerCouldDialIt() {
        IOException refused = assertThrows(IOException.class,
                () -> LanDiscoveryService.announceableInterfaces(ListenerBindAddress.loopbackOnly()));
        assertTrue(refused.getMessage().contains("loopback"), refused.getMessage());

        assertThrows(IOException.class, () -> new LanDiscoveryService(new IdentityId(new byte[32]), List::of, 4242,
                discovered -> { }, ListenerBindAddress.loopbackOnly()));
    }

    @Test
    void aWildcardListenerIsAnnouncedOnEveryLanInterfaceNeverOnLoopbackWhenARealOneExists() throws Exception {
        List<NetworkInterface> chosen = LanDiscoveryService.announceableInterfaces(ListenerBindAddress.wildcard());
        assertFalse(chosen.isEmpty());
        boolean lanExists = !LocalAddresses.reachable(ListenerBindAddress.wildcard(), 1, 4).isEmpty();
        if (lanExists) {
            for (NetworkInterface networkInterface : chosen) {
                assertFalse(networkInterface.isLoopback(), networkInterface.getName() + " is loopback but a LAN interface exists");
            }
        }
    }

    @Test
    void aListenerBoundToOneAddressIsAnnouncedOnlyOnTheInterfaceThatOwnsIt() throws Exception {
        var lan = LocalAddresses.reachable(ListenerBindAddress.wildcard(), 1, 4).stream()
                .filter(address -> address.toInetAddress() instanceof java.net.Inet4Address).findFirst();
        assumeFalse(lan.isEmpty(), "no non-loopback IPv4 interface address on this machine");
        InetAddress bound = lan.get().toInetAddress();

        List<NetworkInterface> chosen = LanDiscoveryService.announceableInterfaces(ListenerBindAddress.of(bound));

        assertFalse(chosen.isEmpty());
        for (NetworkInterface networkInterface : chosen) {
            assertTrue(Collections.list(networkInterface.getInetAddresses()).contains(bound),
                    networkInterface.getName() + " does not own " + bound);
        }
    }

    @Test
    void aListenerBoundToAnAddressNoInterfaceOwnsHasNowhereToBeAnnounced() throws Exception {
        assertThrows(IOException.class, () -> LanDiscoveryService.announceableInterfaces(
                ListenerBindAddress.of(InetAddress.getByName("203.0.113.77"))));
    }
}
