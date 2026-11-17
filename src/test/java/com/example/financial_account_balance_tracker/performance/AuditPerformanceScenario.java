package com.example.financial_account_balance_tracker.performance;

import com.example.financial_account_balance_tracker.audit.AsyncAuditDispatcher;
import com.example.financial_account_balance_tracker.audit.JsonAuditSubmissionHandler;
import com.example.financial_account_balance_tracker.model.AuditBatch;
import com.example.financial_account_balance_tracker.model.AuditBatchingResult;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.model.Transaction;
import com.example.financial_account_balance_tracker.service.impl.OptimizingAuditBatchingService;
import com.example.financial_account_balance_tracker.service.impl.BestFitDecreasingAuditBatchingService;
import com.example.financial_account_balance_tracker.service.impl.InMemoryBankAccountService;
import com.example.financial_account_balance_tracker.service.impl.InMemoryTransactionAuditService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in timing demonstration: run with -Dtest=AuditPerformanceScenario.
 * The name intentionally does not match the default unit-test naming pattern.
 * There are no latency thresholds; correctness checks run outside timed regions.
 */
class AuditPerformanceScenario {

    private static final long SEED = 62831;
    private static final long CAPACITY = AuditBatch.MAX_ABSOLUTE_VALUE.movePointRight(2).longValueExact();
    private final OptimizingAuditBatchingService service = new OptimizingAuditBatchingService();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void measurePackingAndAccountToJsonPipeline() throws Exception {
        int warmups = Integer.parseInt(System.getProperty("tracker.performance.warmups", "3"));
        int iterations = Integer.parseInt(System.getProperty("tracker.performance.iterations", "7"));
        if (warmups < 0 || iterations < 1) {
            throw new IllegalArgumentException("Warmups must be nonnegative and iterations must be positive");
        }
        StringBuilder report = new StringBuilder("scenario,transactions,pack_p50_ms,pack_p95_ms,pipeline_p50_ms,"
                + "pipeline_p95_ms,batches,lower_bound,excess_over_bound,optimality_proven,fill_percent,"
                + "initial_bfd_batches,batches_saved,arrival_next_fit_batches,json_bytes\n");
        for (String scenario : List.of("uniform", "minimum_amount", "maximum_amount", "repair_case")) {
            for (int size : new int[] {1000, 100000}) {
                AuditSubmission input = input(scenario, size);
                for (int warmup = 0; warmup < warmups; warmup++) {
                    validatePacking(input, service.pack(input));
                    pipeline(input);
                }
                long[] packingTimes = new long[iterations];
                long[] pipelineTimes = new long[iterations];
                AuditBatchingResult result = null;
                int jsonBytes = 0;
                for (int iteration = 0; iteration < iterations; iteration++) {
                    long started = System.nanoTime();
                    result = service.pack(input);
                    packingTimes[iteration] = System.nanoTime() - started;
                    validatePacking(input, result);
                    PipelineSample sample = pipeline(input);
                    pipelineTimes[iteration] = sample.elapsedNanos();
                    jsonBytes = sample.jsonBytes();
                }
                long totalPence = totalPence(input);
                double fillPercent = 100.0 * totalPence / (result.batches().size() * (double) CAPACITY);
                int initialBatches = new BestFitDecreasingAuditBatchingService().pack(input).batches().size();
                report.append(String.format(Locale.ROOT, "%s,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%s,%.3f,%d,%d,%d,%d%n",
                        scenario, size, percentileMillis(packingTimes, 0.50), percentileMillis(packingTimes, 0.95),
                        percentileMillis(pipelineTimes, 0.50), percentileMillis(pipelineTimes, 0.95),
                        result.batches().size(), result.batchCountLowerBound(),
                        result.batches().size() - result.batchCountLowerBound(), result.optimalityProven(),
                        fillPercent, initialBatches, initialBatches - result.batches().size(), arrivalNextFitBatchCount(input), jsonBytes));
            }
        }
        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("audit-performance.csv"), report, StandardCharsets.UTF_8);
        Files.writeString(target.resolve("audit-performance-environment.txt"), String.format(Locale.ROOT,
                "java.version=%s%nos.name=%s%nos.arch=%s%navailable.processors=%d%nmax.heap.mib=%d%n"
                        + "seed=%d%nwarmups.per.case=%d%nmeasured.iterations.per.case=%d%n",
                System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() / (1024 * 1024),
                SEED, warmups, iterations), StandardCharsets.UTF_8);
        System.out.print(report);
    }

    private PipelineSample pipeline(AuditSubmission input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            JsonAuditSubmissionHandler handler = new JsonAuditSubmissionHandler(service, jsonMapper, output);
            CompletableFuture<Void> completed = new CompletableFuture<>();
            AsyncAuditDispatcher dispatcher = new AsyncAuditDispatcher(input.transactions().size(), 1, submission -> {
                try {
                    handler.handle(submission);
                    completed.complete(null);
                } catch (RuntimeException exception) {
                    completed.completeExceptionally(exception);
                    throw exception;
                }
            });
            InMemoryTransactionAuditService collector = new InMemoryTransactionAuditService(
                    input.transactions().size(), dispatcher::submit);
            InMemoryBankAccountService account = new InMemoryBankAccountService(collector);
            dispatcher.start();
            try {
                long started = System.nanoTime();
                for (Transaction transaction : input.transactions()) {
                    account.processTransaction(transaction);
                }
                completed.get(10, TimeUnit.SECONDS);
                long elapsed = System.nanoTime() - started;

                // Parsing, assertions, expected-value calculations and shutdown are not timed.
                var batches = jsonMapper.readTree(bytes.toByteArray()).at("/submission/batches");
                int transactions = 0;
                BigDecimal total = BigDecimal.ZERO;
                for (var batch : batches) {
                    transactions += batch.get("countOfTransactions").intValue();
                    BigDecimal amount = new BigDecimal(batch.get("totalValueOfAllTransactions").asString());
                    assertThat(amount).isPositive().isLessThanOrEqualTo(AuditBatch.MAX_ABSOLUTE_VALUE);
                    total = total.add(amount);
                }
                assertThat(transactions).isEqualTo(input.transactions().size());
                assertThat(total).isEqualByComparingTo(BigDecimal.valueOf(totalPence(input), 2));
                assertThat(account.retrieveExactBalance()).isEqualByComparingTo(input.transactions().stream()
                        .map(Transaction::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
                assertThat(collector.pendingTransactionCount()).isZero();
                assertThat(dispatcher.failedSubmission()).isEmpty();
                return new PipelineSample(elapsed, bytes.size());
            } finally {
                dispatcher.stop();
            }
        }
    }

    private static void validatePacking(AuditSubmission input, AuditBatchingResult result) {
        HashMap<Transaction, Integer> remaining = new HashMap<>();
        input.transactions().forEach(transaction -> remaining.merge(transaction, 1, Integer::sum));
        long packedPence = 0;
        for (AuditBatch batch : result.batches()) {
            assertThat(batch.totalValueOfAllTransactions()).isPositive().isLessThanOrEqualTo(AuditBatch.MAX_ABSOLUTE_VALUE);
            packedPence += batch.totalValueOfAllTransactions().movePointRight(2).longValueExact();
            for (Transaction transaction : batch.transactions()) {
                Integer count = remaining.get(transaction);
                assertThat(count).as("Transaction occurrence exists in the input").isNotNull().isPositive();
                if (count == 1) {
                    remaining.remove(transaction);
                } else {
                    remaining.put(transaction, count - 1);
                }
            }
        }
        assertThat(remaining).isEmpty();
        assertThat(packedPence).isEqualTo(totalPence(input));
    }

    private static AuditSubmission input(String scenario, int size) {
        SplittableRandom random = new SplittableRandom(SEED);
        List<Transaction> transactions = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long pennies = switch (scenario) {
                case "uniform" -> random.nextLong(20000, 50000001);
                case "minimum_amount" -> 20000;
                case "maximum_amount" -> 50000000;
                case "repair_case" -> i < size - 6 ? 50000000
                        : new long[] {44000000, 25000000, 20000000, 39000000, 34000000, 27000000}[i - (size - 6)];
                default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
            };
            transactions.add(new Transaction(new UUID(0, i), BigDecimal.valueOf(i % 2 == 0 ? pennies : -pennies, 2)));
        }
        return new AuditSubmission(1, transactions);
    }

    private static long totalPence(AuditSubmission submission) {
        return submission.transactions().stream()
                .mapToLong(transaction -> transaction.amount().abs().movePointRight(2).longValueExact()).sum();
    }

    /** A simple arrival-order baseline; this is not an exact minimum. */
    private static int arrivalNextFitBatchCount(AuditSubmission input) {
        int batches = 1;
        long load = 0;
        for (Transaction transaction : input.transactions()) {
            long pennies = transaction.amount().abs().movePointRight(2).longValueExact();
            if (load + pennies > CAPACITY) {
                batches++;
                load = 0;
            }
            load += pennies;
        }
        return batches;
    }

    private static double percentileMillis(long[] values, double percentile) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(sorted.length * percentile) - 1;
        return sorted[index] / 1000000.0;
    }

    private record PipelineSample(long elapsedNanos, int jsonBytes) {
    }
}
