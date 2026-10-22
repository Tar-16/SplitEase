package com.example.splitease.model;
import java.util.*;

/** Membership-authorized accounting plus recipient UPI metadata needed for suggestions only. */
public final class SettlementDetails {
    public final GroupDetails group;
    public final List<Settlement> records;
    public final List<Transfer> suggestions;
    public final Map<String,String> recipientUpi;
    public SettlementDetails(GroupDetails group,List<Settlement> records,List<Transfer> suggestions,Map<String,String> recipientUpi) {
        this.group=group; this.records=Collections.unmodifiableList(new ArrayList<>(records));
        this.suggestions=Collections.unmodifiableList(new ArrayList<>(suggestions)); this.recipientUpi=Collections.unmodifiableMap(new HashMap<>(recipientUpi));
    }
}
