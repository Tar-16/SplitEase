package com.example.splitease.data;
import android.content.Context;
import android.graphics.BitmapFactory;
import com.example.splitease.domain.Rules;
import java.io.*;
import java.util.*;

/** Private immutable file objects. Only called on the repository worker. */
final class ReceiptFiles {
 static final int MAX_BYTES=4*1024*1024;
 private final File directory;
 ReceiptFiles(Context context,String namespace){directory=new File(context.getNoBackupFilesDir(),"receipts-"+UUID.nameUUIDFromBytes(namespace.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
 File file(String id){return new File(directory,Rules.id(id)+".jpg");}
 int[] dimensions(byte[] bytes)throws IOException {
  if(bytes==null||bytes.length<4||bytes.length>MAX_BYTES||bytes[0]!=(byte)0xff||bytes[1]!=(byte)0xd8||bytes[bytes.length-2]!=(byte)0xff||bytes[bytes.length-1]!=(byte)0xd9)throw new IOException("Expected normalized JPEG");
  BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
  if(options.outWidth<1||options.outHeight<1||(long)options.outWidth*options.outHeight>16000000L||Math.max(options.outWidth,options.outHeight)>16000)throw new IOException("Invalid image dimensions");
  try(InputStream in=new ByteArrayInputStream(bytes)){androidx.exifinterface.media.ExifInterface exif=new androidx.exifinterface.media.ExifInterface(in);int orientation=exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1);if(orientation!=1&&orientation!=0)throw new IOException("Image must be upright");}
  return new int[]{options.outWidth,options.outHeight};
 }
 void write(String id,byte[] bytes)throws IOException {
  if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Receipt storage unavailable");
  File partial=new File(directory,Rules.id(id)+".partial"),target=file(id);
  try {try(FileOutputStream out=new FileOutputStream(partial)){out.write(bytes);out.flush();out.getFD().sync();}if(!partial.renameTo(target))throw new IOException("Receipt publish failed");
   try{java.io.FileDescriptor directoryFd=android.system.Os.open(directory.getPath(),android.system.OsConstants.O_RDONLY,0);try{android.system.Os.fsync(directoryFd);}finally{android.system.Os.close(directoryFd);}}catch(android.system.ErrnoException error){throw new IOException("Receipt sync failed",error);}}
  finally {if(partial.exists())partial.delete();}
 }
 byte[] read(String id)throws IOException {
  File file=file(id);long length=file.length();if(!file.isFile()||length<1||length>MAX_BYTES)throw new FileNotFoundException("Receipt unavailable");
  try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>MAX_BYTES)throw new IOException("Receipt too large");}return out.toByteArray();}
 }
 void reconcile(Set<String> live)throws IOException {
  File[] files=directory.listFiles();if(files==null){if(directory.exists())throw new IOException("Receipt cleanup unavailable");return;}
  for(File file:files){String name=file.getName();boolean keep=name.endsWith(".jpg")&&live.contains(name.substring(0,name.length()-4));if(!keep&&!file.delete())throw new IOException("Receipt cleanup deferred");}
 }
}
