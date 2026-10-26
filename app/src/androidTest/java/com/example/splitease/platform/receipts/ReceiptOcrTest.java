package com.example.splitease.platform.receipts;
import android.graphics.*;
import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.domain.ReceiptTotalDetector;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Generated images exercise ML Kit; no private photographs are bundled. */
@RunWith(AndroidJUnit4.class)
public class ReceiptOcrTest {
 private File image(String text,boolean faded,float skew,boolean obscure)throws Exception {
  String[] lines=text.split("\n");Bitmap bitmap=Bitmap.createBitmap(1600,Math.max(1000,lines.length*90+180),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);canvas.rotate(skew,800,bitmap.getHeight()/2f);
  Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setTypeface(Typeface.MONOSPACE);paint.setColor(faded?Color.rgb(155,155,155):Color.BLACK);paint.setTextSize(42);
  for(int i=0;i<lines.length;i++)canvas.drawText(lines[i],80,100+i*90,paint);
  if(obscure){paint.setColor(Color.WHITE);canvas.drawRect(450,150,1550,bitmap.getHeight(),paint);}
  File file=File.createTempFile("ocr-fixture-",".jpg",ApplicationProvider.getApplicationContext().getCacheDir());try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.JPEG,95,out);}finally{bitmap.recycle();}return file;
 }
 private ReceiptCaptureService.Scan scan(File file)throws Exception {ReceiptImaging.Normalized attachment=ReceiptImaging.normalize(file);return ReceiptOcr.scan(file,attachment.jpeg,true);}
 @Test public void representativeGeneratedImagesThroughRealMlKit()throws Exception {
  Set<String> included=new HashSet<>(Arrays.asList("restaurant-gst","supermarket","fuel","electricity","broadband","pharmacy","hotel","parking","coupon-service","paid-zero-due","conflicting-finals","no-total"));int correct=0,uncertain=0,incorrect=0;List<String> failures=new ArrayList<>();
  try(BufferedReader reader=new BufferedReader(new InputStreamReader(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("receipt-fixtures.tsv"),java.nio.charset.StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){String[] fields=line.split("\t",3);if(fields.length!=3||!included.contains(fields[0]))continue;File file=image(fields[2].replace("\\n","\n"),false,0,false);
    try{ReceiptCaptureService.Scan result=scan(file);assertFalse(fields[0],result.ocrFailed);Long expected=fields[1].equals("UNCERTAIN")?null:Long.valueOf(fields[1]);if(!Objects.equals(expected,result.detection.amountPaise))failures.add(fields[0]+" expected="+expected+" actual="+result.detection.amountPaise+"\n"+result.detection.debugReport);if(result.detection.amountPaise==null)uncertain++;else if(Objects.equals(expected,result.detection.amountPaise))correct++;else incorrect++;result.preview.recycle();}finally{file.delete();}
  }}assertEquals(12,correct+uncertain+incorrect);System.out.println("GENERATED IMAGE CORPUS correct="+correct+" incorrect="+incorrect+" uncertain="+uncertain);assertTrue(String.join("\n",failures),failures.isEmpty());
 }
 @Test public void fadedSkewedAndObscuredReceiptsUseEvidenceOrManualFallback()throws Exception {
  File faded=image("TEST SHOP\nSubtotal Rs 800.00\nCGST Rs 20.00\nSGST Rs 20.00\nTOTAL Rs 840.00\nUPI Rs 840.00\nDUE Rs 0.00",true,4,false);
  File obscured=image("TEST SHOP\nGRAND TOTAL Rs 840.00\nUPI Rs 840.00",false,0,true);
  try{ReceiptCaptureService.Scan a=scan(faded),b=scan(obscured);assertEquals(a.detection.debugReport,Long.valueOf(84000),a.detection.amountPaise);assertNull(b.detection.debugReport,b.detection.amountPaise);a.preview.recycle();b.preview.recycle();}finally{faded.delete();obscured.delete();}
 }
 @Test public void rawRecognitionOrientationIsIndependentOfCompressedAttachment()throws Exception {
  File source=image("TEST RECEIPT\nGRAND TOTAL INR 285.75",false,0,false);Bitmap bitmap=BitmapFactory.decodeFile(source.getPath());Matrix matrix=new Matrix();matrix.setRotate(-90);Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);
  try{try(FileOutputStream out=new FileOutputStream(source)){rotated.compress(Bitmap.CompressFormat.JPEG,95,out);}ExifInterface exif=new ExifInterface(source);exif.setAttribute(ExifInterface.TAG_ORIENTATION,"6");exif.saveAttributes();ReceiptCaptureService.Scan result=scan(source);assertEquals(result.detection.debugReport,Long.valueOf(28575),result.detection.amountPaise);result.preview.recycle();}finally{bitmap.recycle();rotated.recycle();source.delete();}
 }
}
