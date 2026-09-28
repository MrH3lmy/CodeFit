package com.codefit.peer.transport;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * An IP-literal {@code host:port} pair. Never a hostname: ADR-0001 §4 and the runtime dependency
 * inventory are explicit that the peer features perform <strong>no DNS lookups</strong>, so a hostname
 * string is rejected before anything ever calls {@link InetAddress#getByName}. The regex below matches
 * only strings that already look numeric; {@code InetAddress.getByName} is then used purely as a local,
 * no-I/O parser/normalizer for a value that has already been proven not to need resolution — the same
 * guarantee the JDK documents for a "literal IP address" argument.
 */
public record PeerAddress(String host, int port) {
    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}$");
    /** Deliberately permissive (hex groups, "::", and letters); final validity is settled by parsing. */
    private static final Pattern IPV6_CANDIDATE = Pattern.compile("^\\[?[0-9A-Fa-f:.]+]?$");

    public PeerAddress {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host must not be blank.");
        }
        String candidate = stripBrackets(host);
        if (!isIpLiteral(candidate)) {
            throw new IllegalArgumentException("Only IP-literal addresses are accepted (no hostnames/DNS): " + host);
        }
        host = candidate;
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be 1..65535, got " + port);
        }
    }

    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /** True only for a syntactically numeric IPv4 or IPv6 literal; never triggers name resolution. */
    public static boolean isIpLiteral(String candidate) {
        if (IPV4.matcher(candidate).matches()) {
            return true;
        }
        if (!candidate.contains(":") || !IPV6_CANDIDATE.matcher(candidate).matches()) {
            return false;
        }
        try {
            InetAddress parsed = InetAddress.getByName(candidate);
            return parsed instanceof Inet6Address;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** Resolves the literal to an {@link InetAddress}; never performs network I/O for a validated literal. */
    public InetAddress toInetAddress() {
        try {
            InetAddress resolved = InetAddress.getByName(host);
            if (!(resolved instanceof Inet4Address) && !(resolved instanceof Inet6Address)) {
                throw new IllegalStateException("Unexpected address type for literal " + host);
            }
            return resolved;
        } catch (UnknownHostException e) {
            // Unreachable for an address that already passed the constructor's literal check.
            throw new IllegalStateException("IP literal failed to parse: " + host, e);
        }
    }

    @Override
    public String toString() {
        return host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
    }
}
