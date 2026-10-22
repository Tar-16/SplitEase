package com.example.splitease.ui.expenses;

import android.content.Context;
import com.example.splitease.R;
import com.example.splitease.model.*;
import com.example.splitease.util.InrFormatter;
import java.text.DateFormat;
import java.util.*;

/** Display-only formatting; all accounting is calculated by the domain/repository layer. */
public final class ExpenseUi {
    private ExpenseUi() {}
    public static String date(long timestamp) {
        DateFormat format=DateFormat.getDateInstance(DateFormat.MEDIUM);
        format.setTimeZone(TimeZone.getTimeZone("UTC")); return format.format(new Date(timestamp));
    }
    public static String balance(Context context,long paise,boolean own) {
        if(paise==0) return context.getString(R.string.balance_settled);
        return context.getString(paise>0 ? (own?R.string.balance_owed:R.string.balance_member_owed) : (own?R.string.balance_owes:R.string.balance_member_owes),InrFormatter.magnitude(paise));
    }
    public static String name(Context context,GroupDetails details,String id) {
        String name=details.names.get(id);
        if(name==null) throw new IllegalStateException("Accounting identity missing");
        for(MemberIdentity member:details.members) if(member.id.equals(id)) return name;
        return context.getString(R.string.expense_former,name);
    }
    public static String icon(String category) {
        switch(category) { case "Food":return "🍽"; case "Travel":return "✈"; case "Rent":return "⌂"; case "Bills":return "▤"; default:return "•"; }
    }
}
