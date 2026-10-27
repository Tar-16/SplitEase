package com.example.splitease.repository;

import com.example.splitease.model.*;
import java.util.concurrent.Executor;

/** Actor comes from authenticated cloud scope, never from caller input. No credential operations.
 * Calls are async; account changes cancel delivery. Supply a serial callback executor.
 * Creation is idempotent for an already complete owner. Username changes/deletion are unsupported.
 */
public interface CloudProfileRepository {
    void createOwnProfile(String username,Executor executor,RepositoryCallback<CloudProfile> callback);
    void getOwnProfile(Executor executor,RepositoryCallback<CloudProfile> callback);
    void findByUsername(String username,Executor executor,RepositoryCallback<MemberIdentity> callback);
    void updateOwnProfile(String displayUsername,String upiId,Executor executor,RepositoryCallback<CloudProfile> callback);
    ObservableQuery<CloudProfile> observeOwnProfile();
}
