package com.fittrack.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 9: the Coach timeout must be real, and must not be the scanner's.
 *
 * <p>The defect this pins is a timeout that was configured but never reached the socket: the Coach
 * shared the scanner's 45 second client, so {@code ai-coach-timeout-ms} existed only in a field while
 * the call still blocked for the full 45 seconds. A mocked "already-thrown timeout" would not have
 * caught that, so these tests point the provider at a real socket that accepts a connection and then
 * never answers. The only way the call can return is for the transport itself to give up.
 *
 * <p>Wall-clock time is kept reasonable by configuring small budgets. What is proven is that the
 * configured number is the number that governs the call.
 */
class OpenAiProviderTimeoutTest {

    /**
     * A server that accepts connections and never answers.
     *
     * <p>Accepted-but-silent connections are what a hung provider looks like from the client side,
     * and they are the only honest way to exercise a read timeout.
     */
    private static final class SilentServer implements AutoCloseable {
        private final ServerSocket socket;
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private final List<Socket> held = Collections.synchronizedList(new ArrayList<>());

        SilentServer() throws IOException {
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            pool.submit(() -> {
                while (!socket.isClosed()) {
                    try {
                        held.add(socket.accept());
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        String baseUrl() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        @Override
        public void close() {
            try { socket.close(); } catch (IOException ignored) { }
            synchronized (held) {
                for (Socket s : held) {
                    try { s.close(); } catch (IOException ignored) { }
                }
            }
            pool.shutdownNow();
        }
    }

    /** A server that answers immediately with a fixed JSON body. */
    private static final class StubServer implements AutoCloseable {
        private final ServerSocket socket;
        private final ExecutorService pool = Executors.newSingleThreadExecutor();
        private final String body;

        StubServer(String body) throws IOException {
            this.body = body;
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            pool.submit(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        // Read the request head, bounded, before replying. An unbounded drain would
                        // block waiting for bytes the client will never send, which is a hang rather
                        // than a fast healthy response.
                        client.setSoTimeout(2_000);
                        InputStream in = client.getInputStream();
                        byte[] scratch = new byte[8192];
                        int first = in.read(scratch);
                        // One extra short read is enough to consume the body of a small request; the
                        // socket timeout bounds it if the client is still mid-write.
                        if (first > 0 && first < scratch.length) in.read(scratch);
                        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                        OutputStream out = client.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                + payload.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        out.write(payload);
                        out.flush();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        String baseUrl() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        @Override
        public void close() {
            try { socket.close(); } catch (IOException ignored) { }
            pool.shutdownNow();
        }
    }

    private static final List<AiProvider.CoachMessage> MESSAGES =
            List.of(new AiProvider.CoachMessage("system", "instructions"));

    /** Drives one real blocking call and returns how long the transport took to abandon it. */
    private static long timeUntilTimeout(String baseUrl, long budgetMs) {
        OpenAiProvider provider = OpenAiProvider.forBaseUrl(baseUrl, budgetMs);
        long start = System.nanoTime();
        try {
            provider.analyzeCoachMessages(MESSAGES);
        } catch (AiProviderException expected) {
            assertThat(expected.category())
                    .as("a blocked call must be categorised as a timeout").isEqualTo("timeout");
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    // ------------------------------------------------------------- Coach

    @Test
    @DisplayName("Phase 9 - a Coach call against a silent provider is abandoned at the configured budget")
    void coachCallIsBoundedByTheConfiguredTimeout() throws Exception {
        try (SilentServer server = new SilentServer()) {
            OpenAiProvider provider = OpenAiProvider.forBaseUrl(server.baseUrl(), 700);

            long start = System.nanoTime();
            assertThatThrownBy(() -> provider.analyzeCoachMessages(MESSAGES))
                    .isInstanceOf(AiProviderException.class)
                    .satisfies(e -> assertThat(((AiProviderException) e).category())
                            .as("a blocked call must be categorised as a timeout").isEqualTo("timeout"));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // Released near the configured budget, not by the scanner's 45 seconds, and not by an
            // elapsed-time check after the fact: only the socket can release it this early.
            assertThat(elapsedMs)
                    .as("the call must be released near the configured 700ms budget")
                    .isLessThan(20_000L);
            assertThat(elapsedMs)
                    .as("and must not return instantly, which would mean no timeout was applied at all")
                    .isGreaterThan(200L);
        }
    }

    @Test
    @DisplayName("Phase 9 - a shorter configured budget is released proportionally earlier")
    void shorterBudgetReturnsEarlier() throws Exception {
        try (SilentServer server = new SilentServer()) {
            long fast = timeUntilTimeout(server.baseUrl(), 300);
            long slow = timeUntilTimeout(server.baseUrl(), 2_500);

            // This is the test that proves the configuration controls the operation: the two calls
            // differ only in the configured value, so the difference in outcome can only come from
            // the number the provider was given.
            assertThat(fast)
                    .as("a 300ms budget must release clearly before a 2500ms one")
                    .isLessThan(slow);
            assertThat(slow).as("the larger budget must still bound the call").isLessThan(20_000L);
        }
    }

    @Test
    @DisplayName("Phase 9 - the configured Coach budget is the value actually applied")
    void configuredTimeoutIsRetained() {
        assertThat(OpenAiProvider.forBaseUrl("http://127.0.0.1:1", 20_000).coachTimeout())
                .as("the production default of 20s must reach the provider")
                .isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("Phase 9 - a non-positive configured budget is clamped rather than disabling the timeout")
    void nonPositiveBudgetIsClamped() throws Exception {
        // A zero read timeout would otherwise mean "wait forever", which is the exact failure this
        // class exists to prevent, so the value is floored before it reaches the socket.
        try (SilentServer server = new SilentServer()) {
            assertThat(OpenAiProvider.forBaseUrl(server.baseUrl(), 0).coachTimeout())
                    .as("a zero budget must be clamped to a real millisecond value")
                    .isEqualTo(Duration.ofMillis(1));
            assertThat(timeUntilTimeout(server.baseUrl(), 0))
                    .as("even a zero budget must still terminate the call")
                    .isLessThan(20_000L);
        }
    }

    @Test
    @DisplayName("Phase 9 - a healthy provider still returns normally under the shorter budget")
    void fastProviderIsUnaffected() throws Exception {
        try (StubServer server = new StubServer(
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"summary\\\":\\\"ok\\\"}\"}}]}")) {
            AiProvider.CoachAnalysis result =
                    OpenAiProvider.forBaseUrl(server.baseUrl(), 20_000).analyzeCoachMessages(MESSAGES);
            assertThat(result.text()).contains("summary");
            assertThat(result.model()).as("the text model is still used").isEqualTo("text-model");
        }
    }

    // ----------------------------------------------------------- scanner

    @Test
    @DisplayName("Phase 9 - the food scanner keeps its long timeout and does not inherit the Coach budget")
    void foodScannerKeepsItsLongTimeout() {
        // Asserted against the two live request factories rather than by waiting out a 45 second
        // call. Probing the factory the provider actually uses is both faster and more precise: it
        // shows the scanner budget and the Coach budget are set independently on the sockets, which
        // is exactly the property that regressed.
        OpenAiProvider provider = OpenAiProvider.forBaseUrl("http://127.0.0.1:1", 300);

        assertThat(scannerReadTimeoutMillis(provider))
                .as("the scanner must keep its original 45 second read timeout")
                .isEqualTo(45_000);
        assertThat(coachReadTimeoutMillis(provider))
                .as("the Coach must carry the configured 300ms budget on its own client")
                .isEqualTo(300);
    }

    @Test
    @DisplayName("Phase 9 - a 20 second Coach budget and the scanner's 45 seconds coexist")
    void productionDefaultsCoexist() {
        OpenAiProvider provider = OpenAiProvider.forBaseUrl("http://127.0.0.1:1", 20_000);

        assertThat(coachReadTimeoutMillis(provider)).isEqualTo(20_000);
        assertThat(scannerReadTimeoutMillis(provider))
                .as("narrowing the Coach budget must not have narrowed the scanner")
                .isEqualTo(45_000);
    }

    /** Reads the read timeout off the provider's live scanner request factory. */
    private static int scannerReadTimeoutMillis(OpenAiProvider provider) {
        return readTimeoutOf(clientField(provider, "client"));
    }

    /** Reads the read timeout off the provider's live Coach request factory. */
    private static int coachReadTimeoutMillis(OpenAiProvider provider) {
        return readTimeoutOf(clientField(provider, "coachClient"));
    }

    private static RestClient clientField(OpenAiProvider provider, String name) {
        try {
            java.lang.reflect.Field field = OpenAiProvider.class.getDeclaredField(name);
            field.setAccessible(true);
            return (RestClient) field.get(provider);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot inspect the " + name + " client", e);
        }
    }

    private static int readTimeoutOf(RestClient client) {
        // RestClient extends DefaultRestClient, which holds the request factory. The field names
        // are Spring implementation details, so a rename fails loudly here rather than silently
        // making the assertion vacuous.
        Object factory = declaredField(client, "clientRequestFactory");
        Object value = declaredField(factory, "readTimeout");
        if (value instanceof Duration d) return (int) d.toMillis();
        return (Integer) value;
    }

    /**
     * Reads a declared field, walking up the hierarchy.
     *
     * <p>The walk is needed because the field is declared on the superclass, not on the type the
     * reference points at, and a plain {@code getDeclaredField} would miss it.
     */
    private static Object declaredField(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                // Declared further up; keep walking.
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot read the " + name + " field on " + type.getSimpleName(), e);
            }
        }
        throw new AssertionError("no field named " + name + " on " + target.getClass().getName());
    }
}
