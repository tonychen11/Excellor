package org.example;
import java.time.Duration;
import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

public class SlidingWindowRateLimiter {
    private final Queue<Instant> requestTimestamps = new ConcurrentLinkedQueue<>();
    private final int maxRequestsPerMinute;
    private final Duration windowSize;
    private final ReentrantLock lock = new ReentrantLock();

    public SlidingWindowRateLimiter(int maxRequestsPerMinute, Duration windowSize) {
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.windowSize = windowSize;
    }

    public boolean acquirePermission() {
        lock.lock();
        try {
            Instant now = Instant.now();
            // Remove timestamps older than our window
            while (!requestTimestamps.isEmpty() &&
                    Duration.between(requestTimestamps.peek(), now).compareTo(windowSize) > 0) {
                requestTimestamps.poll();
            }

            // Check if we've hit the limit
            if (requestTimestamps.size() < maxRequestsPerMinute) {
                requestTimestamps.add(now);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    public void waitForPermission() throws InterruptedException {
        while (!acquirePermission()) {
            Thread.sleep(100); // Poll every 100ms
        }
    }
}