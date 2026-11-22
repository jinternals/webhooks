package com.jinternals.webhooks.consumer.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class IdempotencyGuardTest {

    @Test
    void noOpAlwaysAllowsAndRemembersNothing() {
        NoOpIdempotencyGuard guard = new NoOpIdempotencyGuard();
        // tryBegin always proceeds (true), even after a commit: the guard remembers nothing.
        assertThat(guard.tryBegin("a")).isTrue();
        guard.markProcessed("a");
        assertThat(guard.tryBegin("a")).isTrue();
        // markProcessed / release are no-ops that must not throw.
        guard.release("a");
        guard.markProcessed(null);
        guard.release(null);
    }

    @Test
    void inMemoryReservesAndSkipsConcurrentDuplicate() {
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        assertThat(guard.tryBegin("id-1")).isTrue(); // first time -> reserved, proceed
        assertThat(guard.tryBegin("id-1")).isFalse(); // still reserved/in-flight -> skip as duplicate
        assertThat(guard.tryBegin("id-2")).isTrue(); // distinct id -> proceed
    }

    @Test
    void inMemorySuppressesRetryAfterCommit() {
        // Commit keeps the id remembered, so a later retry of the same id is skipped.
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        assertThat(guard.tryBegin("id-1")).isTrue();
        guard.markProcessed("id-1");
        assertThat(guard.tryBegin("id-1")).isFalse();
    }

    @Test
    void inMemoryAllowsRetryAfterRelease() {
        // Release drops the reservation, so a failed delivery's retry is allowed to run (the core
        // reserve/commit property: a failure must not permanently dedupe the id).
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        assertThat(guard.tryBegin("id-1")).isTrue();
        guard.release("id-1");
        assertThat(guard.tryBegin("id-1")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void inMemoryIgnoresBlankIds() {
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        Map<String, Instant> seen = (Map<String, Instant>) ReflectionTestUtils.getField(guard, "seen");

        // A blank id is never a duplicate (always proceeds) and is never stored.
        assertThat(guard.tryBegin(null)).isTrue();
        assertThat(guard.tryBegin("")).isTrue();
        assertThat(guard.tryBegin("   ")).isTrue();
        assertThat(seen).as("tryBegin must never store a blank id").isEmpty();

        // Committing a blank id (null, "", "   ") must be a no-op that stores nothing. Inspecting the map
        // directly makes the storage observable: without the null/isBlank guard, markProcessed would
        // insert the blank key (tryBegin's own guard would otherwise hide it, since it short-circuits
        // blank ids to true). Covers both halves of `webhookId == null || webhookId.isBlank()`.
        guard.markProcessed(null);
        guard.markProcessed("");
        guard.markProcessed("   ");
        assertThat(seen).as("markProcessed must never store a blank id").isEmpty();

        // Releasing a blank id is likewise a short-circuit no-op: it must NOT reach seen.remove(...).
        // Seed real + blank keys directly, then prove release leaves the blanks in place — without the
        // guard, release(null)/release("")/release("   ") would remove exactly those entries, so their
        // survival kills the dropped-guard mutant.
        seen.clear();
        seen.put("", Instant.now().plusSeconds(600));
        seen.put("   ", Instant.now().plusSeconds(600));
        guard.release(null);
        guard.release("");
        guard.release("   ");
        assertThat(seen)
                .as("release of a blank id must short-circuit before remove(...)")
                .containsOnlyKeys("", "   ");

        seen.clear();
        assertThat(guard.tryBegin("   ")).isTrue();
        assertThat(guard.tryBegin("")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void inMemoryMarkProcessedStoresANonBlankId() {
        // markProcessed on a non-blank id must actually store it (so a later retry is deduped). This
        // pins the "else" path past the blank guard: a mutant that turns the guard always-true (making
        // markProcessed a complete no-op) is killed because "fresh" would then never be stored.
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        Map<String, Instant> seen = (Map<String, Instant>) ReflectionTestUtils.getField(guard, "seen");

        guard.markProcessed("fresh");
        assertThat(seen).containsKey("fresh");
        assertThat(guard.tryBegin("fresh")).isFalse(); // now deduped
    }

    @Test
    @SuppressWarnings("unchecked")
    void inMemoryReleaseRemovesANonBlankId() {
        // release on a non-blank id must actually remove it. Pins the "else" path past the blank guard:
        // a mutant that turns the guard always-true (making release a no-op) is killed because the
        // seeded entry would then survive.
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        Map<String, Instant> seen = (Map<String, Instant>) ReflectionTestUtils.getField(guard, "seen");
        seen.put("gone", Instant.now().plusSeconds(600));

        guard.release("gone");
        assertThat(seen).doesNotContainKey("gone");
    }

    @Test
    @SuppressWarnings("unchecked")
    void inMemoryTreatsExpiredReservationAsNew() {
        // A prior entry whose expiry is already in the past must be treated as new (returns true, i.e.
        // proceed), not skipped. Kills the mutant dropping the `!previousExpiry.isAfter(now)` check
        // (which would skip any re-seen id regardless of age).
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        Map<String, Instant> seen = (Map<String, Instant>) ReflectionTestUtils.getField(guard, "seen");
        seen.put("stale", Instant.now().minusSeconds(60)); // already expired

        assertThat(guard.tryBegin("stale")).isTrue(); // expired -> new, proceed
        assertThat(guard.tryBegin("stale")).isFalse(); // just reserved -> now a duplicate
    }

    @Test
    void inMemoryRefreshesTtlOnCommit() {
        // markProcessed refreshes the TTL from completion time, so a slow handler can't let the entry
        // expire mid-flight and then be re-processed. After a commit the id is still remembered.
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        assertThat(guard.tryBegin("id-1")).isTrue();
        guard.markProcessed("id-1");
        // A fresh commit alone keeps it deduped.
        assertThat(guard.tryBegin("id-1")).isFalse();
    }

    @Test
    void inMemoryRejectsNonPositiveMaxSize() {
        // Boundary: 0 is rejected, 1 is accepted. Kills the ConditionalsBoundary mutant on `< 1`.
        assertThatThrownBy(() -> new InMemoryIdempotencyGuard(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSize");
        assertThatThrownBy(() -> new InMemoryIdempotencyGuard(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new InMemoryIdempotencyGuard(1).tryBegin("ok")).isTrue(); // maxSize=1 is valid
    }

    @Test
    @SuppressWarnings("unchecked")
    void inMemoryCapsSizeByEvictingLeastRecentlySeen() {
        // A flood of unique ids must not grow the map past the cap: the least-recently-seen id is
        // evicted. With maxSize=2, adding a 3rd distinct id drops the eldest ("a").
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard(2);
        Map<String, Instant> seen = (Map<String, Instant>) ReflectionTestUtils.getField(guard, "seen");

        assertThat(guard.tryBegin("a")).isTrue();
        assertThat(guard.tryBegin("b")).isTrue();
        assertThat(guard.tryBegin("c")).isTrue(); // eviction: "a" (eldest) is dropped

        assertThat(seen).hasSize(2).containsKeys("b", "c").doesNotContainKey("a");
        // "a" was evicted, so it proceeds as new again. This also pins the exact cap: under a mutant
        // that never evicts, the map would hold 3 and "a" would be skipped as a duplicate here.
        assertThat(guard.tryBegin("a")).isTrue();
    }
}
