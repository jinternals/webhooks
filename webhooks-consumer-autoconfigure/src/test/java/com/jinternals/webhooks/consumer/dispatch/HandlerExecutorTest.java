package com.jinternals.webhooks.consumer.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.consumer.support.LogCapture;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class HandlerExecutorTest {

    @Test
    void syncRunsInlineOnTheCallingThread() {
        SyncHandlerExecutor executor = new SyncHandlerExecutor();
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        executor.execute("contact.created", () -> ranOn.set(Thread.currentThread()));

        // Inline => the task ran on the test thread before execute() returned.
        assertThat(ranOn.get()).isSameAs(Thread.currentThread());
    }

    @Test
    void asyncRunsOffThreadAndCompletes() throws Exception {
        try (AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 10, Duration.ofSeconds(5))) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Thread> ranOn = new AtomicReference<>();

            executor.execute("contact.created", () -> {
                ranOn.set(Thread.currentThread());
                done.countDown();
            });

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ranOn.get()).isNotSameAs(Thread.currentThread());
            assertThat(ranOn.get().getName()).startsWith("webhook-handler-");
            // Worker threads must be daemons so a hung handler can't keep the JVM alive. Kills the
            // VoidMethodCall mutant that drops setDaemon(true), and pins the factory's non-null return.
            assertThat(ranOn.get().isDaemon()).isTrue();
        }
    }

    @Test
    void asyncInterruptsAndWarnsWhenAHandlerExceedsTheTimeout() throws Exception {
        try (LogCapture logs = LogCapture.forClass(AsyncHandlerExecutor.class);
                AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 10, Duration.ofMillis(50))) {
            AtomicBoolean interrupted = new AtomicBoolean(false);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);

            executor.execute("slow.type", () -> {
                started.countDown();
                try {
                    Thread.sleep(5_000); // far longer than the 50ms timeout
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted).isTrue(); // the timeout scheduler cancelled & interrupted it
            // cancel(true) returned true (was still running) -> the warn line MUST be emitted. Kills the
            // RemoveConditional mutants on `if (future.cancel(true))`.
            Thread.sleep(100); // let the scheduler thread finish logging after the cancel
            assertThat(logs.count()).isEqualTo(1);
            assertThat(logs.lastFormattedMessage())
                    .contains("slow.type")
                    .contains("exceeded")
                    .contains("interrupted");
        }
    }

    @Test
    void asyncDoesNotInterruptOrWarnWhenAHandlerFinishesBeforeTheTimeout() throws Exception {
        try (LogCapture logs = LogCapture.forClass(AsyncHandlerExecutor.class);
                AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 10, Duration.ofMillis(200))) {
            AtomicBoolean interrupted = new AtomicBoolean(false);
            CountDownLatch finished = new CountDownLatch(1);

            executor.execute("fast.type", () -> {
                try {
                    Thread.sleep(10); // completes well before the 200ms timeout
                } catch (InterruptedException e) {
                    interrupted.set(true);
                } finally {
                    finished.countDown();
                }
            });

            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            // Give the (already-expired) cancel a chance to fire; it must be a no-op on a done task.
            Thread.sleep(300);
            assertThat(interrupted).isFalse();
            // cancel(true) returned false (already completed) -> NO warn line. Kills the opposite-branch
            // RemoveConditional mutant that would always (or never) log.
            assertThat(logs.count()).isZero();
        }
    }

    @Test
    void asyncAppliesBackPressureByRunningOnTheCallerWhenSaturated() throws Exception {
        // 1 thread, queue capacity 1. Occupy the worker + fill the queue, then the 3rd task must run on
        // the caller (CallerRunsPolicy) rather than being rejected or growing the queue.
        try (AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 1, Duration.ofSeconds(30))) {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch workerBusy = new CountDownLatch(1);
            AtomicInteger ran = new AtomicInteger();

            executor.execute(
                    "t",
                    () -> { // occupies the single worker thread
                        workerBusy.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        ran.incrementAndGet();
                    });
            assertThat(workerBusy.await(2, TimeUnit.SECONDS)).isTrue();

            executor.execute("t", ran::incrementAndGet); // fills the bounded queue

            AtomicReference<Thread> ranOn = new AtomicReference<>();
            executor.execute("t", () -> ranOn.set(Thread.currentThread())); // queue full -> caller runs it

            // CallerRunsPolicy ran the 3rd task synchronously on this thread, before execute() returned.
            assertThat(ranOn.get()).isSameAs(Thread.currentThread());

            release.countDown();
        }
    }

    @Test
    void asyncTimesOutAStuckHandlerEvenOnTheCallerRunsPath() throws Exception {
        // Regression for the armed-before-submit fix: when the pool is saturated the task runs synchronously
        // on the caller inside execute(). If the timeout cancel were scheduled only AFTER execute() returned,
        // a stuck caller-run handler would block that line forever and never be interrupted. With the cancel
        // armed before submission, even a caller-run handler is interrupted at the timeout. Here: occupy the
        // single worker + fill the queue, then submit a slow handler that is forced onto the caller; it must
        // be interrupted by the timeout, and execute() must return rather than block for the full sleep.
        try (AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 1, Duration.ofMillis(100))) {
            CountDownLatch releaseWorker = new CountDownLatch(1);
            CountDownLatch workerBusy = new CountDownLatch(1);
            executor.execute(
                    "t",
                    () -> { // occupy the single worker thread
                        workerBusy.countDown();
                        try {
                            releaseWorker.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            assertThat(workerBusy.await(2, TimeUnit.SECONDS)).isTrue();
            executor.execute("t", () -> {}); // fill the bounded queue

            AtomicBoolean callerRunInterrupted = new AtomicBoolean(false);
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            long start = System.nanoTime();
            // Queue full -> this slow handler runs on THIS (caller) thread and must be interrupted at 100ms.
            executor.execute("t", () -> {
                ranOn.set(Thread.currentThread());
                try {
                    Thread.sleep(5_000); // far longer than the 100ms timeout
                } catch (InterruptedException e) {
                    callerRunInterrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            });
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(ranOn.get()).isSameAs(Thread.currentThread()); // confirm the caller-runs path
            assertThat(callerRunInterrupted).isTrue(); // the armed timeout interrupted the caller-run handler
            assertThat(elapsedMs).isLessThan(4_000); // returned at ~100ms, not after the full 5s sleep
            // Interrupt hygiene: the handler re-asserted the interrupt (as well-behaved code does), but
            // execute() must NOT hand this (request/servlet) thread back with the interrupt flag still set —
            // otherwise the container reuses a poisoned thread. isInterrupted() is non-clearing, so this
            // asserts the flag is already clear after execute() returned.
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the caller/request thread must not be left interrupted after a caller-runs timeout")
                    .isFalse();

            releaseWorker.countDown();
        }
    }

    @Test
    void doesNotLeaveTheCallerThreadInterruptedWhenACallerRunHandlerReassertsInterrupt() {
        // Focused companion to the timeout test: prove the interrupt the timeout introduced on the caller is
        // cleared before execute() returns, for a handler that explicitly re-asserts interruption. If the
        // clear (Thread.interrupted()) were removed, this thread would return still-interrupted.
        try (AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 1, Duration.ofMillis(100))) {
            CountDownLatch releaseWorker = new CountDownLatch(1);
            CountDownLatch workerBusy = new CountDownLatch(1);
            executor.execute("t", () -> {
                workerBusy.countDown();
                try {
                    releaseWorker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                assertThat(workerBusy.await(2, TimeUnit.SECONDS)).isTrue();
                executor.execute("t", () -> {}); // fill the queue

                AtomicReference<Thread> ranOn = new AtomicReference<>();
                executor.execute("t", () -> {
                    ranOn.set(Thread.currentThread());
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); // re-assert, as well-behaved handlers do
                    }
                });

                assertThat(ranOn.get()).isSameAs(Thread.currentThread()); // caller-runs path confirmed
                assertThat(Thread.currentThread().isInterrupted())
                        .as("interrupt must be cleared so the pooled request thread is not poisoned")
                        .isFalse();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } finally {
                releaseWorker.countDown();
            }
        }
    }

    @Test
    void preservesACallerInterruptOnThePooledPathWhereTheTaskIsNotRunInline() throws Exception {
        // On the POOLED path the task is still running on a worker when execute() returns, so task.isDone() is
        // false and the method must NOT clear the caller's interrupt flag — that clear exists only to scrub the
        // interrupt OUR OWN timeout introduces on the CallerRunsPolicy path. If the task.isDone() guard were
        // dropped (if(true)), execute() would swallow a pre-existing interrupt the caller set for its own
        // reasons. We pre-set this thread's interrupt, submit a handler that genuinely runs on a worker (queue
        // has room, so no caller-runs), and assert the flag survived.
        try (AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 10, Duration.ofSeconds(60))) {
            CountDownLatch workerBusy = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            AtomicReference<Thread> ranOn = new AtomicReference<>();

            Thread.currentThread().interrupt(); // a caller interrupt that has nothing to do with our timeout
            try {
                executor.execute("pooled.type", () -> {
                    ranOn.set(Thread.currentThread());
                    workerBusy.countDown();
                    try {
                        releaseWorker.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

                // Read the flag non-destructively FIRST: on the pooled path (task not done on return) the
                // method must have left our caller interrupt untouched. Checking before any interruptible wait
                // avoids the pre-set flag aborting the await itself.
                boolean callerStillInterrupted = Thread.currentThread().isInterrupted();
                Thread.interrupted(); // now clear it so the await below can block normally
                assertThat(callerStillInterrupted)
                        .as("a caller's own interrupt must survive the pooled path (nothing of ours to clear)")
                        .isTrue();

                // The handler ran on a pool worker, not this thread (confirms the pooled, not caller-runs, path).
                assertThat(workerBusy.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(ranOn.get()).isNotSameAs(Thread.currentThread());
            } finally {
                releaseWorker.countDown();
                Thread.interrupted(); // scrub any residual interrupt so it cannot leak into later tests
            }
        }
    }

    @Test
    void namedDaemonFactoryProducesANamedDaemonThread() throws Exception {
        // Direct, race-free assertion of the thread-factory invariant: the produced thread carries the
        // given name and is a daemon. Kills the VoidMethodCall mutant that drops setDaemon(true) and the
        // NullReturn mutant on the factory.
        //
        // A new Thread inherits the daemon flag of the thread that CREATES it, so we must create from a
        // known NON-daemon context — otherwise a mutant dropping setDaemon(true) could still look daemon
        // by inheritance (if the JUnit thread happens to be a daemon) and survive. We create the factory
        // thread from an explicitly non-daemon worker, so setDaemon(true) is the *only* thing that can
        // make it a daemon.
        AtomicReference<Thread> produced = new AtomicReference<>();
        Thread creator =
                new Thread(() -> produced.set(AsyncHandlerExecutor.namedDaemon(() -> {}, "webhook-handler-7")));
        creator.setDaemon(false);
        creator.start();
        creator.join(2_000);

        Thread t = produced.get();
        assertThat(t).isNotNull();
        assertThat(t.getName()).isEqualTo("webhook-handler-7");
        assertThat(t.isDaemon()).isTrue();
    }

    @Test
    void fastHandlersDoNotLeaveScheduledCancelsQueuedUntilTimeout() throws Exception {
        // With a long timeout and fast handlers, each armed cancel is cancelled once the handler finishes. The
        // scheduler is configured with removeOnCancelPolicy(true), so a cancelled task is evicted from the
        // delay queue at once instead of lingering for the full timeout. Without BOTH the explicit cancel and
        // that policy, ~N dead cancels would pile up here. We submit well more than the queue capacity of
        // fast, inline-completing handlers and assert the scheduler's queue does not retain them.
        try (AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 1, Duration.ofSeconds(60))) {
            java.util.concurrent.ScheduledThreadPoolExecutor scheduler = timeoutSchedulerOf(executor);
            assertThat(scheduler.getRemoveOnCancelPolicy())
                    .as("removeOnCancelPolicy must be enabled so cancelled timeouts are evicted immediately")
                    .isTrue();

            for (int i = 0; i < 200; i++) {
                CountDownLatch done = new CountDownLatch(1);
                executor.execute("t", done::countDown);
                assertThat(done.await(2, TimeUnit.SECONDS)).isTrue(); // handler completed
            }

            // Give the just-cancelled tasks a moment to drain, then assert the queue is not holding ~200 dead
            // cancels. It is bounded by in-flight work, not by the number of handlers ever submitted.
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(scheduler.getQueue().size()).isLessThan(10));
        }
    }

    private static java.util.concurrent.ScheduledThreadPoolExecutor timeoutSchedulerOf(AsyncHandlerExecutor executor)
            throws Exception {
        java.lang.reflect.Field f = AsyncHandlerExecutor.class.getDeclaredField("timeoutScheduler");
        f.setAccessible(true);
        return (java.util.concurrent.ScheduledThreadPoolExecutor) f.get(executor);
    }

    @Test
    void asyncPropagatesSubmitterMdcToTheWorkerThread() throws Exception {
        // Whatever the APPLICATION put in the MDC on the submitting thread (e.g. a webhook.id it bound in
        // its own filter, and a traceId from Micrometer/OTel) must be visible to the handler even though it
        // runs on a pool thread. The toolkit binds nothing itself; it only propagates the app's context.
        // Without propagation these would be null on the worker.
        try (AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 10, Duration.ofSeconds(5))) {
            MDC.put("webhook.id", "msg_async_1");
            MDC.put("traceId", "trace-async-1");
            try {
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<String> seenWebhookId = new AtomicReference<>();
                AtomicReference<String> seenTraceId = new AtomicReference<>();
                AtomicReference<Thread> ranOn = new AtomicReference<>();

                executor.execute("contact.created", () -> {
                    ranOn.set(Thread.currentThread());
                    seenWebhookId.set(MDC.get("webhook.id"));
                    seenTraceId.set(MDC.get("traceId"));
                    done.countDown();
                });

                assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(ranOn.get()).isNotSameAs(Thread.currentThread()); // genuinely off-thread
                assertThat(seenWebhookId.get()).isEqualTo("msg_async_1");
                assertThat(seenTraceId.get()).isEqualTo("trace-async-1");
            } finally {
                MDC.clear();
            }
        }
    }

    @Test
    void asyncRestoresWorkerMdcAfterTheHandlerSoItDoesNotLeakBetweenTasks() throws Exception {
        // A reused pool thread must not carry one delivery's correlation keys into the next. The worker's
        // MDC before/after the handler must be unchanged (here: empty), proving the restore in finally.
        try (AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 10, Duration.ofSeconds(5))) {
            // First task binds a context; capture the worker's MDC AFTER it returns, via a second task
            // forced onto the same single worker thread.
            MDC.put("webhook.id", "msg_first");
            CountDownLatch first = new CountDownLatch(1);
            executor.execute("t", first::countDown);
            assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();
            MDC.clear(); // submitter clears its own; the worker's restore is what we assert next

            CountDownLatch second = new CountDownLatch(1);
            AtomicReference<Map<String, String>> workerMdcAtStart = new AtomicReference<>();
            executor.execute("t", () -> {
                // No submitter context now => the worker must have been cleared, not left with msg_first.
                workerMdcAtStart.set(MDC.getCopyOfContextMap());
                second.countDown();
            });
            assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();

            // Either null (cleared) or an empty map is correct; crucially it must NOT contain msg_first.
            Map<String, String> ctx = workerMdcAtStart.get();
            assertThat(ctx == null || ctx.isEmpty()).isTrue();
        }
    }

    @Test
    void callerRunsRestoresTheCallerMdcAfterTheHandlerMutatesIt() throws Exception {
        // On the CallerRunsPolicy path the handler runs on THIS thread. The submitter's MDC is captured,
        // re-installed, and must be restored in finally. We prove the restore (setContext(previous)) by
        // having the handler ADD a key: after execute() returns, the caller's MDC must be back to its
        // pre-call state with that key gone. Dropping the finally restore would leave the key behind.
        try (AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 1, Duration.ofSeconds(30))) {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch workerBusy = new CountDownLatch(1);
            executor.execute(
                    "t",
                    () -> { // occupy the single worker
                        workerBusy.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            executor.execute("t", () -> {}); // fill the bounded queue
            assertThat(workerBusy.await(2, TimeUnit.SECONDS)).isTrue();

            MDC.put("webhook.id", "caller-id"); // the caller thread's own correlation context
            try {
                AtomicReference<Thread> ranOn = new AtomicReference<>();
                // Queue full -> runs on THIS (caller) thread, and mutates MDC inside the handler.
                executor.execute("t", () -> {
                    ranOn.set(Thread.currentThread());
                    MDC.put("leaked", "should-be-removed-on-restore");
                });
                assertThat(ranOn.get()).isSameAs(Thread.currentThread()); // confirm caller-runs path
                // finally restored the caller's MDC: the handler-added key is gone, the original remains.
                assertThat(MDC.get("leaked")).isNull();
                assertThat(MDC.get("webhook.id")).isEqualTo("caller-id");
            } finally {
                MDC.clear();
                release.countDown();
            }
        }
    }

    @Test
    void closeIsIdempotentAndStopsTheExecutor() throws Exception {
        AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 1, Duration.ofSeconds(5));
        long start = System.nanoTime();
        executor.close();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // Killing "remove pool.shutdown()": without shutdown(), awaitTermination(5s) would block for 5s on an
        // idle pool instead of returning immediately.
        assertThat(elapsedMs).isLessThan(1_000);
        assertThat(timeoutSchedulerOf(executor).isShutdown()).isTrue();
        executor.close(); // second close must not throw
    }

    @Test
    void closeDrainsInFlightAndQueuedHandlersBeforeReturning() throws Exception {
        // Already-accepted (204) handlers must be allowed to finish during close() rather than immediately
        // interrupted/discarded by shutdownNow(). Kills "remove pool.awaitTermination(...)".
        RecordingFailureListener listener = new RecordingFailureListener();
        AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 10, Duration.ofSeconds(5), listener);
        CountDownLatch workerStarted = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();

        executor.execute("in.flight", () -> {
            workerStarted.countDown();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            completed.incrementAndGet();
        });
        assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        executor.execute("queued.event", completed::incrementAndGet); // sits in queue behind the busy worker

        executor.close();

        assertThat(completed.get()).isEqualTo(2);
        assertThat(listener.failedTypes).isEmpty();
        assertThat(listener.timedOutTypes).isEmpty();
    }

    @Test
    void closeRestoresInterruptFlagAndReportsDroppedQueuedHandlersToFailureListener() throws Exception {
        // When close() is interrupted (or the drain window expires) while tasks remain in the queue, those
        // unexecuted tasks must be logged and reported to HandlerFailureListener rather than silently dropped,
        // and the caller's interrupt flag must be restored.
        RecordingFailureListener listener = new RecordingFailureListener();
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        try (LogCapture logs = LogCapture.forClass(AsyncHandlerExecutor.class)) {
            AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 10, Duration.ofSeconds(60), listener);
            executor.execute("busy.event", () -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            executor.execute("dropped.event", () -> {}); // queued behind busy worker

            Thread.currentThread().interrupt();
            try {
                executor.close();
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
                releaseWorker.countDown();
            }

            assertThat(listener.failedTypes).containsExactly("dropped.event");
            assertThat(listener.causes).hasSize(1);
            assertThat(listener.causes.get(0))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("shut down before queued handler could run");
            assertThat(logs.formattedMessages())
                    .anyMatch(m -> m.contains("dropped.event") && m.contains("dropped on shutdown"));
        }
    }

    @Test
    void aTimeoutFiringOnAnAlreadyCompletedHandlerIsASilentNoOp() throws Exception {
        // The scheduled cancel can still fire in the race window after a handler has completed but before its
        // self-cancel (runThenClearTimeout) evicts it. In that case future.cancel(true) returns false and the
        // method must NOT log a timeout warn nor notify the listener — otherwise a handler that finished
        // successfully would be falsely reported as timed out. Driving cancelIfRunning directly against an
        // already-completed Future makes that race deterministic and kills the conditional mutant that would
        // treat cancel(true)==false as a timeout.
        RecordingFailureListener listener = new RecordingFailureListener();
        try (LogCapture logs = LogCapture.forClass(AsyncHandlerExecutor.class);
                AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 10, Duration.ofSeconds(60), listener)) {
            FutureTask<?> completed = new FutureTask<>(() -> null);
            completed.run(); // runs inline and completes; cancel(true) will now return false
            assertThat(completed.isDone()).isTrue();

            java.lang.reflect.Method cancelIfRunning = AsyncHandlerExecutor.class.getDeclaredMethod(
                    "cancelIfRunning", String.class, java.util.concurrent.Future.class);
            cancelIfRunning.setAccessible(true);
            cancelIfRunning.invoke(executor, "done.type", completed);

            assertThat(listener.timedOutTypes).isEmpty();
            assertThat(listener.failedTypes).isEmpty();
            assertThat(logs.count()).isZero();
        }
    }

    /** Records failure/timeout callbacks for assertions, and can be made to throw to prove isolation. */
    private static final class RecordingFailureListener implements HandlerFailureListener {
        final List<String> failedTypes = new CopyOnWriteArrayList<>();
        final List<Throwable> causes = new CopyOnWriteArrayList<>();
        final List<String> timedOutTypes = new CopyOnWriteArrayList<>();
        private final boolean throwing;

        RecordingFailureListener() {
            this(false);
        }

        RecordingFailureListener(boolean throwing) {
            this.throwing = throwing;
        }

        @Override
        public void onHandlerFailed(String type, Throwable cause) {
            failedTypes.add(type);
            causes.add(cause);
            if (throwing) {
                throw new IllegalStateException("listener boom");
            }
        }

        @Override
        public void onHandlerTimeout(String type) {
            timedOutTypes.add(type);
            if (throwing) {
                throw new IllegalStateException("listener boom");
            }
        }
    }

    @Test
    void asyncLogsAndRoutesAHandlerExceptionToTheFailureListener() throws Exception {
        RecordingFailureListener listener = new RecordingFailureListener();
        RuntimeException boom = new IllegalStateException("handler blew up");
        try (LogCapture logs = LogCapture.forClass(AsyncHandlerExecutor.class);
                AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 10, Duration.ofSeconds(30), listener)) {
            CountDownLatch reported = new CountDownLatch(1);
            listenerSignal(listener, reported);

            executor.execute("contact.created", () -> {
                throw boom;
            });

            assertThat(reported.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(listener.failedTypes).containsExactly("contact.created");
            assertThat(listener.causes).containsExactly(boom); // the exact exception, not a wrapper
            assertThat(listener.timedOutTypes).isEmpty();
            // A thrown handler is never swallowed: it is logged at ERROR with the type + "204".
            assertThat(logs.count()).isEqualTo(1);
            assertThat(logs.lastFormattedMessage()).contains("contact.created").contains("204");
        }
    }

    @Test
    void asyncRoutesATimeoutToTheFailureListener() throws Exception {
        RecordingFailureListener listener = new RecordingFailureListener();
        try (AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 10, Duration.ofMillis(50), listener)) {
            CountDownLatch started = new CountDownLatch(1);

            executor.execute("slow.type", () -> {
                started.countDown();
                try {
                    Thread.sleep(5_000); // far longer than the 50ms timeout
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            // The timeout cancel runs on the scheduler thread; wait for the callback to land.
            long deadline = System.currentTimeMillis() + 2_000;
            while (listener.timedOutTypes.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(listener.timedOutTypes).containsExactly("slow.type");
            assertThat(listener.failedTypes).isEmpty(); // a timeout is not reported as a failure
        }
    }

    @Test
    void anInterruptedHandlerThatThrowsIsTreatedAsATimeoutNotAFailure() throws Exception {
        // A handler that, on timeout-interrupt, re-throws WITHOUT clearing the interrupt flag. runGuarded
        // catches the throw with Thread.isInterrupted()==true, so it is the timeout path: it must NOT be
        // reported via onHandlerFailed (cancelIfRunning already reports the timeout). This covers the
        // interrupted-branch `return` and kills the conditional mutant that would mis-route it as a failure.
        RecordingFailureListener listener = new RecordingFailureListener();
        try (LogCapture logs = LogCapture.forClass(AsyncHandlerExecutor.class);
                AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 10, Duration.ofMillis(50), listener)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch threw = new CountDownLatch(1);

            executor.execute("slow.throwing", () -> {
                started.countDown();
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    // Re-assert the interrupt flag, then throw: runGuarded sees isInterrupted()==true.
                    Thread.currentThread().interrupt();
                    threw.countDown();
                    throw new IllegalStateException("interrupted mid-handler");
                }
            });

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(threw.await(2, TimeUnit.SECONDS)).isTrue();

            // Give both the cancel (timeout report) and runGuarded's swallow a moment to settle.
            long deadline = System.currentTimeMillis() + 2_000;
            while (listener.timedOutTypes.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(listener.timedOutTypes).containsExactly("slow.throwing"); // reported as a timeout
            assertThat(listener.failedTypes).isEmpty(); // NOT as a failure
            // Only the timeout WARN is logged — no ERROR "failed" line for the interrupted throw.
            assertThat(logs.count()).isEqualTo(1);
            assertThat(logs.lastFormattedMessage()).contains("exceeded").contains("interrupted");
        }
    }

    @Test
    void aThrowingFailureListenerIsIsolatedOnTheExceptionPath() throws Exception {
        RecordingFailureListener listener = new RecordingFailureListener(true);
        try (LogCapture logs = LogCapture.forClass(AsyncHandlerExecutor.class);
                AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 10, Duration.ofSeconds(30), listener)) {
            executor.execute("t", () -> {
                throw new IllegalStateException("handler blew up");
            });

            long deadline = System.currentTimeMillis() + 2_000;
            while (listener.failedTypes.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(listener.failedTypes).containsExactly("t");
            // Two ERROR lines: the handler failure, then the listener's own throw — isolated, not propagated.
            long deadline2 = System.currentTimeMillis() + 2_000;
            while (logs.count() < 2 && System.currentTimeMillis() < deadline2) {
                Thread.sleep(10);
            }
            assertThat(logs.count()).isEqualTo(2);
            assertThat(logs.lastFormattedMessage())
                    .contains("HandlerFailureListener threw")
                    .contains("'t'");
        }
    }

    @Test
    void aThrowingFailureListenerIsIsolatedOnTheTimeoutPath() throws Exception {
        RecordingFailureListener listener = new RecordingFailureListener(true);
        try (LogCapture logs = LogCapture.forClass(AsyncHandlerExecutor.class);
                AsyncHandlerExecutor executor = new AsyncHandlerExecutor(1, 1, 10, Duration.ofMillis(50), listener)) {
            CountDownLatch started = new CountDownLatch(1);
            executor.execute("slow", () -> {
                started.countDown();
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            long deadline = System.currentTimeMillis() + 2_000;
            while (listener.timedOutTypes.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(listener.timedOutTypes).containsExactly("slow");
            // The warn (timeout) + the error (listener threw) are both present; the throw was isolated.
            long deadline2 = System.currentTimeMillis() + 2_000;
            while (logs.count() < 2 && System.currentTimeMillis() < deadline2) {
                Thread.sleep(10);
            }
            assertThat(logs.count()).isEqualTo(2);
            assertThat(logs.lastFormattedMessage())
                    .contains("HandlerFailureListener threw")
                    .contains("'slow'");
        }
    }

    /** Make the recording listener count down a latch on its next failure callback (test synchronisation). */
    private static void listenerSignal(RecordingFailureListener listener, CountDownLatch latch) {
        // The listener records into failedTypes; poll that in a tiny watcher so the test can await it.
        Thread watcher = new Thread(() -> {
            while (listener.failedTypes.isEmpty()) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            latch.countDown();
        });
        watcher.setDaemon(true);
        watcher.start();
    }
}
