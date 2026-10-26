package com.example.splitease.model;
/** Encoded transport payload, never a SQLite BLOB or a local path. */
public final class ReceiptImage {
 public final ReceiptMetadata metadata; private final byte[] jpeg;
 public ReceiptImage(ReceiptMetadata metadata,byte[] jpeg){this.metadata=metadata;this.jpeg=jpeg.clone();}
 public byte[] bytes(){return jpeg.clone();}
}
