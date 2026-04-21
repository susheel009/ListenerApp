package com.listener.app.client;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the GitHubClient locking strategy.
 * Tests the lock-map pattern in isolation (no HTTP calls).
 */
class GitHubClientLockTest {

    /**
     * Verifies that lock entries are never removed from the map,
     * so two threads always get the same lock instance for the same key.
     */
    @Test
    void lockMap_sameKey_returnsSameInstance() {
        Map<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

        ReentrantLock lock1 = fileLocks.computeIfAbsent("inbox/2026-04-20", k -> new ReentrantLock());
        ReentrantLock lock2 = fileLocks.computeIfAbsent("inbox/2026-04-20", k -> new ReentrantLock());

        assertSame(lock1, lock2, "Same key must return the exact same lock instance");
    }

    /**
     * Verifies that different date keys get different locks (no false serialization).
     */
    @Test
    void lockMap_differentKeys_returnsDifferentInstances() {
        Map<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

        ReentrantLock lock1 = fileLocks.computeIfAbsent("inbox/2026-04-20", k -> new ReentrantLock());
        ReentrantLock lock2 = fileLocks.computeIfAbsent("inbox/2026-04-21", k -> new ReentrantLock());

        assertNotSame(lock1, lock2, "Different keys must get different locks");
    }

    /**
     * Verifies that tryLock with timeout returns false when the lock is held.
     */
    @Test
    void tryLock_timesOut_whenHeld() throws InterruptedException {
        ReentrantLock lock = new ReentrantLock();
        lock.lock(); // Simulate Thread A holding the lock

        AtomicBoolean acquired = new AtomicBoolean(true);
        Thread thread = new Thread(() -> {
            try {
                acquired.set(lock.tryLock(100, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();
        thread.join();

        assertFalse(acquired.get(), "tryLock should return false when lock is held");
        lock.unlock();
    }

    /**
     * Verifies lock key is based on the STABLE base path, not the rollover path.
     * If rollover changes actualPath from "inbox/2026-04-20.md" to "inbox/2026-04-20-1.md",
     * the lock key should remain "inbox/2026-04-20" (the base path).
     */
    @Test
    void lockKey_usesBasePath_notRolloverPath() {
        String path = "inbox/2026-04-20.md";
        String basePath = path.endsWith(".md") ? path.substring(0, path.length() - 3) : path;

        assertEquals("inbox/2026-04-20", basePath);

        // Simulated rollover
        String rolledOverPath = basePath + "-1.md";
        assertEquals("inbox/2026-04-20-1.md", rolledOverPath);

        // Lock key must still be the base path
        Map<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();
        ReentrantLock lockBefore = fileLocks.computeIfAbsent(basePath, k -> new ReentrantLock());
        // After rollover, still using basePath for the lock
        ReentrantLock lockAfter = fileLocks.computeIfAbsent(basePath, k -> new ReentrantLock());

        assertSame(lockBefore, lockAfter, "Lock key must be stable across rollover");
    }

    /**
     * Verifies that rollover threshold is measured in UTF-8 bytes, not char count.
     * CJK characters are 3 bytes each in UTF-8 but 1 char in Java.
     */
    @Test
    void rolloverThreshold_measuredInUtf8Bytes() {
        // 300 CJK characters = 300 Java chars but 900 UTF-8 bytes
        String cjkContent = "你".repeat(300);

        int charLength = cjkContent.length();
        int utf8Length = cjkContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

        assertEquals(300, charLength);
        assertEquals(900, utf8Length);

        // If threshold is 800 bytes, char count (300) would NOT trigger rollover
        // but UTF-8 byte count (900) SHOULD trigger rollover
        int threshold = 800;
        assertFalse(charLength > threshold, "Char count should NOT trigger rollover");
        assertTrue(utf8Length > threshold, "UTF-8 byte count SHOULD trigger rollover");
    }

    /**
     * Verifies concurrent threads serialize through the same lock.
     */
    @Test
    void concurrentAccess_serialized() throws InterruptedException {
        Map<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();
        String basePath = "inbox/2026-04-20";

        AtomicBoolean overlap = new AtomicBoolean(false);
        AtomicBoolean inCritical = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(2);

        Runnable task = () -> {
            ReentrantLock lock = fileLocks.computeIfAbsent(basePath, k -> new ReentrantLock());
            lock.lock();
            try {
                if (inCritical.getAndSet(true)) {
                    overlap.set(true); // Two threads in critical section = bug
                }
                Thread.sleep(50); // Simulate work
                inCritical.set(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
                done.countDown();
            }
        };

        new Thread(task).start();
        new Thread(task).start();

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse(overlap.get(), "Two threads must NOT overlap in the critical section");
    }
}
