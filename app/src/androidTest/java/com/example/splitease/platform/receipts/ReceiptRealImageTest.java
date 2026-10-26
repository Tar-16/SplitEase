package com.example.splitease.platform.receipts;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.domain.ReceiptTotalDetector;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.TimeUnit;

/** Opt-in private fixture; photograph and recognized text never enter repository assets or logs. */
@RunWith(AndroidJUnit4.class)
public class ReceiptRealImageTest {
 @Test public void evaluatePrivatePhotograph()throws Exception {
  String phase=InstrumentationRegistry.getArguments().getString("receiptImagePhase");Assume.assumeNotNull(phase);
  Context context=ApplicationProvider.getApplicationContext();File source=new File(context.getCacheDir(),"private-ocr-fixture.jpg");Assert.assertTrue(source.isFile());
  ReceiptImaging.Normalized normalized=ReceiptImaging.normalize(source);
  ReceiptCaptureService.Scan scan=ReceiptOcr.scan(source,normalized.jpeg,true);
  String report="phase="+phase+"\nstatus="+scan.detection.status+"\namountPaise="+scan.detection.amountPaise+"\nreliability="+scan.detection.reliability+"\nlabel="+scan.detection.supportingLabel+"\nocrFailed="+scan.ocrFailed+"\n"+scan.detection.debugReport;
  scan.preview.recycle();
  try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),"receipt-"+phase+".txt"))){out.write(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  if("after".equals(phase)){Assert.assertEquals(Long.valueOf(70140),scan.detection.amountPaise);Assert.assertFalse(scan.ocrFailed);}
 }
}
