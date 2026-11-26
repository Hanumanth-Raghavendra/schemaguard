package io.github.hanumanthraghavendra.schemaguard.api.config;

import io.github.hanumanthraghavendra.schemaguard.api.ratelimit.RateLimitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses request bodies above {@code schemaguard.limits.max-body-bytes} with 413, before any
 * of the body is parsed. A body sent without a length (chunked) is read up to the limit and
 * no further.
 */
@Component
@Order(RateLimitFilter.ORDER + 1)
public class BodySizeFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public BodySizeFilter(ApiProperties properties) {
        this.maxBytes = properties.limits().maxBodyBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            reject(response);
            return;
        }
        if (declared >= 0 || !"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = readAtMost(request.getInputStream(), maxBytes + 1);
        if (body.length > maxBytes) {
            reject(response);
            return;
        }
        chain.doFilter(new CachedBody(request, body), response);
    }

    private static byte[] readAtMost(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while (out.size() < limit && (read = in.read(buffer, 0, (int) Math.min(buffer.length, limit - out.size()))) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter()
                .write(
                        "{\"type\":\"about:blank\",\"title\":\"Content Too Large\",\"status\":413,"
                                + "\"detail\":\"The request body is larger than " + maxBytes + " bytes.\",\"code\":\"SG-API-413\"}");
    }

    /** The request with its body already read into memory. */
    private static final class CachedBody extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    return in.read(bytes, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Asynchronous reading is not supported");
                }
            };
        }
    }
}
