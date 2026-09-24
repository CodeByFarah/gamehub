package com.gamehub.integration;

import com.gamehub.api.dto.CloudSaveUploadRequest;
import com.gamehub.application.exception.CloudSaveConflictException;
import com.gamehub.application.service.CloudSaveService;
import com.gamehub.domain.common.Region;
import com.gamehub.infrastructure.persistence.entity.UserEntity;
import com.gamehub.infrastructure.persistence.entity.UserProfileEntity;
import com.gamehub.infrastructure.persistence.repository.CloudSaveRepository;
import com.gamehub.infrastructure.persistence.repository.UserProfileRepository;
import com.gamehub.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two devices, one save slot, the same version. Exactly one must win.
 *
 * <h2>Problem</h2>
 * A player has GameHub open on a phone and a tablet. Both hold version 1, both
 * finish a session, both upload.
 *
 * <h2>Race condition</h2>
 * Without a version predicate both UPDATE statements succeed. The second
 * overwrites the first, and the progress written by the first device is gone.
 * Nothing errors and nothing logs; the player simply finds work missing. This
 * is the worst class of bug: silent, data-destroying, and impossible to
 * reproduce on one device.
 *
 * <h2>Solution</h2>
 * UPDATE cloud_saves SET version = version + 1 ... WHERE id = ? AND version =
 * ?. Postgres re-evaluates the predicate against the latest committed row, so
 * the statement matches one row or zero. The loser sees zero and is told, with
 * the server current version attached so it can merge.
 *
 * <h2>Why this works</h2>
 * The check and the write are one statement. There is no window between them
 * for another transaction to slip through, which is exactly what a
 * read-then-write has and what makes it unsafe below serializable isolation.
 *
 * <h2>Tradeoffs</h2>
 * Optimistic control wins when conflicts are rare, and they are: two devices
 * writing one slot within milliseconds is unusual, so the common path takes no
 * lock at all. Pessimistic locking would also be correct but would hold a row
 * lock across the client round trip, making every writer pay for a conflict
 * that almost never happens. The cost is that the loser must retry, and the
 * client has to treat 409 as a normal outcome.
 */
@Tag("concurrency")
@DisplayName("Cloud save: concurrent writes to one slot")
class CloudSaveConcurrencyTest extends IntegrationTestBase {

    private static final UUID GAME_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final int WRITERS = 16;

    @Autowired private CloudSaveService cloudSaves;
    @Autowired private CloudSaveRepository saveRepository;
    @Autowired private UserRepository users;
    @Autowired private UserProfileRepository profiles;

    private UUID userId;

    @BeforeEach
    void createPlayer() {
        UserEntity user = new UserEntity();
        user.setUsername("saver" + UUID.randomUUID().toString().substring(0, 8));
        user.setEmail(user.getUsername() + "@example.test");
        user.setPasswordHash("not-a-real-hash");
        userId = users.saveAndFlush(user).getId();

        UserProfileEntity profile = new UserProfileEntity();
        profile.setUserId(userId);
        profile.setDisplayName("Saver");
        profile.setRegion(Region.EU_WEST);
        profiles.saveAndFlush(profile);

        cloudSaves.upload(userId, request(0L, "initial state"));
    }

    @Test
    @DisplayName("exactly one of many simultaneous writers at the same version succeeds")
    void onlyOneWriterWinsAtEachVersion() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        // A barrier, not just a thread pool. Without it the threads start
        // staggered and the first finishes before the last begins, so the race
        // never happens and the test passes vacuously.
        CyclicBarrier startLine = new CyclicBarrier(WRITERS);

        try (ExecutorService pool = Executors.newFixedThreadPool(WRITERS)) {
            List<Callable<Void>> writers = new ArrayList<>();

            for (int i = 0; i < WRITERS; i++) {
                final int index = i;
                writers.add(() -> {
                    startLine.await();
                    try {
                        // Every writer presents version 1, which they all
                        // legitimately believe is current.
                        cloudSaves.upload(userId, request(1L, "payload from device " + index));
                        accepted.incrementAndGet();
                    } catch (CloudSaveConflictException e) {
                        conflicted.incrementAndGet();
                        // The rejection must carry what the client needs in
                        // order to merge. A bare 409 forces another round
                        // trip, during which the version can move again.
                        assertThat(e.getMeta()).containsKeys("serverVersion", "serverChecksum");
                    }
                    return null;
                });
            }

            for (Future<Void> future : pool.invokeAll(writers)) {
                future.get();
            }
        }

        // The invariant. Not "at least one" and not "no exceptions": exactly
        // one writer may win, because every other outcome is a lost write.
        assertThat(accepted.get())
                .withFailMessage("expected exactly one accepted write, got %d", accepted.get())
                .isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(WRITERS - 1);

        // The winner advanced the version by exactly one. A larger jump would
        // mean a second write slipped through unnoticed.
        assertThat(saveRepository.findByUserIdAndGameIdAndSlot(userId, GAME_ID, (short) 0))
                .get()
                .satisfies(save -> assertThat(save.getVersion()).isEqualTo(2L));
    }

    @Test
    @DisplayName("a writer holding a stale version is rejected, not silently applied")
    void staleWriterIsRejected() {
        cloudSaves.upload(userId, request(1L, "second"));

        assertThatThrownBy(() -> cloudSaves.upload(userId, request(1L, "stale")))
                .isInstanceOf(CloudSaveConflictException.class)
                .satisfies(thrown -> assertThat(
                        ((CloudSaveConflictException) thrown).getMeta().get("serverVersion"))
                        .isEqualTo(2L));
    }

    private CloudSaveUploadRequest request(long expectedVersion, String content) {
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        return new CloudSaveUploadRequest(
                GAME_ID, 0, expectedVersion,
                Base64.getEncoder().encodeToString(payload),
                sha256Hex(payload),
                "device-test");
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
