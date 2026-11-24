package com.example.splitease.model;
/** Stable logical identity. No file paths, provider URIs or backend SDK types.
 * amountPaise is the optional user-confirmed total printed on this bill (checked integer paise). */
public final class ReceiptMetadata {
 public final String id,groupId,expenseId,mimeType;
 public final int width,height; public final long byteCount,createdAt;
 public final Long amountPaise;
 public ReceiptMetadata(String id,String groupId,String expenseId,int width,int height,long byteCount,long createdAt){this(id,groupId,expenseId,width,height,byteCount,createdAt,null);}
 public ReceiptMetadata(String id,String groupId,String expenseId,int width,int height,long byteCount,long createdAt,Long amountPaise){this.id=id;this.groupId=groupId;this.expenseId=expenseId;this.width=width;this.height=height;this.byteCount=byteCount;this.createdAt=createdAt;this.amountPaise=amountPaise;mimeType="image/jpeg";}
}
