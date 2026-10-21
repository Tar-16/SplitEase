package com.example.splitease.repository;

/**
 * One-shot asynchronous result. Implementations must perform I/O off the caller thread
 * and deliver exactly one result on the executor supplied to the repository operation.
 * Neither results nor errors may be null. UI consumers must respect their view lifecycle.
 */
public interface RepositoryCallback<T> {
    void onSuccess(T value);
    void onError(Exception error);
}
