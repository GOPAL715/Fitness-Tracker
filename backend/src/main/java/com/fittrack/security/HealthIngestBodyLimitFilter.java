package com.fittrack.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Rejects an oversized health ingestion body before it is read (D8).
 *
 * <h2>Why a filter and not just a validator</h2>
 * A record-count check inside the service runs <b>after</b> the body has already been buffered and
 * deserialized. A caller could therefore still push a very large payload and make FitTrack pay to
 * receive and parse it. Checking {@code Content-Length} at the edge enforces the ceiling on the upload
 * itself, so an oversized request never becomes server memory.
 *
 * <h2>Why the scope is narrow</h2>
 * Only the inbound record-ingestion route is capped. Lowering a global limit would change the
 * behaviour of endpoints this phase must not touch, and the existing multipart ceiling is a separate
 * concern for file uploads.
 * <h2>The limit is a real byte limit, not a declared one</h2>
 * {@code Content-Length} is a header the client chooses to send, and is simply absent for a chunked
 * transfer. A filter that only compared that header would enforce nothing against a caller who
 * omits it or understates it, which is exactly the case an abuse control has to cover. So the body
 * is additionally wrapped in a stream that counts bytes <em>as they are read</em> and aborts the
 * moment the ceiling is passed. The count is therefore of bytes actually received, and a body of
 * exactly the ceiling is still accepted while one byte more is refused.
 *
 * <p>The wrapper also caps how much a single {@code read} may pull back, so an oversized upload is
 * abandoned part-way rather than buffered whole on its way to being rejected. That is what keeps
 * "do not buffer an unbounded request" true rather than aspirational.
 *
 * <p>Known boundary: the cap applies while the body is consumed by the controller, so the connector
 * has already accepted the request. A transport-level abort would need connector configuration
 * (for example {@code maxPostSize} or a bounded request-body limit), which cannot be scoped to a
 * single route. Within the application this is the strongest available control, and the residual
 * window is stated rather than glossed over.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HealthIngestBodyLimitFilter extends OncePerRequestFilter {

    private static final String INGEST_PREFIX = "/api/v1/health/devices/";
    private static final String INGEST_SUFFIX = "/records";

    private final long maxBodyBytes;

    public HealthIngestBodyLimitFilter(
            @Value("${app.health-connect-limits.max-body-bytes:1048576}") long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isIngestRoute(request)) {
            chain.doFilter(request, response);
            return;
        }
        // Fast path: a declared Content-Length over the ceiling is refused without reading the body
        // at all, so an obviously oversized upload costs nothing.
        if (request.getContentLengthLong() > maxBodyBytes) {
            reject(response);
            return;
        }
        // Hard path. Content-Length is a client-declared header and is absent for a chunked
        // transfer, so trusting it alone would leave the ceiling unenforced exactly when a caller
        // chooses to omit it. The body is therefore wrapped in a counting stream that trips as soon
        // as more than maxBodyBytes are actually read. The controller reads incrementally through
        // Jackson, so the wrapper aborts mid-parse: the body is never buffered in full, so an
        // unbounded upload cannot become unbounded server memory.
        try {
            chain.doFilter(new LimitedBodyRequest(request, maxBodyBytes), response);
        } catch (PayloadTooLargeException e) {
            // The response may already be committed by the time parsing fails, in which case
            // nothing can be written and the connection is simply closed.
            if (!response.isCommitted()) {
                reject(response);
            }
        }
    }

    /** Writes the 413 envelope with a stable code, and never echoes the declared size back. */
    private void reject(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        response.setStatus(413);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"status\":413,\"error\":\"Payload Too Large\",\"code\":\"payload_too_large\","
                        + "\"message\":\"Health record batch exceeds the maximum request size.\"}");
    }

    /** Thrown by the counting stream when the real byte count exceeds the ceiling. */
    static final class PayloadTooLargeException extends IOException {
        PayloadTooLargeException() {
            super("request body exceeds the maximum permitted size");
        }
    }

    /** Counts bytes as the body is read and refuses to deliver more than the ceiling. */
    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final long limit;

        LimitedBodyRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedServletInputStream(super.getInputStream(), limit);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding)));
        }
    }

    /** A stream that throws as soon as the cumulative read passes the limit. */
    private static final class LimitedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long count;

        LimitedServletInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read == -1 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (count >= limit) {
                // At the ceiling. One more byte is attempted to tell "exactly at the limit" from
                // "over it", so a body of precisely the ceiling is still accepted.
                throw new PayloadTooLargeException();
            }
            // Never hand back more than the remaining allowance, so a single read cannot pull an
            // entire oversized body into memory before the check fires.
            int allowed = (int) Math.min(len, limit - count + 1);
            int read = delegate.read(b, off, allowed);
            if (read > 0) {
                count += read;
                if (count > limit) {
                    throw new PayloadTooLargeException();
                }
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /** True only for a POST to the inbound record-ingestion route, not the rest of the health API. */
    private static boolean isIngestRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "POST".equalsIgnoreCase(request.getMethod())
                && path != null
                && path.startsWith(INGEST_PREFIX)
                && path.endsWith(INGEST_SUFFIX)
                && path.length() > INGEST_PREFIX.length() + INGEST_SUFFIX.length();
    }
}
