package PLACEMENT.COM.PLACEMENTNOTIFIER.Scheduler;

import PLACEMENT.COM.PLACEMENTNOTIFIER.Service.CleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CleanupScheduler {

    private final CleanupService cleanupService;

    // Runs every day at midnight
    // Cron: "0 0 0 * * *"
    //        │ │ │
    //        │ │ └── hour 0 (midnight)
    //        │ └──── minute 0
    //        └────── second 0



//    @Scheduled(cron = "0/30 * * * * *")
    @Scheduled(cron = "0 0 0 * * *")
    public void runCleanup() {
        try {
            log.info("Starting daily cleanup job...");
            cleanupService.deleteOldRecords();
            log.info("Daily cleanup job completed.");
        } catch (Exception e) {
            log.error("Cleanup job failed: {}", e.getMessage(), e);
        }
    }
}