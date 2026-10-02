package com.codefit.peer.discovery;

import com.codefit.peer.protocol.IdentityId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real multicast evidence that two paired devices recognize each other's opt-in LAN announcements while
 * a third, unpaired device's announcements about a different pair go unrecognized. Skipped (not failed)
 * in a sandbox with no multicast-capable network interface, since that is an environment limitation, not
 * a defect — the production code stays off by default regardless (#182: "optional, opt-in").
 */
class LanDiscoveryServiceTest {

    @BeforeAll
    static void requiresMulticastCapableInterface() throws Exception {
        boolean available = false;
        var interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface candidate = interfaces.nextElement();
            if (candidate.isUp() && candidate.supportsMulticast()) {
                available = true;
                break;
            }
        }
        Assumptions.assumeTrue(available, "No multicast-capable network interface in this environment.");
    }

    private static IdentityId randomId() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new IdentityId(bytes);
    }

    @Test
    @Timeout(30)
    void pairedDevicesRecognizeEachOthersAnnouncementsOnTheLan() throws Exception {
        IdentityId alice = randomId();
        IdentityId bob = randomId();

        BlockingQueue<DiscoveredPeer> aliceDiscoveries = new ArrayBlockingQueue<>(10);
        BlockingQueue<DiscoveredPeer> bobDiscoveries = new ArrayBlockingQueue<>(10);

        try {
            try (LanDiscoveryService aliceService = new LanDiscoveryService(alice, () -> List.of(bob), 4001, aliceDiscoveries::add);
                 LanDiscoveryService bobService = new LanDiscoveryService(bob, () -> List.of(alice), 4002, bobDiscoveries::add)) {

                DiscoveredPeer bobSeenByAlice = aliceDiscoveries.poll(20, TimeUnit.SECONDS);
                DiscoveredPeer aliceSeenByBob = bobDiscoveries.poll(20, TimeUnit.SECONDS);

                assertNotNull(bobSeenByAlice, "alice should discover bob via LAN announcement");
                assertEquals(bob, bobSeenByAlice.contactIdentityId());
                assertEquals(4002, bobSeenByAlice.address().port());

                assertNotNull(aliceSeenByBob, "bob should discover alice via LAN announcement");
                assertEquals(alice, aliceSeenByBob.contactIdentityId());
                assertEquals(4001, aliceSeenByBob.address().port());
            }
        } catch (IOException e) {
            Assumptions.assumeTrue(false, "Multicast unavailable in this sandbox: " + e.getMessage());
        }
    }

    @Test
    @Timeout(30)
    void anUnpairedThirdPartyIsNeverRecognized() throws Exception {
        IdentityId alice = randomId();
        IdentityId bob = randomId();
        IdentityId stranger = randomId();

        BlockingQueue<DiscoveredPeer> strangerDiscoveries = new ArrayBlockingQueue<>(10);

        try {
            try (LanDiscoveryService aliceService = new LanDiscoveryService(alice, () -> List.of(bob), 4101, event -> { });
                 LanDiscoveryService strangerService = new LanDiscoveryService(stranger, List::of, 4102, strangerDiscoveries::add)) {
                // The stranger has no paired contacts at all, so it can never recognize alice's announcements,
                // even though it will receive the raw multicast packets on the same LAN.
                DiscoveredPeer discovered = strangerDiscoveries.poll(5, TimeUnit.SECONDS);
                assertNull(discovered, "an unpaired stranger must never recognize anyone's announcement");
            }
        } catch (IOException e) {
            Assumptions.assumeTrue(false, "Multicast unavailable in this sandbox: " + e.getMessage());
        }
    }
}
