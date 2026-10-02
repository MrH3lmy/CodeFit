package com.codefit.peer.transport;

import com.codefit.peer.transport.TransportTestSupport.FakeContacts;
import com.codefit.peer.transport.TransportTestSupport.TestPeer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.codefit.peer.transport.TransportTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Proves the listener is reachable the way real LAN peers reach it: through one of this machine's own
 * non-loopback interface addresses, not just 127.0.0.1. (Two devices on a LAN cannot use loopback; a
 * listener bound only to loopback refuses a connection to the machine's LAN address even from the same
 * host, which is exactly what the old hardcoded-loopback bind did.) The bind strategy is deliberate and
 * each variant is checked: wildcard, one specific interface, and loopback-only.
 *
 * <p>Needs at least one non-loopback, non-link-local interface address on the machine running the tests;
 * if there is none the tests are skipped (assumption), never silently passed.
 */
class PeerListenerLanReachabilityTest {
    private final List<PeerNetworkService> services = new ArrayList<>();

    @AfterEach
    void tearDown() {
        services.forEach(PeerNetworkService::disable);
    }

    private static PeerAddress lanAddress(int port) {
        List<PeerAddress> lan = LocalAddresses.reachable(ListenerBindAddress.wildcard(), port, 4);
        assumeFalse(lan.isEmpty(), "no non-loopback interface address on this machine; cannot exercise LAN reachability");
        return lan.get(0);
    }

    private record Pair(PeerNetworkService alice, PeerNetworkService bob, TestPeer bobPeer, TransportKeyMaterial bobKey, int bobPort) {
    }

    private Pair pair(ListenerBindAddress bobBinding) throws Exception {
        TestPeer alice = TestPeer.create();
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial aliceKey = alice.newTransportKey(hoursAgo(2));
        TransportKeyMaterial bobKey = bob.newTransportKey(hoursAgo(2));
        PeerNetworkService aliceService = new PeerNetworkService(new FakeContacts().pair(bob, pinOf(bobKey)), event -> { });
        PeerNetworkService bobService = new PeerNetworkService(new FakeContacts().pair(alice, pinOf(aliceKey)), event -> { });
        services.add(aliceService);
        services.add(bobService);
        aliceService.enable(alice.identity, aliceKey, 1L, 0, ListenerBindAddress.loopbackOnly());
        bobService.enable(bob.identity, bobKey, 1L, 0, bobBinding);
        return new Pair(aliceService, bobService, bob, bobKey, bobService.listeningPort().orElseThrow());
    }

    private static DialOutcome dial(Pair pair, PeerAddress address) throws Exception {
        return pair.alice().connect(pair.bobPeer().id(), address, keyOf(pair.bobKey()), singleAttempt(), new AtomicBoolean(false))
                .get(20, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(60)
    void aWildcardListenerAcceptsAConnectionThroughANonLoopbackInterfaceAddress() throws Exception {
        Pair pair = pair(ListenerBindAddress.wildcard());
        PeerAddress lan = lanAddress(pair.bobPort());
        assertFalse(lan.toInetAddress().isLoopbackAddress());

        DialOutcome outcome = dial(pair, lan);

        assertTrue(outcome.result().authenticated(), "connection via " + lan + " should authenticate: " + outcome.result());
        InetSocketAddress remote = (InetSocketAddress) outcome.connection().remoteAddress();
        assertEquals(lan.toInetAddress(), remote.getAddress(), "the socket really went to the LAN address");
        assertFalse(remote.getAddress().isLoopbackAddress());
        // Reachability is not trust: the same authenticated protocol ran over that LAN connection.
        assertEquals(keyOf(pair.bobKey()), outcome.result().remoteBinding().transportKey());
    }

    @Test
    @Timeout(60)
    void aLoopbackOnlyListenerIsNotReachableThroughTheLanAddressWhichIsWhatTheOldHardcodedBindDid() throws Exception {
        Pair pair = pair(ListenerBindAddress.loopbackOnly());
        PeerAddress lan = lanAddress(pair.bobPort());

        DialOutcome viaLan = dial(pair, lan);
        assertFalse(viaLan.result().authenticated());
        assertEquals(ConnectionFailureReason.CONNECTION_REFUSED, viaLan.result().failureReason(), viaLan.result().detail());

        DialOutcome viaLoopback = dial(pair, new PeerAddress("127.0.0.1", pair.bobPort()));
        assertTrue(viaLoopback.result().authenticated(), "same-machine access still works: " + viaLoopback.result());
    }

    @Test
    @Timeout(60)
    void aListenerBoundToOneInterfaceAddressAnswersThatAddressOnly() throws Exception {
        PeerAddress lanProbe = lanAddress(1);
        Pair pair = pair(ListenerBindAddress.of(lanProbe.toInetAddress()));
        PeerAddress lan = new PeerAddress(lanProbe.host(), pair.bobPort());

        DialOutcome viaLan = dial(pair, lan);
        assertTrue(viaLan.result().authenticated(), viaLan.result().toString());

        DialOutcome viaLoopback = dial(pair, new PeerAddress("127.0.0.1", pair.bobPort()));
        assertFalse(viaLoopback.result().authenticated());
        assertEquals(ConnectionFailureReason.CONNECTION_REFUSED, viaLoopback.result().failureReason());
    }

    @Test
    void reachableAddressesAreExactlyWhatTheBindCoversAndNeverALoopbackOrLinkLocalAddress() {
        List<PeerAddress> wildcard = LocalAddresses.reachable(ListenerBindAddress.wildcard(), 4242, 4);
        assertTrue(wildcard.size() <= 4);
        for (PeerAddress address : wildcard) {
            InetAddress inet = address.toInetAddress();
            assertEquals(4242, address.port());
            assertFalse(inet.isLoopbackAddress() || inet.isLinkLocalAddress() || inet.isAnyLocalAddress() || inet.isMulticastAddress(), address.toString());
        }

        assertEquals(List.of(new PeerAddress("127.0.0.1", 4242)),
                LocalAddresses.reachable(ListenerBindAddress.loopbackOnly(), 4242, 4),
                "a loopback-only listener is reachable by this machine only, and says so");

        assumeFalse(wildcard.isEmpty());
        PeerAddress first = wildcard.get(0);
        assertEquals(List.of(first), LocalAddresses.reachable(ListenerBindAddress.of(first.toInetAddress()), 4242, 4),
                "binding to one interface address advertises only that address");
    }

    @Test
    void bindStrategiesBehaveAsDocumented() throws Exception {
        assertTrue(ListenerBindAddress.wildcard().isWildcard());
        assertTrue(ListenerBindAddress.of(InetAddress.getByName("0.0.0.0")).isWildcard());
        assertTrue(ListenerBindAddress.loopbackOnly().isLoopbackOnly());
        assertFalse(ListenerBindAddress.wildcard().isLoopbackOnly());
        assertTrue(ListenerBindAddress.wildcard().covers(InetAddress.getByName("192.0.2.9")));
        assertFalse(ListenerBindAddress.loopbackOnly().covers(InetAddress.getByName("192.0.2.9")));
        assertThrows(IllegalArgumentException.class, () -> ListenerBindAddress.of(InetAddress.getByName("224.0.0.1")));
    }

    @Test
    void networkingIsStillOffByDefaultAndTheDefaultEnableBindsTheWildcard() throws Exception {
        TestPeer peer = TestPeer.create();
        PeerNetworkService service = new PeerNetworkService(id -> KnownContactLookup.Status.UNKNOWN, event -> { });
        services.add(service);
        assertFalse(service.isEnabled());
        assertTrue(service.boundAddress().isEmpty());
        service.enable(peer.identity, peer.newTransportKey(hoursAgo(1)), 1L, 0);
        assertTrue(service.boundAddress().orElseThrow().isWildcard());
    }

    @Test
    void aFailedBindLeavesTheServiceDisabledAndHoldingNoKeyMaterial() throws Exception {
        try (java.net.ServerSocket taken = new java.net.ServerSocket(0)) {
            TestPeer peer = TestPeer.create();
            PeerNetworkService service = new PeerNetworkService(id -> KnownContactLookup.Status.UNKNOWN, event -> { });
            services.add(service);
            assertThrows(java.io.IOException.class,
                    () -> service.enable(peer.identity, peer.newTransportKey(hoursAgo(1)), 1L, taken.getLocalPort()));
            assertFalse(service.isEnabled());
            assertTrue(service.currentBinding().isEmpty());
            assertTrue(service.boundAddress().isEmpty());
        }
    }
}
