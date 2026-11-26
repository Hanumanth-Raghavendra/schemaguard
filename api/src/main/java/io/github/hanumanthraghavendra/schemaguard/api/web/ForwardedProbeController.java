package io.github.hanumanthraghavendra.schemaguard.api.web;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEMPORARY (Phase 9.1, removed in the same slice): shows how a caller's address reaches the API
 * through Render, and through Vercel's rewrite, so rate limits can be keyed on an address that
 * cannot be spoofed. It answers with the caller's own forwarding headers; of every other header it
 * gives only the name.
 */
@Hidden
@RestController
class ForwardedProbeController {

    private static final Set<String> ADDRESS_HEADERS = Set.of(
            "x-forwarded-for", "x-real-ip", "forwarded", "cf-connecting-ip", "true-client-ip",
            "x-vercel-forwarded-for", "x-vercel-proxied-for", "x-client-ip", "fastly-client-ip");

    @GetMapping("/api/v1/probe/forwarded")
    Map<String, Object> forwarded(HttpServletRequest request) {
        Map<String, List<String>> addresses = new TreeMap<>();
        Set<String> names = new TreeSet<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            names.add(lower);
            if (ADDRESS_HEADERS.contains(lower)) {
                addresses.put(lower, Collections.list(request.getHeaders(name)));
            }
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("remoteAddr", request.getRemoteAddr());
        answer.put("addressHeaders", addresses);
        answer.put("headerNames", names);
        return answer;
    }
}
