package io.github.hanumanthraghavendra.schemaguard.api.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Who sent a request, as far as the API can tell behind its proxies (D-116). On Render the
 * caller reaches the API through Cloudflare and Render's own proxy, which append the address
 * that connected to them as the last {@code X-Forwarded-For} entry; whatever the caller wrote
 * into that header comes before it. The web app's requests arrive through Vercel's rewrite,
 * which connects from its own changing addresses and names the browser in
 * {@code X-Vercel-Forwarded-For}, overwriting any value the browser sent. A caller who goes to
 * Render directly can still send that header made up, so it is never trusted alone: see
 * {@link RateLimiter}.
 *
 * <p>Both addresses are keys for counting, not for display: IPv4 addresses are written
 * canonically and IPv6 addresses are cut to their /64 network, the block one subscriber is
 * usually given, so a caller cannot get a fresh count from each address of their own block.
 *
 * @param visitor    whose limit a request counts against: the browser's address for a request
 *                   through the Vercel rewrite, otherwise the connecting address
 * @param connecting the address that connected to Render: Vercel's for the web app, otherwise
 *                   the caller's own; a caller cannot make it up
 */
public record ClientAddress(String visitor, String connecting) {

    static final String FORWARDED_FOR = "X-Forwarded-For";
    static final String VERCEL_FORWARDED_FOR = "X-Vercel-Forwarded-For";

    public static ClientAddress of(HttpServletRequest request) {
        return of(
                request.getRemoteAddr(),
                Collections.list(request.getHeaders(FORWARDED_FOR)),
                request.getHeader(VERCEL_FORWARDED_FOR));
    }

    /**
     * @param remoteAddr         the address of the TCP peer (a Cloudflare edge on Render)
     * @param forwardedFor       the {@code X-Forwarded-For} header lines, in order
     * @param vercelForwardedFor the {@code X-Vercel-Forwarded-For} header, if any
     */
    static ClientAddress of(String remoteAddr, List<String> forwardedFor, @Nullable String vercelForwardedFor) {
        String connecting = null;
        if (!forwardedFor.isEmpty()) {
            // Only the last entry was written by a proxy; an unreadable one is not skipped,
            // because the entry before it is the caller's own text.
            String last = forwardedFor.getLast();
            connecting = key(last.substring(last.lastIndexOf(',') + 1));
        }
        if (connecting == null) {
            String peer = key(remoteAddr);
            connecting = peer != null ? peer : remoteAddr;
        }
        String visitor = key(vercelForwardedFor);
        return new ClientAddress(visitor != null ? visitor : connecting, connecting);
    }

    /**
     * The counting key for one address: {@code 203.0.113.7}, or {@code 2001:db8:0:1::/64} for any
     * address in that IPv6 network. An IPv4 address written as IPv6 ({@code ::ffff:203.0.113.7})
     * counts as itself. A port, brackets and a zone are ignored. Null for anything that is not an
     * address, which is never looked up as a host name.
     */
    static @Nullable String key(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String address = text.strip();
        if (address.isEmpty() || address.length() > 64) {
            return null;
        }
        if (address.startsWith("[")) {
            int end = address.indexOf(']');
            if (end < 0) {
                return null;
            }
            address = address.substring(1, end);
        }
        int zone = address.indexOf('%');
        if (zone >= 0) {
            address = address.substring(0, zone);
        }
        int colon = address.indexOf(':');
        if (colon < 0 || colon == address.lastIndexOf(':')) {
            // IPv4, perhaps followed by a port.
            int[] octets = ipv4(colon < 0 ? address : address.substring(0, colon));
            return octets == null ? null : octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
        }
        int[] groups = ipv6(address);
        if (groups == null) {
            return null;
        }
        if (groups[0] == 0 && groups[1] == 0 && groups[2] == 0 && groups[3] == 0 && groups[4] == 0 && groups[5] == 0xffff) {
            return (groups[6] >> 8) + "." + (groups[6] & 0xff) + "." + (groups[7] >> 8) + "." + (groups[7] & 0xff);
        }
        return Integer.toHexString(groups[0]) + ":" + Integer.toHexString(groups[1]) + ":"
                + Integer.toHexString(groups[2]) + ":" + Integer.toHexString(groups[3]) + "::/64";
    }

    /** Four decimal numbers of at most three digits, each at most 255; otherwise null. */
    private static int @Nullable [] ipv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return null;
            }
            octets[i] = Integer.parseInt(part);
            if (octets[i] > 255) {
                return null;
            }
        }
        return octets;
    }

    /** Eight 16-bit groups (RFC 4291 2.2: one {@code ::}, an optional dotted IPv4 tail); otherwise null. */
    private static int @Nullable [] ipv6(String text) {
        String address = text;
        int lastColon = address.lastIndexOf(':');
        if (address.indexOf('.', lastColon) >= 0) {
            int[] octets = ipv4(address.substring(lastColon + 1));
            if (octets == null) {
                return null;
            }
            address = address.substring(0, lastColon + 1)
                    + Integer.toHexString(octets[0] << 8 | octets[1]) + ":" + Integer.toHexString(octets[2] << 8 | octets[3]);
        }
        int gap = address.indexOf("::");
        if (gap != address.lastIndexOf("::")) {
            return null;
        }
        int[] groups = new int[8];
        if (gap < 0) {
            int[] all = hexGroups(address);
            return all != null && all.length == 8 ? all : null;
        }
        int[] before = hexGroups(address.substring(0, gap));
        int[] after = hexGroups(address.substring(gap + 2));
        if (before == null || after == null || before.length + after.length > 7) {
            return null;
        }
        System.arraycopy(before, 0, groups, 0, before.length);
        System.arraycopy(after, 0, groups, 8 - after.length, after.length);
        return groups;
    }

    /** ASCII only: {@link Character#digit} would also take digits of other scripts. */
    private static boolean isHexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** Colon-separated groups of one to four hex digits; empty text is no groups. */
    private static int @Nullable [] hexGroups(String text) {
        if (text.isEmpty()) {
            return new int[0];
        }
        String[] parts = text.split(":", -1);
        int[] groups = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 4 || !part.chars().allMatch(ClientAddress::isHexDigit)) {
                return null;
            }
            groups[i] = Integer.parseInt(part, 16);
        }
        return groups;
    }
}
