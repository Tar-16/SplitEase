package com.example.splitease.domain;

import java.util.*;

public final class Rules {
    private Rules() {}
    public static String id(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value))
            throw new IllegalArgumentException("Expected canonical String UUID");
        return value;
    }
    public static String text(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Text is required");
        return value.trim();
    }
    public static void positive(long paise) {
        if (paise <= 0) throw new IllegalArgumentException("Amount must be positive integer paise");
    }
    public static void date(long millis) {
        if (millis < 0) throw new IllegalArgumentException("Date must be nonnegative epoch millis");
    }
    /** Stable UUID sort determines who receives each leftover paise, independent of UI order. */
    public static Map<String, Long> equalSplit(long total, Collection<String> participants) {
        positive(total);
        if (participants == null || participants.isEmpty()) throw new IllegalArgumentException("Participants required");
        TreeSet<String> ids = new TreeSet<>();
        for (String participant : participants) {
            if (!ids.add(id(participant))) throw new IllegalArgumentException("Duplicate participant");
        }
        return equalSplitIdentities(total, ids);
    }
    /** Same exact split algorithm for opaque cloud identities, sorted deterministically. */
    public static Map<String,Long> equalSplitIdentities(long total, Collection<String> participants) {
        positive(total);
        if(participants==null || participants.isEmpty()) throw new IllegalArgumentException("Participants required");
        TreeSet<String> ids=new TreeSet<>();
        for(String id:participants) if(id==null || id.isEmpty() || !ids.add(id)) throw new IllegalArgumentException("Invalid participant");
        long base = total / ids.size(), remainder = total % ids.size();
        Map<String, Long> result = new LinkedHashMap<>();
        for (String participant : ids) result.put(participant, base + (remainder-- > 0 ? 1 : 0));
        return Collections.unmodifiableMap(result);
    }
    /** Months use 1..12. Days beyond month end clamp to its final day, in UTC. */
    public static long dueDate(int year, int month, int day) {
        if (year < 1970 || year > 9999 || month < 1 || month > 12 || day < 1 || day > 31)
            throw new IllegalArgumentException("Invalid monthly schedule");
        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        calendar.clear();
        calendar.set(year, month - 1, 1);
        calendar.set(Calendar.DAY_OF_MONTH, Math.min(day, calendar.getActualMaximum(Calendar.DAY_OF_MONTH)));
        return calendar.getTimeInMillis();
    }
}
