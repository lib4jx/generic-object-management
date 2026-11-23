package com.msm.core.objects.integration.async;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs work once the current transaction committed: never on rollback; at once when no transaction is active. */
class AfterCommit {

    void run(Runnable work) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
            return;
        }
        work.run();
    }
}
