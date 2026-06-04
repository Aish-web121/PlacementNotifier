package PLACEMENT.COM.PLACEMENTNOTIFIER.Scheduler;

import PLACEMENT.COM.PLACEMENTNOTIFIER.Service.NotificationOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

// @Component tells Spring: "manage this class"
// Like @Service but for non-service classes
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailPollingScheduler {

    // The orchestrator that does all the real work
    private final NotificationOrchestrator orchestrator;

    // Read from application.properties
    // scheduler.enabled=true
    // You can set this to false to pause the scheduler
    // without redeploying the app
    @Value("${scheduler.enabled:true}")
    private boolean schedulerEnabled;

    // ─────────────────────────────────────────────
    // AtomicBoolean = a boolean that is THREAD SAFE
    //
    // Why do we need this?
    //
    // Imagine poll #1 starts at 12:00
    // Gmail is slow, takes 4 minutes to respond
    // Poll #2 starts at 12:03 (scheduler doesn't wait)
    // Now TWO polls are running at the same time
    // Both find the same email
    // Both try to send Telegram notification
    // DUPLICATE notification sent 😱
    //
    // AtomicBoolean prevents this:
    // Poll #1 sets running = true
    // Poll #2 sees running = true → skips itself
    // Poll #1 finishes → sets running = false
    // Poll #3 starts normally
    // ─────────────────────────────────────────────
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ─────────────────────────────────────────────
    // @Scheduled = run this method automatically
    //
    // cron = "0 */3 * * * *" means:
    // ┌─ second (0)
    // │  ┌─ minute (every 3rd: 0,3,6,9,12...)
    // │  │    ┌─ hour (every hour)
    // │  │    │  ┌─ day (every day)
    // │  │    │  │  ┌─ month (every month)
    // │  │    │  │  │  ┌─ weekday (every day)
    // 0  */3  *  *  *  *
    //
    // So runs at:
    // 12:00:00, 12:03:00, 12:06:00, 12:09:00...
    // forever, automatically
    // ─────────────────────────────────────────────
    @Scheduled(cron = "${scheduler.gmail.cron}")
    public void pollGmail() {

        // Check if scheduler is enabled
        // If you set scheduler.enabled=false in properties
        // the method returns immediately without doing anything
        if (!schedulerEnabled) {
            log.debug("Scheduler is disabled. Skipping poll.");
            return;
        }

        // ── OVERLAP PREVENTION ──
        // compareAndSet(false, true) means:
        // "if current value is false, set it to true and return true"
        // "if current value is true, do nothing and return false"
        // This is ATOMIC — thread safe, no race conditions
        if (!running.compareAndSet(false, true)) {
            log.warn("Previous poll still running. Skipping this cycle.");
            return;
        }

        // Record start time for logging
        long startTime = System.currentTimeMillis();

        try {
            log.info("⏰ Scheduler triggered — starting Gmail poll...");

            // This is the ONLY line that does real work
            // Everything else in this file is safety/logging
            orchestrator.processNewEmails();

        } catch (Exception e) {
            // ── CRITICAL ──
            // This catch block is the most important part
            // of this entire file.
            //
            // If processNewEmails() throws ANY exception
            // and we don't catch it here,
            // Spring's scheduler DIES permanently.
            // Your app keeps running but never polls again.
            // You won't even know until you check logs.
            //
            // By catching here, we log the error
            // and the scheduler stays alive for next cycle.
            log.error("Unexpected error in scheduler (app stays alive): {}",
                    e.getMessage(), e);

        } finally {
            // ── ALWAYS RUNS ──
            // Whether success or exception,
            // this block ALWAYS executes.
            // It resets running = false
            // so next cycle can start.
            //
            // If we didn't have finally:
            // Exception occurs → running stays true forever
            // No more polls ever run
            // App is broken silently
            running.set(false);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("⏱️ Poll cycle finished in {}ms", elapsed);
        }
    }
}