package com.uptimecrew.multistate.llmproxy.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class CostMiddlewareTest {

    private static final Instant FIXED_AT =
            Instant.parse("2026-07-10T12:00:00Z");
    private static final BigDecimal PRICE_PER_K =
            new BigDecimal("0.00300000");

    private final List<Long> redisIncrements = new ArrayList<>();
    private final RedisCostStore store = (tenant, day, costUsdE5) ->
            redisIncrements.add(costUsdE5);

    private ByteArrayOutputStream stdoutCapture;
    private PrintStream originalOut;

    @BeforeEach
    void captureStdout() {
        stdoutCapture = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(stdoutCapture, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    @Test
    void oneKInputZeroOutput() {
        CostMiddleware middleware = middleware(PriceBook.defaults());
        CallContext ctx = new CallContext(FIXED_AT, "tenant-a");
        UpstreamResponse resp = new UpstreamResponse(
                PriceBook.CLAUDE_35_SONNET, 1_000, 0, 42, true);

        middleware.observe(ctx, resp);

        assertThat(redisIncrements).containsExactly(300L);
        assertEmfContains("tenant-a", PriceBook.CLAUDE_35_SONNET, 300L);
    }

    @Test
    void zeroInputOneKOutput() {
        CostMiddleware middleware = middleware(PriceBook.defaults());
        CallContext ctx = new CallContext(FIXED_AT, "tenant-b");
        UpstreamResponse resp = new UpstreamResponse(
                PriceBook.CLAUDE_35_SONNET, 0, 1_000, 55, true);

        middleware.observe(ctx, resp);

        assertThat(redisIncrements).containsExactly(300L);
        assertEmfContains("tenant-b", PriceBook.CLAUDE_35_SONNET, 300L);
    }

    @Test
    void overflowThrowsOnLongValueExact() {
        PriceBook huge = new PriceBook(Map.of("runaway", new BigDecimal("1000000")));
        CostMiddleware middleware = middleware(huge);
        CallContext ctx = new CallContext(FIXED_AT, "tenant-overflow");
        UpstreamResponse resp = new UpstreamResponse(
                "runaway", Long.MAX_VALUE, 0, 1, true);

        assertThrows(ArithmeticException.class, () -> middleware.observe(ctx, resp));
        assertThat(redisIncrements).isEmpty();
        assertThat(stdoutCapture.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void emfLineIncludesServiceTenantFeatureAndCostUsdE5() {
        CostMiddleware middleware = middleware(PriceBook.defaults());
        middleware.observe(
                new CallContext(FIXED_AT, "tenant-synth"),
                new UpstreamResponse(PriceBook.CLAUDE_35_SONNET, 2_000, 0, 120, true));

        String line = stdoutCapture.toString(StandardCharsets.UTF_8).trim();
        assertThat(line).contains("\"service\":\"multistate\"");
        assertThat(line).contains("\"tenant\":\"tenant-synth\"");
        assertThat(line).contains("\"feature\":\"summarize-nexus\"");
        assertThat(line).contains("\"modelId\":\"" + PriceBook.CLAUDE_35_SONNET + "\"");
        assertThat(line).contains("\"CostUsdE5\":600");
        assertThat(line).contains("\"CostUsd\":0.006");
        assertThat(line).contains("\"Namespace\":\"acme/llmproxy\"");
    }

    private CostMiddleware middleware(PriceBook priceBook) {
        return new CostMiddleware(priceBook, store, new EmfEmitter());
    }

    private void assertEmfContains(String tenant, String modelId, long costUsdE5) {
        String line = stdoutCapture.toString(StandardCharsets.UTF_8).trim();
        assertThat(line).contains("\"tenant\":\"" + tenant + "\"");
        assertThat(line).contains("\"modelId\":\"" + modelId + "\"");
        assertThat(line).contains("\"CostUsdE5\":" + costUsdE5);
        double costUsd = costUsdE5 / 100_000.0;
        assertThat(line).contains("\"CostUsd\":" + costUsd);
    }
}
