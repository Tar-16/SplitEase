package com.example.splitease.repository;

import com.example.splitease.model.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Async operations. Supply a serial callback executor; missing records report onError. */
public interface GroupRepository {
    /** Atomic creation. Actor must be creator/admin; duplicate selections are rejected. */
    void createGroup(String actorId, Group group, List<String> memberIds, Executor executor, RepositoryCallback<Group> callback);
    /** Exact normalized username lookup; only public identity is returned, never account metadata. */
    void findMemberByUsername(String actorId, String username, Executor executor, RepositoryCallback<MemberIdentity> callback);
    ObservableQuery<List<GroupDetails>> observeGroupSummaries(String actorId);
    /** Membership is checked on every emission, including after departures. */
    ObservableQuery<GroupDetails> observeGroupDetails(String actorId, String groupId);
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
