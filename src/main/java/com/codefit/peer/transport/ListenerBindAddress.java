package com.codefit.peer.transport;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;

/**
 * Which local address {@link PeerListener} binds its TCP server socket to: a deliberate, explicit
 * choice rather than an accident of the JDK default. Three strategies:
 * <ul>
 *   <li>{@link #wildcard()} - every local interface. What real LAN peers need, and the default used by
 *       {@link PeerNetworkService#enable}. Reachability is not trust: the listener still accepts nobody
 *       until mutual TLS <em>and</em> the identity-signed {@code IDENTITY_BINDING} exchange succeed
 *       ({@code docs/p2p/transport-v1.md} §2), and pre-authentication work stays bounded.</li>
 *   <li>{@link #of(InetAddress)} - exactly one local interface address, for a caller that wants to keep the
 *       listener off, say, a VPN or a public interface.</li>
 *   <li>{@link #loopbackOnly()} - same-machine peers only (tests, local tooling). Nothing on the network
 *       can connect.</li>
 * </ul>
 */
public final class ListenerBindAddress {
    private static final ListenerBindAddress WILDCARD = new ListenerBindAddress(null);

    /** {@code null} means the wildcard address. */
    private final InetAddress address;

    private ListenerBindAddress(InetAddress address) {
        this.address = address;
    }

    public static ListenerBindAddress wildcard() {
        return WILDCARD;
    }

    public static ListenerBindAddress loopbackOnly() {
        return new ListenerBindAddress(InetAddress.getLoopbackAddress());
    }

    /** @throws IllegalArgumentException a multicast address, which can never be a TCP listen address */
    public static ListenerBindAddress of(InetAddress address) {
        Objects.requireNonNull(address, "address");
        if (address.isAnyLocalAddress()) {
            return WILDCARD;
        }
        if (address.isMulticastAddress()) {
            throw new IllegalArgumentException("A multicast address cannot be a listen address: " + address);
        }
        return new ListenerBindAddress(address);
    }

    public boolean isWildcard() {
        return address == null;
    }

    public boolean isLoopbackOnly() {
        return address != null && address.isLoopbackAddress();
    }

    /** The specific interface address, or empty for the wildcard. */
    public Optional<InetAddress> specificAddress() {
        return Optional.ofNullable(address);
    }

    /** True when a connection to {@code candidate} (one of this machine's own addresses) would reach the listener. */
    public boolean covers(InetAddress candidate) {
        return address == null || address.equals(candidate);
    }

    InetSocketAddress toSocketAddress(int port) {
        return address == null ? new InetSocketAddress(port) : new InetSocketAddress(address, port);
    }

    @Override
    public String toString() {
        return address == null ? "wildcard" : address.getHostAddress();
    }
}
