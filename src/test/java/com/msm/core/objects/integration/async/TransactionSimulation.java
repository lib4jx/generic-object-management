package com.msm.core.objects.integration.async;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** A transaction of the calling thread, as {@code @Transactional} opens it: synchronizations run on commit only. */
final class TransactionSimulation {

    private TransactionSimulation() {}

    static void begin() {
        TransactionSynchronizationManager.initSynchronization();
    }

    static void commit() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    }

    static void rollback() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    }

    static void end() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
