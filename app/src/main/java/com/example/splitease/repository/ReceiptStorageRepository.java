package com.example.splitease.repository;
import com.example.splitease.model.*;
import java.util.*;
import java.util.concurrent.Executor;
/** Backend-independent asynchronous receipt operations. Actors come from AuthService.
 * stage accepts an upright, normalized JPEG. Drafts are private to their creator/current membership.
 * ExpenseRepository.saveExpense(..., ReceiptChange, ...) atomically publishes/replaces/removes a link.
 * No visible reference is published before a complete durable image exists. */
public interface ReceiptStorageRepository {
 void stage(String actorId,String groupId,byte[] normalizedJpeg,Executor executor,RepositoryCallback<ReceiptMetadata> callback);
 void load(String actorId,String receiptId,Executor executor,RepositoryCallback<ReceiptImage> callback);
 void findForExpense(String actorId,String expenseId,Executor executor,RepositoryCallback<Optional<ReceiptMetadata>> callback);
 /** Every bill linked to the expense, oldest first. Adapters that support one bill per expense inherit this default. */
 default void findAllForExpense(String actorId,String expenseId,Executor executor,RepositoryCallback<List<ReceiptMetadata>> callback){
  findForExpense(actorId,expenseId,executor,new RepositoryCallback<Optional<ReceiptMetadata>>(){
   public void onSuccess(Optional<ReceiptMetadata> value){callback.onSuccess(value.isPresent()?Collections.singletonList(value.get()):Collections.<ReceiptMetadata>emptyList());}
   public void onError(Exception error){callback.onError(error);}
  });
 }
 void discardDraft(String actorId,String receiptId,Executor executor,RepositoryCallback<Boolean> callback);
 void cleanUp(Executor executor,RepositoryCallback<Boolean> callback);
}
