package com.example.splitease.repository;

import com.example.splitease.model.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Async operations. Supply a serial callback executor; missing records report onError. */
public interface UserRepository {
    void createUser(User user, char[] password, Executor executor, RepositoryCallback<User> callback);
    void updateUser(User user, Executor executor, RepositoryCallback<User> callback);
    void deleteUser(String id, Executor executor, RepositoryCallback<Boolean> callback);
    void findUserByEmail(String email, Executor executor, RepositoryCallback<User> callback);
    void getUser(String id, Executor executor, RepositoryCallback<User> callback);
    void verifyPassword(String id, char[] password, Executor executor, RepositoryCallback<Boolean> callback);
}
