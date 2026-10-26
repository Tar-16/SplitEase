package com.example.splitease.platform.receipts;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import androidx.core.content.FileProvider;
import com.example.splitease.domain.*;
import com.example.splitease.repository.RepositoryCallback;
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
  try{ReceiptImaging.Normalized normalized=ReceiptImaging.normalize(file(id));Scan scan=ReceiptOcr.scan(file(id),normalized.jpeg,com.example.splitease.BuildConfig.DEBUG);executor.execute(()->callback.onSuccess(scan));}
  catch(Exception failure){executor.execute(()->callback.onError(failure));}
  catch(OutOfMemoryError exhausted){executor.execute(()->callback.onError(new IOException("Capture exceeds available image memory")));}
  finally{file(id).delete();}
 });}
 /** Also used by the receipt viewer; bounded decoding runs off the UI thread. */
 public void decode(byte[] jpeg,boolean preview,Executor executor,RepositoryCallback<Bitmap> callback){worker.execute(()->{try{Bitmap image=ReceiptImaging.decode(jpeg,preview);executor.execute(()->callback.onSuccess(image));}catch(Exception failure){executor.execute(()->callback.onError(failure));}});}
 public static Scan recognize(byte[] jpeg)throws IOException {return ReceiptOcr.scan(jpeg,com.example.splitease.BuildConfig.DEBUG);}
}
