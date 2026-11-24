package com.example.splitease.model;
import com.example.splitease.domain.Rules;
import java.util.*;
/** Receipt mutation committed atomically with an expense, including new expenses.
 * KEEP/REPLACE/REMOVE are the single-attachment forms. EDIT adds, removes and re-amounts several bills at once. */
public final class ReceiptChange {
 public enum Kind { KEEP, REPLACE, REMOVE, EDIT }
 /** A staged draft to link, with its optional confirmed amount. */
 public static final class Addition {
  public final String draftId; public final Long amountPaise;
  public Addition(String draftId,Long amountPaise){this.draftId=Rules.id(draftId);if(amountPaise!=null)Rules.positive(amountPaise);this.amountPaise=amountPaise;}
 }
 /** Upper bound per expense; enforced inside the financial transaction. */
 public static final int MAX_PER_EXPENSE=10;
 public final Kind kind; public final String draftId;
 public final List<Addition> additions; public final Set<String> removals; public final Map<String,Long> amounts;
 private ReceiptChange(Kind kind,String draftId,List<Addition> additions,Set<String> removals,Map<String,Long> amounts){this.kind=kind;this.draftId=draftId;this.additions=Collections.unmodifiableList(new ArrayList<>(additions));this.removals=Collections.unmodifiableSet(new LinkedHashSet<>(removals));this.amounts=Collections.unmodifiableMap(new LinkedHashMap<>(amounts));}
 public static ReceiptChange keep(){return new ReceiptChange(Kind.KEEP,null,Collections.emptyList(),Collections.emptySet(),Collections.emptyMap());}
 public static ReceiptChange remove(){return new ReceiptChange(Kind.REMOVE,null,Collections.emptyList(),Collections.emptySet(),Collections.emptyMap());}
 public static ReceiptChange replace(String draftId){return new ReceiptChange(Kind.REPLACE,Rules.id(draftId),Collections.emptyList(),Collections.emptySet(),Collections.emptyMap());}
 /** removals: linked receipt IDs to unlink; amounts: linked receipt ID to new amount (null clears it). */
 public static ReceiptChange edit(Collection<Addition> additions,Collection<String> removals,Map<String,Long> amounts){
  Set<String> removed=new LinkedHashSet<>();for(String id:removals)removed.add(Rules.id(id));
  Map<String,Long> updates=new LinkedHashMap<>();
  for(Map.Entry<String,Long> entry:amounts.entrySet()){Rules.id(entry.getKey());if(removed.contains(entry.getKey()))continue;if(entry.getValue()!=null)Rules.positive(entry.getValue());updates.put(entry.getKey(),entry.getValue());}
  Set<String> drafts=new HashSet<>();for(Addition addition:additions)if(!drafts.add(addition.draftId))throw new IllegalArgumentException("Duplicate draft");
  return new ReceiptChange(Kind.EDIT,null,new ArrayList<>(additions),removed,updates);
 }
}
