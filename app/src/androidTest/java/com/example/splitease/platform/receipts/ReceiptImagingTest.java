package com.example.splitease.platform.receipts;
import android.content.Context;
import android.graphics.*;
import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.splitease.domain.ReceiptTotalDetector;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReceiptImagingTest {
 public static byte[] receipt(String total,int height)throws IOException {
  Bitmap bitmap=Bitmap.createBitmap(1000,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTextSize(42);paint.setTypeface(Typeface.MONOSPACE);
  canvas.drawText("SPLITEASE TEST STORE",40,70,paint);canvas.drawText("INR RECEIPT",40,130,paint);
  for(int y=210;y<height-300;y+=80)canvas.drawText("Item 1                 50.00",40,y,paint);
  canvas.drawText("SUB TOTAL             1500.00",40,height-240,paint);canvas.drawText("GRAND TOTAL           "+total,40,height-160,paint);canvas.drawText("CASH TENDERED         2000.00",40,height-80,paint);
  try(ByteArrayOutputStream out=new ByteArrayOutputStream()){assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,95,out));return out.toByteArray();}finally{bitmap.recycle();}
 }
 private File source(byte[] jpeg)throws IOException{Context context=ApplicationProvider.getApplicationContext();File file=File.createTempFile("receipt-test-",".jpg",context.getCacheDir());try(FileOutputStream out=new FileOutputStream(file)){out.write(jpeg);}return file;}
 @Test public void longReceiptKeepsWidthAspectAndLegibleBottomTotalThroughRealOcr()throws Exception {
  File raw=source(receipt("1234.56",8000));
  try{ReceiptImaging.Normalized normalized=ReceiptImaging.normalize(raw);assertEquals(1000,normalized.width);assertEquals(8000,normalized.height);assertTrue(normalized.jpeg.length<=ReceiptImaging.MAX_BYTES);
   ReceiptCaptureService.Scan result=ReceiptCaptureService.recognize(normalized.jpeg);assertFalse(result.ocrFailed);assertEquals(ReceiptTotalDetector.Status.SUGGESTED,result.detection.status);assertEquals(Long.valueOf(123456),result.detection.amountPaise);result.preview.recycle();
   try(InputStream input=new ByteArrayInputStream(normalized.jpeg)){ExifInterface exif=new ExifInterface(input);assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE));int orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,1);assertTrue(orientation==1||orientation==0);}
  }finally{raw.delete();}
 }
 @Test public void cameraRotationAndMirroredExifBecomeUprightRaster()throws Exception {
  Bitmap image=Bitmap.createBitmap(400,800,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.WHITE);Paint paint=new Paint();paint.setColor(Color.RED);canvas.drawRect(0,0,100,100,paint);paint.setColor(Color.BLUE);canvas.drawRect(300,700,400,800,paint);
  byte[] jpeg;try(ByteArrayOutputStream out=new ByteArrayOutputStream()){image.compress(Bitmap.CompressFormat.JPEG,95,out);jpeg=out.toByteArray();}finally{image.recycle();}
  int[] orientations={1,2,3,4,5,6,7,8};
  int[][] red={{50,50},{350,50},{350,750},{50,750},{50,50},{750,50},{750,350},{50,350}};
  for(int index=0;index<orientations.length;index++){File raw=source(jpeg);try{ExifInterface exif=new ExifInterface(raw);exif.setAttribute(ExifInterface.TAG_ORIENTATION,String.valueOf(orientations[index]));exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE,"12/1,30/1,0/1");exif.saveAttributes();ReceiptImaging.Normalized value=ReceiptImaging.normalize(raw);boolean sideways=orientations[index]>=5;assertEquals(sideways?800:400,value.width);assertEquals(sideways?400:800,value.height);Bitmap upright=ReceiptImaging.decode(value.jpeg,false);int color=upright.getPixel(red[index][0],red[index][1]);assertTrue("red corner for EXIF "+orientations[index],Color.red(color)>200&&Color.blue(color)<60);upright.recycle();}finally{raw.delete();}}
 }
 @Test public void invalidOrUnreadablyExtremeImageFailsInsteadOfSavingThumbnail()throws Exception {
  File invalid=source(new byte[]{1,2,3});try{org.junit.Assert.assertThrows(IOException.class,()->ReceiptImaging.normalize(invalid));}finally{invalid.delete();}
  Bitmap extreme=Bitmap.createBitmap(800,24000,Bitmap.Config.ARGB_8888);extreme.eraseColor(Color.WHITE);byte[] jpeg;try(ByteArrayOutputStream out=new ByteArrayOutputStream()){extreme.compress(Bitmap.CompressFormat.JPEG,90,out);jpeg=out.toByteArray();}finally{extreme.recycle();}File raw=source(jpeg);try{org.junit.Assert.assertThrows(IOException.class,()->ReceiptImaging.normalize(raw));}finally{raw.delete();}
 }
}
