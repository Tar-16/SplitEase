/**
 * Storage-independent asynchronous feature contracts. One-shot operations use an explicit
 * serial callback Executor and RepositoryCallback; live reads use ObservableQuery.
 * Callback executors must remain accepting until completion. Missing records report errors.
 * Actor IDs must come from the future trusted session layer. Never expose SQLite cursors,
 * Firebase tasks, Activity, Fragment or Context through repository APIs.
 */
package com.example.splitease.repository;
