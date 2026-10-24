package com.example.splitease.domain;
import java.util.*;
/** UTC billing months and reminder window, independent of platform scheduling. */
public final class RecurringSchedule {
    private RecurringSchedule() {}
    public static int month(long now) { Rules.date(now); Calendar c=new GregorianCalendar(TimeZone.getTimeZone("UTC")); c.setTimeInMillis(now); int y=c.get(Calendar.YEAR); if(y>9999) throw new IllegalArgumentException(); return y*12+c.get(Calendar.MONTH); }
    public static long due(int month,int day) { return Rules.dueDate(month/12,month%12+1,day); }
    public static boolean reminderDue(long now,long due,int lead) { Rules.date(now); if(lead<0 || lead>31) throw new IllegalArgumentException(); long today=now/86400000L; return today>=due/86400000L-lead && today<=due/86400000L; }
}
