package com.example.splitease.domain;

/** Cloud-only registry keys: bounded ASCII avoids path separators and Unicode case-fold ambiguity. */
public final class CloudProfileValidation {
    private CloudProfileValidation(){}
    public static String display(String value){String result=value==null?"":value.trim();if(!result.matches("[A-Za-z0-9_]{3,30}"))throw new IllegalArgumentException("username");return result;}
    public static String normalized(String value){return AccountValidation.normalize(display(value));}
    public static String upi(String value){if(value==null||value.trim().isEmpty())return null;String result=value.trim();if(result.length()>100||!result.matches("[A-Za-z0-9._-]+@[A-Za-z0-9.-]+"))throw new IllegalArgumentException("upi");return result;}
}
