package com.example.splitease.repository;

import com.example.splitease.model.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Async operations. Supply a serial callback executor; missing records report onError. */
public interface ExpenseRepository {
    void saveExpense(String actorId, Expense expense, Executor executor, RepositoryCallback<Expense> callback);
    void deleteExpense(String actorId, String expenseId, Executor executor, RepositoryCallback<Boolean> callback);
    ObservableQuery<List<Expense>> observeExpenses(String groupId);
}
