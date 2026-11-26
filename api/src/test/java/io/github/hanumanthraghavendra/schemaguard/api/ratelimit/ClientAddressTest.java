package io.github.hanumanthraghavendra.schemaguard.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The header shapes measured on the live service on 2026-11-26 (D-116), with documentation
 * addresses in place of the real ones.
 */
class ClientAddressTest {

    private static final String CLOUDFLARE_EDGE = "172.68.1.10";

    @Test
    void aDirectCallerIsTheLastForwardedForEntry() {
        assertThat(ClientAddress.of(CLOUDFLARE_EDGE, List.of("203.0.113.7"), null))
                .isEqualTo(new ClientAddress("203.0.113.7", "203.0.113.7"));
    }

    @Test
    void entriesTheCallerWroteThemselvesComeFirstAndAreIgnored() {
        assertThat(ClientAddress.of(CLOUDFLARE_EDGE, List.of("10.0.0.1, 198.51.100.4,203.0.113.7"), null))
                .isEqualTo(new ClientAddress("203.0.113.7", "203.0.113.7"));
        // The same header sent twice: the proxies' entry is still last.
        assertThat(ClientAddress.of(CLOUDFLARE_EDGE, List.of("10.0.0.1", "198.51.100.4, 203.0.113.7"), null))
                .isEqualTo(new ClientAddress("203.0.113.7", "203.0.113.7"));
    }

    @Test
    void anUnreadableLastEntryFallsBackToThePeerNotToTheCallersText() {
        assertThat(ClientAddress.of(CLOUDFLARE_EDGE, List.of("203.0.113.7, unknown"), null))
                .isEqualTo(new ClientAddress(CLOUDFLARE_EDGE, CLOUDFLARE_EDGE));
        assertThat(ClientAddress.of("127.0.0.1", List.of(), null)).isEqualTo(new ClientAddress("127.0.0.1", "127.0.0.1"));
    }

    @Test
    void theWebAppsVisitorIsNamedByVercel() {
        // Through the rewrite: the visitor, then Vercel's own address, which changes per request.
        assertThat(ClientAddress.of(CLOUDFLARE_EDGE, List.of("198.51.100.23,3.80.12.34"), "198.51.100.23"))
                .isEqualTo(new ClientAddress("198.51.100.23", "3.80.12.34"));
    }

    @Test
    void aMadeUpVercelHeaderNamesAVisitorButNotTheConnectingAddress() {
        assertThat(ClientAddress.of(CLOUDFLARE_EDGE, List.of("203.0.113.7"), "192.0.2.99"))
                .isEqualTo(new ClientAddress("192.0.2.99", "203.0.113.7"));
    }

    @Test
    void aVercelHeaderThatIsNotOneAddressIsIgnored() {
        for (String header : new String[] {"", "unknown", "192.0.2.1, 192.0.2.2", "192.0.2.256", "deadbeef", "1:2:3", "x".repeat(80)}) {
            assertThat(ClientAddress.of(CLOUDFLARE_EDGE, List.of("203.0.113.7"), header))
                    .as(header)
                    .isEqualTo(new ClientAddress("203.0.113.7", "203.0.113.7"));
        }
    }

    @Test
    void readsTheRequestsHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/diff");
        request.setRemoteAddr(CLOUDFLARE_EDGE);
        request.addHeader("X-Forwarded-For", "10.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.23, 3.80.12.34");
        request.addHeader("X-Vercel-Forwarded-For", "198.51.100.23");
        assertThat(ClientAddress.of(request)).isEqualTo(new ClientAddress("198.51.100.23", "3.80.12.34"));
    }

    @Test
    void ipv4IsWrittenCanonically() {
        assertThat(ClientAddress.key("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(ClientAddress.key(" 203.000.113.07 ")).isEqualTo("203.0.113.7");
        assertThat(ClientAddress.key("203.0.113.7:51234")).isEqualTo("203.0.113.7");
        assertThat(ClientAddress.key("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(ClientAddress.key("::FFFF:cb00:7107")).isEqualTo("203.0.113.7");
    }

    @Test
    void ipv6CountsPerSlash64() {
        assertThat(ClientAddress.key("2001:db8:85a3:42::8a2e:370:7334")).isEqualTo("2001:db8:85a3:42::/64");
        assertThat(ClientAddress.key("2001:0DB8:85A3:0042:ffff:ffff:ffff:ffff")).isEqualTo("2001:db8:85a3:42::/64");
        assertThat(ClientAddress.key("[2001:db8::1]:443")).isEqualTo("2001:db8:0:0::/64");
        assertThat(ClientAddress.key("fe80::1%eth0")).isEqualTo("fe80:0:0:0::/64");
        assertThat(ClientAddress.key("::1")).isEqualTo("0:0:0:0::/64");
        assertThat(ClientAddress.key("::")).isEqualTo("0:0:0:0::/64");
        assertThat(ClientAddress.key("1:2:3:4:5:6:7::")).isEqualTo("1:2:3:4::/64");
        assertThat(ClientAddress.key("64:ff9b::192.0.2.33")).isEqualTo("64:ff9b:0:0::/64");
    }

    @Test
    void anythingElseIsNotAnAddress() {
        String[] notAddresses = {
            null, "", " ", "localhost", "example.com", "1.2.3", "1.2.3.4.5", "1.2.3.-4", "1.2.3.1000", "1..2.3",
            "1:2:3:4:5:6:7:8:9", "1::2::3", ":::", ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:", "12345::", "g::1",
            "[2001:db8::1", "::1.2.3", "::1.2.3.256", "1.2.3.4::", "١٢٣.0.0.1", "٣::1", "1:2:3:4:5:6:7:8::",
            "1:2:3:4:5:6::1.2.3.4:5"
        };
        for (String text : notAddresses) {
            assertThat(ClientAddress.key(text)).as(text == null ? "null" : text).isNull();
        }
        assertThat(ClientAddress.key("1:2:3:4:5:6:1.2.3.4")).isEqualTo("1:2:3:4::/64");
    }
}
