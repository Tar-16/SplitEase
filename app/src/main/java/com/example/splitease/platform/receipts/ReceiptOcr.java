package com.example.splitease.platform.receipts;

import android.graphics.*;
import androidx.exifinterface.media.ExifInterface;
import com.example.splitease.domain.ReceiptTotalDetector;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Recognition uses bounded upright source pixels, independently of attachment JPEG compression. */
public final class ReceiptOcr {
 private ReceiptOcr(){}
 public static ReceiptCaptureService.Scan scan(File source,byte[] attachment,boolean debug)throws IOException {
  return recognize(source(source),attachment,debug);
 }
 public static ReceiptCaptureService.Scan scan(byte[] attachment,boolean debug)throws IOException {
  return recognize(ReceiptImaging.decode(attachment,false),attachment,debug);
 }
 private static Bitmap source(File file)throws IOException {
  if(file.length()<1||file.length()>25L*1024*1024)throw new IOException("Capture size unsupported");
  BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
  if(bounds.outWidth<1||bounds.outHeight<1||(long)bounds.outWidth*bounds.outHeight>150000000L)throw new IOException("Invalid capture");
  BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
  while((long)(bounds.outWidth/options.inSampleSize)*(bounds.outHeight/options.inSampleSize)>16000000L)options.inSampleSize*=2;
  Bitmap bitmap=BitmapFactory.decodeFile(file.getPath(),options);if(bitmap==null)throw new IOException("Cannot read capture");
  try {
   Matrix matrix=new Matrix();switch(new ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)){
    case 2:matrix.setScale(-1,1);break;case 3:matrix.setRotate(180);break;case 4:matrix.setScale(1,-1);break;
    case 5:matrix.setRotate(90);matrix.postScale(-1,1);break;case 6:matrix.setRotate(90);break;case 7:matrix.setRotate(-90);matrix.postScale(-1,1);break;case 8:matrix.setRotate(-90);break;default:break;
   }
   Bitmap upright=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(upright!=bitmap)bitmap.recycle();return upright;
  }catch(IOException|RuntimeException error){bitmap.recycle();throw error;}
 }
 private static ReceiptCaptureService.Scan recognize(Bitmap bitmap,byte[] attachment,boolean debug)throws IOException {
  TextRecognizer recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
  List<ReceiptTotalDetector.Line> lines=new ArrayList<>();boolean failed=false,deferred=false;Bitmap held=null;
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
  try {
   // Keep small thermal glyphs at source scale. Tile both axes rather than shrink a wide photograph.
   outer:for(int top=0;top<bitmap.getHeight();top+=1648){int h=Math.min(2000,bitmap.getHeight()-top);
    for(int left=0;left<bitmap.getWidth();left+=1648){int w=Math.min(2000,bitmap.getWidth()-left);Bitmap tile=Bitmap.createBitmap(bitmap,left,top,w,h);com.google.android.gms.tasks.Task<Text> task=null;boolean pending=false;
     try {
      task=recognizer.process(InputImage.fromBitmap(tile,0));long remaining=deadline-System.nanoTime();if(remaining<=0)throw new TimeoutException();
      Text text=Tasks.await(task,remaining,TimeUnit.NANOSECONDS);
      for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){
       Point[] corners=line.getCornerPoints();float slope=0;
       if(corners!=null&&corners.length>=2&&corners[1].x!=corners[0].x)slope=(corners[1].y-corners[0].y)/(float)(corners[1].x-corners[0].x);
       for(Text.Element word:line.getElements()) {Rect box=word.getBoundingBox();if(box==null)continue;
        if((left>0&&box.left<12)||(top>0&&box.top<12)||(left+w<bitmap.getWidth()&&box.right>w-12)||(top+h<bitmap.getHeight()&&box.bottom>h-12))continue;
        lines.add(new ReceiptTotalDetector.Line(word.getText(),left+box.left,top+box.top,left+box.right,top+box.bottom,slope));
       }
      }
     }catch(InterruptedException interrupted){Thread.currentThread().interrupt();failed=true;}catch(ExecutionException|TimeoutException error){failed=true;}
     finally{if(task!=null&&!task.isComplete()){pending=true;deferred=true;held=tile;Bitmap hold=tile;task.addOnCompleteListener(Runnable::run,result->{recognizer.close();hold.recycle();});}if(!pending&&tile!=bitmap)tile.recycle();}
     if(failed)break outer;if(left+w>=bitmap.getWidth())break;
    }
    if(top+h>=bitmap.getHeight())break;
   }
  }catch(RuntimeException failure){failed=true;}
  finally{if(!deferred)recognizer.close();if(bitmap!=held)bitmap.recycle();}
  ReceiptTotalDetector.Detection detection=ReceiptTotalDetector.detect(failed?Collections.emptyList():lines,debug);
  return new ReceiptCaptureService.Scan(attachment,ReceiptImaging.decode(attachment,true),detection,failed);
 }
}
