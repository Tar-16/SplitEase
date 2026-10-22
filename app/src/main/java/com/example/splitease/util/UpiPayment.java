package com.example.splitease.util;

import android.content.*;
import android.net.Uri;
import com.example.splitease.domain.*;

/** Intent handoff only. There is deliberately no result interpretation or repository dependency. */
public final class UpiPayment {
    private UpiPayment() {}
    public static Uri uri(String upi,String name,long paise,String note) {
        Rules.positive(paise);
        String address=Rules.text(upi);
        if(!address.matches("[A-Za-z0-9._-]+@[A-Za-z0-9.-]+")) throw new IllegalArgumentException("Invalid UPI address");
        return new Uri.Builder().scheme("upi").authority("pay").appendQueryParameter("pa",address)
                .appendQueryParameter("pn",Rules.text(name)).appendQueryParameter("am",ExpenseAmounts.input(paise))
                .appendQueryParameter("cu","INR").appendQueryParameter("tn",Rules.text(note)).build();
    }
    /** Testable Android handoff boundary. No result callback is accepted or interpreted. */
    public interface Gateway {
        boolean available(Intent payment);
        void start(Intent chooser);
    }
    public static boolean launch(Context context,Uri uri,String chooserTitle) {
        return launch(uri,chooserTitle,new Gateway() {
            @Override public boolean available(Intent payment) { return !context.getPackageManager().queryIntentActivities(payment,0).isEmpty(); }
            @Override public void start(Intent chooser) { context.startActivity(chooser); }
        });
    }
    public static boolean launch(Uri uri,String chooserTitle,Gateway gateway) {
        Intent payment=new Intent(Intent.ACTION_VIEW,uri);
        try {
            if(!gateway.available(payment)) return false;
            gateway.start(Intent.createChooser(payment,chooserTitle)); return true;
        } catch(ActivityNotFoundException | SecurityException failure) { return false; }
    }
}
