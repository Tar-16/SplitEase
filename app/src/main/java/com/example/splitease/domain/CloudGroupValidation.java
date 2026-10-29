package com.example.splitease.domain;

import java.util.*;

/** Cloud-only technical bounds for one atomically validated group document. No accounting assumptions. */
public final class CloudGroupValidation {
    public static final int MAX_INITIAL_MEMBERS=8;
    private CloudGroupValidation(){}
    public static String name(String value){String text=value==null?"":value.trim();if(text.isEmpty()||text.length()>100)throw new IllegalArgumentException("group name");return text;}
    public static List<String> initialMembers(String actor,List<String> selected){
        if(actor==null||actor.isEmpty()||selected==null)throw new IllegalArgumentException("members");
        TreeSet<String> ids=new TreeSet<>();ids.add(actor);
        for(String id:selected)if(id==null||id.isEmpty()||!ids.add(id))throw new IllegalArgumentException("duplicate member");
        if(ids.size()>MAX_INITIAL_MEMBERS)throw new IllegalArgumentException("initial member limit");
        return new ArrayList<>(ids);
    }
}
