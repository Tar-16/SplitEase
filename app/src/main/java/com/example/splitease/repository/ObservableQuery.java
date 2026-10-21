package com.example.splitease.repository;

import java.util.concurrent.Executor;

/**
 * Storage-independent stream for repository reads requiring live updates.
 * Subscribe returns promptly; loading and I/O never run on the caller thread.
 * Deliver an initial snapshot, then ordered updates on the supplied serial executor.
 * Errors terminate the subscription. Values and errors are non-null.
 * Future adapters must suppress queued notifications after close().
 */
public interface ObservableQuery<T> {
    Subscription subscribe(Executor callbackExecutor, Observer<T> observer);

    interface Observer<T> {
        void onChanged(T value);
        void onError(Exception error);
    }

    /** Idempotent, nonblocking cancellation. UI owners close when their lifecycle ends. */
    interface Subscription extends AutoCloseable {
        @Override void close();
    }
}
