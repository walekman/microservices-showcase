package com.showcase.transfer.service;

import com.showcase.transfer.client.AccountClient;
import com.showcase.transfer.client.AccountRejectedException;
import com.showcase.transfer.client.AccountServiceUnavailableException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Automatically resolves the ambiguity Phase 2 deliberately left open: COMPENSATION_REQUIRED
 * transfers (drainCompensationRequired, this task) and stale PENDING transfers
 * (sweepStalePending, the next task) are periodically reconciled against Account Service by
 * replaying the ambiguous call with its original idempotency key rather than guessing -- see
 * docs/phase-3-resilience-compensation-idempotency.md's Design Decisions.
 *
 * <p>Registered via {@link SchedulingConfigurer} rather than
 * {@code @Scheduled(fixedDelayString = ...)}: {@code @Scheduled}'s string form parses with
 * {@link java.time.Duration#parse}, which requires strict ISO-8601 ("PT15S"), not the "15s"
 * shorthand this project's application.yml files use everywhere else. Registering the interval
 * from {@link CompensationProperties} as milliseconds sidesteps that mismatch entirely.
 */
@Component
public class CompensationScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CompensationScheduler.class);

    private final TransferRepository transferRepository;
    private final AccountClient accountClient;
    private final CompensationProperties properties;

    public CompensationScheduler(TransferRepository transferRepository, AccountClient accountClient,
                                  CompensationProperties properties) {
        this.transferRepository = transferRepository;
        this.accountClient = accountClient;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::drainCompensationRequired, properties.sweepInterval().toMillis());
    }

    // Package-private so CompensationSchedulerTest can invoke it directly, without going
    // through the scheduler registration machinery.
    void drainCompensationRequired() {
        List<Transfer> stranded = transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED);
        for (Transfer transfer : stranded) {
            try {
                reconcileCredit(transfer);
            } catch (RuntimeException unexpected) {
                // A save() failure (an optimistic-lock conflict, a transient Postgres blip)
                // would otherwise propagate out of this loop and silently skip every other
                // stranded transfer in this batch until the next sweep. Isolating it here is
                // a delay, not data loss: the row is still COMPENSATION_REQUIRED, so the next
                // sweep picks it up again, and the credit call itself is idempotent either way.
                log.error("Transfer {} unexpected failure during reconciliation, will retry next sweep",
                        transfer.getId(), unexpected);
            }
        }
    }

    private void reconcileCredit(Transfer transfer) {
        try {
            accountClient.credit(transfer.getToAccountId(), transfer.getAmount(), transfer.getId() + ":credit");
            // The credit actually landed -- Transfer just did not know it yet. Nothing to
            // reverse; this transfer genuinely completed.
            transfer.markCompleted();
            transferRepository.save(transfer);
            log.info("Transfer {} reconciled as COMPLETED: the credit had already landed", transfer.getId());
        } catch (AccountRejectedException definitivelyRejected) {
            compensateSource(transfer, definitivelyRejected.getDetail());
        } catch (AccountServiceUnavailableException stillUnavailable) {
            log.error("Transfer {} still cannot reconcile the credit leg, will retry next sweep: {}",
                    transfer.getId(), stillUnavailable.getMessage());
        }
    }

    private void compensateSource(Transfer transfer, String rejectionDetail) {
        try {
            accountClient.credit(transfer.getFromAccountId(), transfer.getAmount(), transfer.getId() + ":compensate");
            transfer.markCompensated();
            transferRepository.save(transfer);
            log.info("Transfer {} COMPENSATED: source credited back after destination definitively rejected [{}]",
                    transfer.getId(), rejectionDetail);
        } catch (AccountRejectedException sourceAlsoRejected) {
            transfer.markCompensationFailed(
                    "Destination rejected [%s], and crediting the source back also failed [%s] -- manual review required"
                            .formatted(rejectionDetail, sourceAlsoRejected.getDetail()));
            transferRepository.save(transfer);
            log.error("Transfer {} COMPENSATION_FAILED: manual review required. Destination [{}], source credit-back [{}]",
                    transfer.getId(), rejectionDetail, sourceAlsoRejected.getDetail());
        } catch (AccountServiceUnavailableException stillUnavailable) {
            log.error("Transfer {} destination rejected [{}] but crediting the source back is still unavailable, "
                            + "will retry next sweep: {}",
                    transfer.getId(), rejectionDetail, stillUnavailable.getMessage());
        }
    }
}
