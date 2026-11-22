package com.jinternals.webhooks.consumer.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link HandlerExecutor} that runs handlers <strong>off the request thread</strong> on a bounded pool
 * and returns immediately, so the controller responds {@code 204} as soon as the webhook is verified
 * and accepted (see SPEC.md §15, §18). A {@code 2xx} therefore means "accepted", not "processed":
 * a crash before the queued task runs drops that delivery, so this mode is for handlers that are safe
 * to run best-effort or are themselves idempotent and backed by the sender's retries.
 *
 * <p><strong>Bounded.</strong> Core/max threads and the work-queue capacity are configurable. When the
 * queue is full the submitting (request) thread runs the task itself ({@code CallerRunsPolicy}), which
 * applies natural back-pressure instead of growing memory without limit.
 *
 * <p><strong>Timeout.</strong> A single scheduler arms a one-shot cancel of the task after
 * {@code webhooks.consumer.handler.timeout}. The cancel is <strong>armed before the task is submitted</strong>,
 * so it is in effect even on the {@code CallerRunsPolicy} path — where the task runs synchronously inside
 * the submit call and the submit would otherwise not return (to schedule the timeout) until the handler had
 * already finished. If the handler finished first the cancel is a no-op; otherwise it interrupts the stuck
 * handler so it can't tie up the worker (or the request thread, under caller-runs) forever.
 * <strong>Interruption is cooperative:</strong> {@code Future.cancel(true)} only sets the interrupt flag, so
 * a handler that ignores interruption (or blocks in non-interruptible/native code) cannot be forcibly
 * stopped — the timeout still fires, is logged, and is routed to the failure listener, and the daemon
 * thread guarantees such a handler can never keep the JVM alive.
 *
 * <p><strong>Failure visibility.</strong> Because the {@code 204} is already sent, a handler that throws
 * can no longer surface as a non-2xx the sender would retry on. Rather than let such a failure vanish, every
 * invocation is wrapped so a thrown exception is logged at {@code ERROR} <em>and</em> routed to the
 * {@link HandlerFailureListener}; a timeout is logged and routed the same way (SPEC §20). The listener is
 * the host's dead-letter / alerting hook; a listener that itself throws is isolated and logged.
 *
 * <p><strong>MDC propagation.</strong> The {@code MDC} is thread-local, so a handler run on a pool thread
 * would otherwise lose whatever correlation context the application put on the request thread's {@code MDC}
 * (e.g. a trace id set by a servlet filter or Micrometer Tracing / OpenTelemetry) — and could even inherit a
 * previous task's stale values. To keep log lines correlated across the thread hop, the submitting thread's
 * {@code MDC} map is captured at {@link #execute} time and re-established on the worker for the duration of
 * the handler, then the worker's prior {@code MDC} is restored in a {@code finally} (SPEC §9b). This also
 * covers the {@code CallerRunsPolicy} path, where the task runs on the request thread. The toolkit itself
 * puts nothing into the {@code MDC}; it only faithfully propagates the application's context.
 */
public class AsyncHandlerExecutor implements HandlerExecutor, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncHandlerExecutor.class);

    private final ThreadPoolExecutor pool;
    private final ScheduledThreadPoolExecutor timeoutScheduler;
    private final Duration timeout;
    private final HandlerFailureListener failureListener;

    public AsyncHandlerExecutor(int corePoolSize, int maxPoolSize, int queueCapacity, Duration timeout) {
        this(corePoolSize, maxPoolSize, queueCapacity, timeout, new NoOpHandlerFailureListener());
    }

    public AsyncHandlerExecutor(
            int corePoolSize,
            int maxPoolSize,
            int queueCapacity,
            Duration timeout,
            HandlerFailureListener failureListener) {
        this.timeout = timeout;
        this.failureListener = failureListener;
        AtomicLong n = new AtomicLong();
        this.pool = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                r -> namedDaemon(r, "webhook-handler-" + n.incrementAndGet()),
                (r, p) -> {
                    if (p.isShutdown()) {
                        // Unlike JDK CallerRunsPolicy (which silently discards tasks when shut down!), fail
                        // fast BEFORE the HTTP 204 is returned so the sender retries on a healthy instance.
                        throw new RejectedExecutionException("Async webhook executor is shut down");
                    }
                    r.run(); // full queue -> run on caller (back-pressure)
                });
        this.timeoutScheduler = new ScheduledThreadPoolExecutor(1, r -> namedDaemon(r, "webhook-handler-timeout"));
        // When a fast handler finishes before its timeout we cancel the scheduled cancel (see execute()); this
        // makes that cancellation actually evict the task from the delay queue at once, so dead cancels for
        // fast handlers cannot accumulate up to ~throughput×timeout entries under load.
        this.timeoutScheduler.setRemoveOnCancelPolicy(true);
    }

    @Override
    public void execute(String type, Runnable handlerCall) {
        // Capture the submitting (request) thread's MDC now, so the worker can re-establish the same
        // application-provided correlation context before running the handler (SPEC §9b).
        Map<String, String> submitterContext = MDC.getCopyOfContextMap();
        // Hold the scheduled timeout so the task's own completion (on whichever thread runs it) can cancel it.
        // (#2) Cancelling from inside the task — rather than only after pool.execute() returns — is what makes
        // the fast-handler case work on the COMMON pooled path too: when a pooled handler finishes on a worker
        // thread, pool.execute() has long since returned, so a post-execute() check would never see it done and
        // the dead cancel would linger in the delay queue for the full timeout. Cancelling it in the task's
        // finally block, with removeOnCancelPolicy(true), evicts it immediately regardless of which thread ran.
        AtomicReference<ScheduledFuture<?>> scheduledCancel = new AtomicReference<>();
        // A FutureTask lets us arm the timeout cancel BEFORE running the task. This matters on the
        // CallerRunsPolicy path: when the queue is full the rejected task runs synchronously inside
        // pool.execute(...) on the request thread, so if we scheduled the cancel only after execute()
        // returned, a stuck caller-run handler would never reach that line and would never be interrupted.
        // Scheduling against the task reference first means the timeout is in effect for both the pooled and
        // the caller-runs execution of the very same task.
        NamedTask task =
                new NamedTask(type, () -> runThenClearTimeout(submitterContext, type, handlerCall, scheduledCancel));
        ScheduledFuture<?> cancelFuture =
                timeoutScheduler.schedule(() -> cancelIfRunning(type, task), timeout.toMillis(), TimeUnit.MILLISECONDS);
        scheduledCancel.set(cancelFuture);
        try {
            pool.execute(task);
        } catch (RuntimeException e) {
            cancelFuture.cancel(false);
            throw e;
        }
        // (#1) Interrupt hygiene on the CallerRunsPolicy path. When the queue was full the task ran
        // synchronously on THIS (request/servlet) thread; a timeout firing in that window calls
        // future.cancel(true), which interrupts THIS thread, and a well-behaved handler re-asserts the
        // interrupt. Without clearing it we would hand the container back a request thread whose interrupt flag
        // is still set, poisoning the next blocking operation when the thread is reused. task.isDone() is true
        // here only when the task already ran to completion on this thread (caller-runs) or finished before we
        // got here; on the common pooled path it is still running on a worker and this is a no-op. Clearing is
        // safe: the only interrupt source inside this method is our own timeout cancel, and the container never
        // dispatches a request on an already-interrupted thread.
        if (task.isDone()) {
            Thread.interrupted(); // clear any interrupt our own timeout introduced on the request thread
        }
    }

    /**
     * Run the handler, then cancel its scheduled timeout — from inside the task, so it runs on whichever
     * thread actually executed the handler (a pool worker, or the request thread on the CallerRunsPolicy
     * path). Cancelling here means a handler that finishes well before its timeout evicts the now-pointless
     * scheduled cancel from the scheduler's delay queue at once (removeOnCancelPolicy(true)), so dead cancels
     * for fast handlers cannot accumulate up to ~throughput×timeout entries under load (#2).
     */
    private void runThenClearTimeout(
            Map<String, String> submitterContext,
            String type,
            Runnable handlerCall,
            AtomicReference<ScheduledFuture<?>> scheduledCancel) {
        try {
            runWithContext(submitterContext, type, handlerCall);
        } finally {
            // mayInterruptIfRunning=false: never interrupt the scheduler thread if the cancel is already
            // executing (i.e. the timeout fired). cancel() on an already-run/cancelled task is a harmless no-op.
            scheduledCancel.get().cancel(false);
        }
    }

    /**
     * Run the handler with the submitter's {@code MDC} installed on this (possibly pooled) thread, then
     * restore whatever this thread's {@code MDC} was before — so correlation keys don't leak between
     * reused pool threads. {@code getCopyOfContextMap()} may be {@code null} (empty context); both the
     * install and the restore treat {@code null} as "clear".
     */
    private void runWithContext(Map<String, String> submitterContext, String type, Runnable handlerCall) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        setContext(submitterContext);
        try {
            runGuarded(type, handlerCall);
        } finally {
            setContext(previous);
        }
    }

    /**
     * Replace the current thread's MDC with {@code context}. {@link MDC#setContextMap} already treats a
     * {@code null} map as "clear" (so a {@code null} captured context — i.e. an empty submitter MDC —
     * correctly clears the worker), letting this delegate unconditionally.
     */
    private static void setContext(Map<String, String> context) {
        MDC.setContextMap(context);
    }

    /**
     * Run the handler so a thrown exception is never swallowed: an {@link InterruptedException}-driven cancel
     * is reported as a timeout (handled by {@link #cancelIfRunning}, so skipped here to avoid double-report),
     * any other exception is logged and routed to the failure listener.
     */
    private void runGuarded(String type, Runnable handlerCall) {
        try {
            handlerCall.run();
        } catch (RuntimeException | Error e) {
            if (Thread.currentThread().isInterrupted()) {
                // Interrupted => this is the timeout path; cancelIfRunning reports it. Swallow here only.
                return;
            }
            log.error(
                    "Async webhook handler for type '{}' failed; the delivery was already acknowledged "
                            + "with 204 and will not be retried by the sender",
                    type,
                    e);
            notifyFailed(type, e);
        }
    }

    private void cancelIfRunning(String type, Future<?> future) {
        // cancel(true) returns false if the task already completed (a no-op); true if it was still
        // running and has now been interrupted.
        if (future.cancel(true)) {
            log.warn(
                    "Async webhook handler for type '{}' exceeded {} and was interrupted; "
                            + "the delivery was already acknowledged with 204",
                    type,
                    timeout);
            notifyTimeout(type);
        }
    }

    /** Route a handler failure to the listener, isolating a listener that itself throws. */
    private void notifyFailed(String type, Throwable cause) {
        try {
            failureListener.onHandlerFailed(type, cause);
        } catch (RuntimeException listenerError) {
            log.error(
                    "HandlerFailureListener threw while reporting a failed handler for type '{}'", type, listenerError);
        }
    }

    /** Route a handler timeout to the listener, isolating a listener that itself throws. */
    private void notifyTimeout(String type) {
        try {
            failureListener.onHandlerTimeout(type);
        } catch (RuntimeException listenerError) {
            log.error(
                    "HandlerFailureListener threw while reporting a timed-out handler for type '{}'",
                    type,
                    listenerError);
        }
    }

    /**
     * Thread factory body: a named <strong>daemon</strong> thread, so a hung handler can never keep the
     * JVM alive. Package-private and static so the daemon flag can be asserted directly and
     * deterministically (a pool thread would otherwise inherit the daemon status of whichever thread
     * happened to trigger its lazy creation).
     */
    static Thread namedDaemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /**
     * Gracefully drain in-flight and queued handlers up to {@link #timeout}, since every submitted task was
     * already acknowledged with {@code 204} and will not be retried by the sender. Any queued task that still
     * could not run before the drain window expired (or before the closing thread was interrupted) is logged
     * and routed to {@link #failureListener} so it is never silently dropped.
     */
    @Override
    public void close() {
        pool.shutdown();
        try {
            pool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            abortRemainingTasks();
            timeoutScheduler.shutdownNow();
        }
    }

    private void abortRemainingTasks() {
        for (Runnable unexecuted : pool.shutdownNow()) {
            NamedTask task = (NamedTask) unexecuted;
            if (task.isCancelled()) {
                // Already timed out in the queue and reported via cancelIfRunning -> onHandlerTimeout; skip so
                // it is not reported a second time as a shutdown failure.
                continue;
            }
            String droppedType = task.type;
            IllegalStateException dropped =
                    new IllegalStateException("Async webhook executor shut down before queued handler could run");
            log.error(
                    "Async webhook handler for type '{}' was dropped on shutdown; "
                            + "the delivery was already acknowledged with 204",
                    droppedType,
                    dropped);
            notifyFailed(droppedType, dropped);
        }
    }

    private static final class NamedTask extends FutureTask<Void> {
        final String type;

        NamedTask(String type, Runnable runnable) {
            super(runnable, null);
            this.type = type;
        }
    }
}
