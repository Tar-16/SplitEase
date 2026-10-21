package com.example.splitease.repository;

import com.example.splitease.model.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Async operations. Supply a serial callback executor; missing records report onError. */
public interface RecurringBillRepository {
    void saveBill(String actorId, RecurringBill bill, Executor executor, RepositoryCallback<RecurringBill> callback);
    void deleteBill(String actorId, String billId, Executor executor, RepositoryCallback<Boolean> callback);
    void createCycle(String actorId, String billId, int year, int month, Executor executor, RepositoryCallback<RecurringBillCycle> callback);
    void setContributionPaid(String actorId, String cycleId, String userId, boolean paid, Executor executor, RepositoryCallback<Boolean> callback);
    ObservableQuery<List<RecurringBill>> observeBills(String groupId);
    ObservableQuery<List<RecurringBillCycle>> observeCycles(String billId);
}
