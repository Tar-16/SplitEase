package com.example.splitease.repository;

import com.example.splitease.model.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Async operations. Supply a serial callback executor; missing records report onError. */
public interface GroupRepository {
    void createGroup(Group group, Executor executor, RepositoryCallback<Group> callback);
    void updateGroup(String actorId, Group group, Executor executor, RepositoryCallback<Group> callback);
    void deleteGroup(String actorId, String groupId, Executor executor, RepositoryCallback<Boolean> callback);
    void addMember(String actorId, String groupId, String userId, long joinedAt, Executor executor, RepositoryCallback<Boolean> callback);
    void leaveGroup(String userId, String groupId, long leftAt, Executor executor, RepositoryCallback<Boolean> callback);
    void transferAdmin(String actorId, String groupId, String newAdminId, Executor executor, RepositoryCallback<Boolean> callback);
    ObservableQuery<List<Group>> observeGroups(String userId);
    ObservableQuery<List<GroupMember>> observeMembers(String groupId);
    ObservableQuery<Map<String, Long>> observeBalances(String groupId);
}
