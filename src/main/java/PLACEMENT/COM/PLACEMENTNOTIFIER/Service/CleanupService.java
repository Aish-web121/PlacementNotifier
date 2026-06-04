package PLACEMENT.COM.PLACEMENTNOTIFIER.Service;

import PLACEMENT.COM.PLACEMENTNOTIFIER.Repository.ProcessedEmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class CleanupService {

    private final ProcessedEmailRepository repository;

    // Deletes all records older than 8 days
    // Called by CleanupScheduler every midnight
    public void deleteOldRecords() {

        // cutoff = right now minus 8 days
        // anything processed BEFORE this datetime gets deleted
        LocalDateTime cutoff = LocalDateTime.now().minusDays(8);

        log.info("Cleanup cutoff date: {}", cutoff);

        // count before so we can log how many were removed
        long countBefore = repository.count();

        repository.deleteOlderThan(cutoff);

        long countAfter = repository.count();
        long deleted = countBefore - countAfter;

        log.info("Deleted {} records older than 8 days. {} records remaining.",
                deleted, countAfter);
    }
}