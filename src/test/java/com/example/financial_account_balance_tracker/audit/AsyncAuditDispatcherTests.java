package com.example.financial_account_balance_tracker.audit;

import com.example.financial_account_balance_tracker.exception.AuditProcessingException;
import com.example.financial_account_balance_tracker.exception.AuditUnavailableException;
import com.example.financial_account_balance_tracker.exception.InvalidAuditSubmissionException;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.model.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncAuditDispatcherTests {

    private final List<AsyncAuditDispatcher> dispatchers = new ArrayList<>();

    @AfterEach
    void stopWorkers() {
        dispatchers.forEach(AsyncAuditDispatcher::stop);
    }

    @Test
    void processesWindowsInOrderOnADedicatedThreadAndDrainsOnShutdown() {
        List<AuditSubmission> received = new CopyOnWriteArrayList<>();
        Set<String> threadNames = ConcurrentHashMap.newKeySet();
        AsyncAuditDispatcher dispatcher = dispatcher(3, submission -> {
            threadNames.add(Thread.currentThread().getName());
            received.add(submission);
        });
        dispatcher.start();
        dispatcher.submit(submission(1));
        dispatcher.submit(submission(2));
        dispatcher.submit(submission(3));

        dispatcher.stop();

        assertEquals(List.of(submission(1), submission(2), submission(3)), received);
        assertEquals(Set.of("audit-dispatcher"), threadNames);
        assertFalse(dispatcher.isRunning());
        assertThrows(AuditUnavailableException.class, () -> dispatcher.submit(submission(4)));
    }

    @Test
    void rejectsAFullQueueWithoutWaitingForTheSlowHandler() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AsyncAuditDispatcher dispatcher = dispatcher(1, submission -> {
            entered.countDown();
            await(release);
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            dispatcher.start();
            dispatcher.submit(submission(1));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            dispatcher.submit(submission(2));

            Future<?> attempt = caller.submit(() ->
                    assertThrows(AuditUnavailableException.class, () -> dispatcher.submit(submission(3))));
            attempt.get(2, TimeUnit.SECONDS);

            assertEquals(1, dispatcher.queuedSubmissionCount());
        } finally {
            release.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
            dispatcher.stop();
        }
    }

    @Test
    void retainsFailedAndQueuedSubmissionsAndRejectsNewWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IllegalStateException handlerFailure = new IllegalStateException("Audit handler failed");
        AsyncAuditDispatcher dispatcher = dispatcher(1, submission -> {
            entered.countDown();
            await(release);
            throw handlerFailure;
        });
        try {
            dispatcher.start();
            dispatcher.submit(submission(1));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            dispatcher.submit(submission(2));
        } finally {
            release.countDown();
            dispatcher.stop();
        }

        assertEquals(submission(1), dispatcher.failedSubmission().orElseThrow());
        assertEquals(1, dispatcher.queuedSubmissionCount());
        assertFalse(dispatcher.isRunning());
        AuditUnavailableException rejected = assertThrows(AuditUnavailableException.class,
                () -> dispatcher.submit(submission(3)));
        assertTrue(rejected.getCause() instanceof AuditProcessingException);
        assertSame(handlerFailure, rejected.getCause().getCause());
        assertThrows(IllegalStateException.class, dispatcher::start);
    }

    @Test
    void rejectsWrongSizedWindowsBeforeQueueingThem() {
        AsyncAuditDispatcher dispatcher = dispatcher(1, submission -> {});
        dispatcher.start();
        AuditSubmission tooSmall = new AuditSubmission(1, List.of(transaction(1)));

        assertThrows(InvalidAuditSubmissionException.class, () -> dispatcher.submit(tooSmall));
        assertEquals(0, dispatcher.queuedSubmissionCount());
    }

    @Test
    void rejectsSubmissionsBeforeStartup() {
        AsyncAuditDispatcher dispatcher = dispatcher(1, submission -> {});

        assertThrows(AuditUnavailableException.class, () -> dispatcher.submit(submission(1)));
    }

    @Test
    void doesNotWrapAnAlreadyTypedAuditFailureAgain() {
        AuditProcessingException failure = new AuditProcessingException("Console write failed");
        AsyncAuditDispatcher dispatcher = dispatcher(1, submission -> { throw failure; });
        dispatcher.start();
        dispatcher.submit(submission(1));
        dispatcher.stop();

        assertSame(failure, assertThrows(AuditUnavailableException.class,
                () -> dispatcher.submit(submission(2))).getCause());
        assertEquals(submission(1), dispatcher.failedSubmission().orElseThrow());
    }

    @Test
    void rejectsMissingSubmissionsWithADomainException() {
        AsyncAuditDispatcher dispatcher = dispatcher(1, submission -> {});
        dispatcher.start();
        assertThrows(InvalidAuditSubmissionException.class, () -> dispatcher.submit(null));
        assertEquals(0, dispatcher.queuedSubmissionCount());
    }

    @Test
    void supportsRestartAfterACleanShutdown() {
        List<AuditSubmission> received = new CopyOnWriteArrayList<>();
        AsyncAuditDispatcher dispatcher = dispatcher(1, received::add);
        dispatcher.start();
        dispatcher.submit(submission(1));
        dispatcher.stop();
        dispatcher.start();
        dispatcher.submit(submission(2));
        dispatcher.stop();

        assertEquals(List.of(submission(1), submission(2)), received);
    }

    private AsyncAuditDispatcher dispatcher(int capacity, AuditSubmissionHandler handler) {
        AsyncAuditDispatcher dispatcher = new AsyncAuditDispatcher(2, capacity, handler);
        dispatchers.add(dispatcher);
        return dispatcher;
    }

    private static AuditSubmission submission(long sequence) {
        return new AuditSubmission(sequence, List.of(transaction(sequence * 2 - 1), transaction(sequence * 2)));
    }

    private static Transaction transaction(long id) {
        return new Transaction(new UUID(0, id), new BigDecimal("200.00"));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test handler was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test handler interrupted", exception);
        }
    }
}
