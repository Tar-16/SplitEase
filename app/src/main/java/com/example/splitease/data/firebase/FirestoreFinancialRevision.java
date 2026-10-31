package com.example.splitease.data.firebase;
import com.google.firebase.firestore.*;
/** Atomic fence shared by every cloud expense/settlement mutation; no stored balances. */
final class FirestoreFinancialRevision {
 private FirestoreFinancialRevision(){}
 static long value(DocumentSnapshot group){Long value=group.getLong("financialRevision");return value==null?0:value;}
 static long advance(Transaction tx,DocumentReference group,DocumentSnapshot before,String collection,String id){
  long next=Math.addExact(value(before),1);
  java.util.Map<String,Object> record=new java.util.HashMap<>();record.put("collection",collection);record.put("id",id);
  tx.update(group,"financialRevision",next,"financialRecord",record);
  return next;
 }
}
