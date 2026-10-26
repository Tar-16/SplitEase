package com.example.splitease.model;
/** Stable logical identity. No file paths, provider URIs or backend SDK types. */
public final class ReceiptMetadata {
 public final String id,groupId,expenseId,mimeType;
 public final int width,height; public final long byteCount,createdAt;
 public ReceiptMetadata(String id,String groupId,String expenseId,int width,int height,long byteCount,long createdAt){this.id=id;this.groupId=groupId;this.expenseId=expenseId;this.width=width;this.height=height;this.byteCount=byteCount;this.createdAt=createdAt;mimeType="image/jpeg";}
}
