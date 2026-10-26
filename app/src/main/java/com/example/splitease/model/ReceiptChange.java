package com.example.splitease.model;
import com.example.splitease.domain.Rules;
/** Receipt mutation committed atomically with an expense, including new expenses. */
public final class ReceiptChange {
 public enum Kind { KEEP, REPLACE, REMOVE }
 public final Kind kind; public final String draftId;
 private ReceiptChange(Kind kind,String draftId){this.kind=kind;this.draftId=draftId;}
 public static ReceiptChange keep(){return new ReceiptChange(Kind.KEEP,null);}
 public static ReceiptChange remove(){return new ReceiptChange(Kind.REMOVE,null);}
 public static ReceiptChange replace(String draftId){return new ReceiptChange(Kind.REPLACE,Rules.id(draftId));}
}
