package com.example.splitease.platform.receipts;

import android.graphics.*;
import androidx.exifinterface.media.ExifInterface;
import java.io.*;

/** Bounded decoding, EXIF normalization and legibility-first JPEG compression. Worker thread only. */
public final class ReceiptImaging {
 public static final int MAX_BYTES=4*1024*1024;
 public static final class Normalized {
  public final byte[] jpeg;public final int width,height;
  Normalized(byte[] jpeg,int width,int height){this.jpeg=jpeg;this.width=width;this.height=height;}
 }
 private ReceiptImaging(){}
 public static Normalized normalize(File file)throws IOException {
  if(file.length()<1||file.length()>25L*1024*1024)throw new IOException("Capture size unsupported");
  BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
  if(bounds.outWidth<1||bounds.outHeight<1||(long)bounds.outWidth*bounds.outHeight>150000000L)throw new IOException("Invalid capture");
  int sample=1;while((long)(bounds.outWidth/sample)*(bounds.outHeight/sample)>12000000L)sample*=2;
  BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
  Bitmap source=BitmapFactory.decodeFile(file.getPath(),options);if(source==null)throw new IOException("Cannot decode capture");
  Bitmap upright=null,scaled=null;
  try {
   ExifInterface exif=new ExifInterface(file);Matrix matrix=new Matrix();
   switch(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)){
    case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:matrix.setScale(-1,1);break;
    case ExifInterface.ORIENTATION_ROTATE_180:matrix.setRotate(180);break;
    case ExifInterface.ORIENTATION_FLIP_VERTICAL:matrix.setScale(1,-1);break;
    case ExifInterface.ORIENTATION_TRANSPOSE:matrix.setRotate(90);matrix.postScale(-1,1);break;
    case ExifInterface.ORIENTATION_ROTATE_90:matrix.setRotate(90);break;
    case ExifInterface.ORIENTATION_TRANSVERSE:matrix.setRotate(-90);matrix.postScale(-1,1);break;
    case ExifInterface.ORIENTATION_ROTATE_270:matrix.setRotate(-90);break;
    default:break;
   }
   upright=Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),matrix,true);
   if(upright!=source)source.recycle();
   int width=upright.getWidth(),height=upright.getHeight();
   double factor=Math.min(1d,Math.min(1600d/Math.min(width,height),Math.min(16000d/Math.max(width,height),Math.sqrt(12000000d/((long)width*height)))));
   int w=Math.max(1,(int)Math.round(width*factor)),h=Math.max(1,(int)Math.round(height*factor));
   if(Math.min(bounds.outWidth,bounds.outHeight)>=640 && Math.min(w,h)<640)throw new IOException("Capture too long for readable storage");
   scaled=Bitmap.createScaledBitmap(upright,w,h,true);
   if(scaled!=upright)upright.recycle();
   byte[] encoded=null;
   for(int quality:new int[]{88,82,76}){try(ByteArrayOutputStream out=new ByteArrayOutputStream()){if(!scaled.compress(Bitmap.CompressFormat.JPEG,quality,out))throw new IOException("Cannot encode receipt");encoded=out.toByteArray();}if(encoded.length<=MAX_BYTES)break;}
   if(encoded.length>MAX_BYTES)throw new IOException("Readable receipt exceeds size limit");
   return new Normalized(encoded,w,h);
  }finally{if(scaled!=null&&scaled!=upright&&scaled!=source)scaled.recycle();if(upright!=null&&upright!=source)upright.recycle();source.recycle();}
 }
 public static Bitmap decode(byte[] jpeg,boolean preview)throws IOException {
  BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,bounds);
  if(bounds.outWidth<1||bounds.outHeight<1||(long)bounds.outWidth*bounds.outHeight>16000000L)throw new IOException("Invalid receipt");
  BitmapFactory.Options options=new BitmapFactory.Options();int sample=1;if(preview)while((long)(bounds.outWidth/sample)*(bounds.outHeight/sample)>1500000L)sample*=2;options.inSampleSize=sample;
  Bitmap result=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,options);if(result==null)throw new IOException("Receipt unavailable");return result;
 }
}
