package com.example.splitease.platform.receipts;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import androidx.core.content.FileProvider;
import com.example.splitease.domain.*;
import com.example.splitease.repository.RepositoryCallback;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Android boundary for private camera output and bundled on-device OCR. Never returns file paths. */
public final class ReceiptCaptureService {
 public static final class Target {public final String id;public final Uri uri;Target(String id,Uri uri){this.id=id;this.uri=uri;}}
 public static final class Scan {public final byte[] jpeg;public final Bitmap preview;public final ReceiptTotalDetector.Detection detection;public final boolean ocrFailed;Scan(byte[] jpeg,Bitmap preview,ReceiptTotalDetector.Detection detection,boolean failed){this.jpeg=jpeg;this.preview=preview;this.detection=detection;ocrFailed=failed;}}
 private final Context context;private final File directory;
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"receipt-imaging"));
 public ReceiptCaptureService(Context context){this.context=context.getApplicationContext();directory=new File(context.getCacheDir(),"receipt-captures");worker.execute(()->{File[] files=directory.listFiles();if(files!=null)for(File file:files)if(System.currentTimeMillis()-file.lastModified()>86400000L)file.delete();});}
 private File file(String id){return new File(directory,Rules.id(id)+".jpg");}
 public void prepare(Executor executor,RepositoryCallback<Target> callback){worker.execute(()->{try{if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Camera storage unavailable");String id=UUID.randomUUID().toString();File output=file(id);if(!output.createNewFile())throw new IOException("Capture unavailable");Target target=new Target(id,FileProvider.getUriForFile(context,context.getPackageName()+".receipts",output));executor.execute(()->callback.onSuccess(target));}catch(Exception failure){executor.execute(()->callback.onError(failure));}});}
 public void cancel(String id){if(id!=null&&!id.isEmpty())worker.execute(()->{File capture=file(id);if(capture.exists())capture.delete();});}
 public void process(String id,Executor executor,RepositoryCallback<Scan> callback){worker.execute(()->{
  try{ReceiptImaging.Normalized normalized=ReceiptImaging.normalize(file(id));Scan scan=recognize(normalized.jpeg);executor.execute(()->callback.onSuccess(scan));}
  catch(Exception failure){executor.execute(()->callback.onError(failure));}
  finally{file(id).delete();}
 });}
 /** Also used by the receipt viewer; bounded decoding runs off the UI thread. */
 public void decode(byte[] jpeg,boolean preview,Executor executor,RepositoryCallback<Bitmap> callback){worker.execute(()->{try{Bitmap image=ReceiptImaging.decode(jpeg,preview);executor.execute(()->callback.onSuccess(image));}catch(Exception failure){executor.execute(()->callback.onError(failure));}});}
 public static Scan recognize(byte[] jpeg)throws IOException {
  Bitmap original=ReceiptImaging.decode(jpeg,false),bitmap=original;
  if(original.getWidth()>1600)bitmap=Bitmap.createScaledBitmap(original,1600,Math.max(1,Math.round(original.getHeight()*1600f/original.getWidth())),true);
  TextRecognizer recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
  StringBuilder text=new StringBuilder();boolean failed=false,deferred=false;Bitmap pendingBitmap=null;
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
  try {
   for(int top=0;top<bitmap.getHeight();top+=1744){
    int height=Math.min(2000,bitmap.getHeight()-top);Bitmap tile=Bitmap.createBitmap(bitmap,0,top,bitmap.getWidth(),height);
    com.google.android.gms.tasks.Task<Text> task=null;boolean pending=false;
    try {
     task=recognizer.process(InputImage.fromBitmap(tile,0));
     long remaining=deadline-System.nanoTime();if(remaining<=0)throw new TimeoutException();
     Text result=Tasks.await(task,remaining,TimeUnit.NANOSECONDS);
     text.append(rows(result,top>0?24:0,top+height<bitmap.getHeight()?height-24:height)).append('\n');
    }catch(InterruptedException interrupted){Thread.currentThread().interrupt();failed=true;}
     catch(ExecutionException|TimeoutException error){failed=true;}
    finally{
     if(task!=null&&!task.isComplete()){
      pending=true;deferred=true;pendingBitmap=tile;Bitmap held=tile;
      task.addOnCompleteListener(Runnable::run,value->{recognizer.close();held.recycle();});
     }
     if(!pending&&tile!=bitmap)tile.recycle();
    }
    if(failed||top+height>=bitmap.getHeight())break;
   }
  }finally{
   if(!deferred)recognizer.close();
   if(bitmap!=pendingBitmap)bitmap.recycle();if(original!=bitmap)original.recycle();
  }
  return new Scan(jpeg,ReceiptImaging.decode(jpeg,true),ReceiptTotalDetector.detect(failed?"":text.toString()),failed);
 }
 private static String rows(Text text,int minimum,int maximum){
  List<Text.Line> lines=new ArrayList<>();for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines())if(line.getBoundingBox()!=null&&line.getBoundingBox().top>=minimum&&line.getBoundingBox().bottom<=maximum)lines.add(line);
  lines.sort(Comparator.comparingInt(line->line.getBoundingBox().centerY()));StringBuilder result=new StringBuilder();List<Text.Line> row=new ArrayList<>();int center=-1,tolerance=0;
  for(Text.Line line:lines){android.graphics.Rect bounds=line.getBoundingBox();if(!row.isEmpty()&&Math.abs(bounds.centerY()-center)>Math.max(tolerance,bounds.height()*0.4f)){appendRow(result,row);row.clear();}
   if(row.isEmpty()){center=bounds.centerY();tolerance=Math.max(6,Math.round(bounds.height()*0.4f));}row.add(line);
  }appendRow(result,row);return result.toString();
 }
 private static void appendRow(StringBuilder output,List<Text.Line> row){row.sort(Comparator.comparingInt(line->line.getBoundingBox().left));for(Text.Line line:row)output.append(line.getText()).append(' ');output.append('\n');}
}
