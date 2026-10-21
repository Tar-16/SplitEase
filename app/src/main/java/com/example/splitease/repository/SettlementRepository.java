package com.example.splitease.repository;

import com.example.splitease.model.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Async operations. Supply a serial callback executor; missing records report onError. */
public interface SettlementRepository {
    void saveSettlement(String actorId, Settlement settlement, Executor executor, RepositoryCallback<Settlement> callback);
    void deleteSettlement(String actorId, String settlementId, Executor executor, RepositoryCallback<Boolean> callback);
    ObservableQuery<List<Settlement>> observeSettlements(String groupId);
}
