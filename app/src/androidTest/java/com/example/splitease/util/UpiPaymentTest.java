package com.example.splitease.util;
import android.content.*;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UpiPaymentTest {
    @Test public void encodedUriKeepsExactPaiseAndCannotInjectParameters() {
        Uri uri=UpiPayment.uri("name@bank","A & B",101,"Lunch & am=999 # ₹");
        assertEquals("upi",uri.getScheme()); assertEquals("pay",uri.getHost());
        assertEquals("name@bank",uri.getQueryParameter("pa")); assertEquals("A & B",uri.getQueryParameter("pn"));
        assertEquals("1.01",uri.getQueryParameter("am")); assertEquals("INR",uri.getQueryParameter("cu"));
        assertEquals("Lunch & am=999 # ₹",uri.getQueryParameter("tn")); assertEquals(5,uri.getQueryParameterNames().size());
        assertEquals("92233720368547758.07",UpiPayment.uri("a@b","A",Long.MAX_VALUE,"note").getQueryParameter("am"));
        assertThrows(IllegalArgumentException.class,() -> UpiPayment.uri("bad&am=999","A",1,"note"));
        assertThrows(IllegalArgumentException.class,() -> UpiPayment.uri("a@b","A",0,"note"));
    }
    @Test public void missingAppRacesAndCancelledHandoffHaveNoPaymentResultContract() {
        Uri uri=UpiPayment.uri("a@b","A",1,"note"); AtomicReference<Intent> started=new AtomicReference<>();
        assertFalse(UpiPayment.launch(uri,"Choose",new UpiPayment.Gateway() {
            public boolean available(Intent payment) { return false; }
            public void start(Intent chooser) { fail("Must not start without a handler"); }
        }));
        assertFalse(UpiPayment.launch(uri,"Choose",new UpiPayment.Gateway() {
            public boolean available(Intent payment) { return true; }
            public void start(Intent chooser) { throw new ActivityNotFoundException(); }
        }));
        assertTrue(UpiPayment.launch(uri,"Choose",new UpiPayment.Gateway() {
            public boolean available(Intent payment) { assertEquals(Intent.ACTION_VIEW,payment.getAction()); return true; }
            public void start(Intent chooser) { started.set(chooser); /* Simulate chooser cancellation: no result is delivered or interpreted. */ }
        }));
        assertEquals(Intent.ACTION_CHOOSER,started.get().getAction());
        Intent target=started.get().getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(uri,target.getData());
    }
}
