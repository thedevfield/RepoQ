package dev.devfield.repoq.interview.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class OllamaFirstQuestionGeneratorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private final FirstQuestionGenerator.Input input = new FirstQuestionGenerator.Input("JAVA", UUID.randomUUID(),
            "Declares a class with a quoted \"name\".");
    private HttpServer server;

    @BeforeEach void bindLoopback() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach void stopLoopback() { server.stop(0); }

    @Test void requestsOnlyPinnedFactsAndExtractsCompletedStructuredContent() throws Exception {
        var sent = new AtomicReference<JsonNode>();
        var authorization = new AtomicReference<String>();
        var method = new AtomicReference<String>();
        String content = content(input.evidenceId());
        server.createContext("/api/chat", exchange -> {
            try (exchange) {
                sent.set(JSON.readTree(exchange.getRequestBody()));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                method.set(exchange.getRequestMethod());
                byte[] result = envelope(content, true, "stop");
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, result.length);
                exchange.getResponseBody().write(result);
            }
        });
        server.start();

        assertEquals(content, generator(TIMEOUT).generate(input));
        assertEquals("POST", method.get());
        assertNull(authorization.get());
        var request = sent.get();
        assertEquals("qwen3.5:4b", request.path("model").asText());
        assertFalse(request.path("stream").booleanValue());
        assertFalse(request.path("think").booleanValue());
        var format = request.path("format");
        assertTrue(format.has("additionalProperties"));
        assertFalse(format.path("additionalProperties").booleanValue());
        assertEquals(input.evidenceId().toString(), format.at("/properties/evidenceId/enum/0").asText());
        assertEquals(2, format.path("required").size());
        assertEquals(JSON.valueToTree(Map.of("topicKey", input.topicKey(), "evidenceId", input.evidenceId().toString(),
                        "codeFact", input.evidenceSummary())),
                JSON.readTree(request.at("/messages/1/content").asText()));
    }

    @ParameterizedTest @ValueSource(ints = {429, 500, 503})
    void transientStatusIsRetryable(int status) {
        respond(status, "text/plain", new byte[0]);
        assertThrows(FirstQuestionGenerator.Retryable.class, () -> generator(TIMEOUT).generate(input));
    }

    @ParameterizedTest @ValueSource(ints = {400, 404, 307})
    void rejectedStatusIsPermanentAndRedirectIsNotFollowed(int status) {
        server.createContext("/api/chat", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "/unexpected");
                exchange.sendResponseHeaders(status, -1);
            }
        });
        var redirected = new CountDownLatch(1);
        server.createContext("/unexpected", exchange -> {
            try (exchange) {
                redirected.countDown();
                exchange.sendResponseHeaders(503, -1);
            }
        });
        server.start();
        assertNull(generator(TIMEOUT).generate(input));
        assertEquals(1, redirected.getCount());
    }

    @ParameterizedTest @ValueSource(strings = {"unfinished", "length", "invalid-json", "oversized", "content-type"})
    void invalidEnvelopesArePermanent(String variant) throws Exception {
        byte[] body = switch (variant) {
            case "unfinished" -> envelope(content(input.evidenceId()), false, "stop");
            case "length" -> envelope(content(input.evidenceId()), true, "length");
            case "invalid-json" -> "{".getBytes(StandardCharsets.UTF_8);
            case "oversized" -> envelope("x".repeat(OllamaFirstQuestionGenerator.MAX_RESPONSE_BYTES), true, "stop");
            default -> envelope(content(input.evidenceId()), true, "stop");
        };
        respond(200, "content-type".equals(variant) ? "text/plain" : "application/json", body);
        assertNull(generator(TIMEOUT).generate(input));
    }

    @Test void deadlineCoversBodyAfterHeadersArrive() {
        var startedBody = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.createContext("/api/chat", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                startedBody.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        try {
            assertThrows(FirstQuestionGenerator.Retryable.class,
                    () -> generator(Duration.ofSeconds(1)).generate(input));
            assertEquals(0, startedBody.getCount());
        } finally {
            release.countDown();
        }
    }

    @Test void validatesTimeoutBelowWorkerLease() {
        assertThrows(IllegalArgumentException.class, () -> generator(Duration.ofSeconds(30)));
    }

    @Test void optInSelectsOneGeneratorAndRejectsAmbiguity() {
        var context = new ApplicationContextRunner()
                .withInitializer(app -> app.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(InterviewSessions.class, FakeFirstQuestionGenerator.class, OllamaFirstQuestionGenerator.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
        context.run(app -> assertTrue(app.getBeansOfType(FirstQuestionGenerator.class).isEmpty()));
        context.withPropertyValues("devfield.interview.sessions.enabled=true",
                "devfield.interview.first-question.ollama-enabled=true")
                .run(app -> assertInstanceOf(OllamaFirstQuestionGenerator.class, app.getBean(FirstQuestionGenerator.class)));
        context.withPropertyValues("devfield.interview.sessions.enabled=true",
                "devfield.interview.first-question.ollama-enabled=true", "devfield.interview.first-question.fake-enabled=true")
                .run(app -> assertInstanceOf(org.springframework.beans.factory.NoUniqueBeanDefinitionException.class,
                        org.springframework.core.NestedExceptionUtils.getMostSpecificCause(app.getStartupFailure())));
    }

    private void respond(int status, String contentType, byte[] body) {
        server.createContext("/api/chat", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length != 0) exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    private OllamaFirstQuestionGenerator generator(Duration timeout) {
        return new OllamaFirstQuestionGenerator(server.getAddress().getPort(), timeout);
    }

    static String content(UUID evidenceId) {
        return JSON.valueToTree(Map.of("question", "이 구현을 선택한 이유는 무엇인가요?", "evidenceId", evidenceId)).toString();
    }

    static byte[] envelope(String content, boolean done, String reason) {
        return JSON.valueToTree(Map.of("model", "qwen3.5:4b", "done", done, "done_reason", reason,
                "message", Map.of("role", "assistant", "content", content))).toString().getBytes(StandardCharsets.UTF_8);
    }
}
