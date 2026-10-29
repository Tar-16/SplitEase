package com.example.splitease.model;

import java.util.*;

/** Authorized group/accounting snapshot. Members are current; names/balances also retain referenced historical identities. */
public final class GroupDetails {
    public final Group group;
    public final List<MemberIdentity> members;
    public final boolean canLeave;
    public final List<Expense> expenses;
    public final Map<String, Long> balances;
    public final Map<String, String> names;
    public final long totalExpenses;
    /** False means financial values are unavailable and must never be displayed/interpreted as zero. */
    public final boolean accountingAvailable;

    public GroupDetails(Group group, List<MemberIdentity> members, boolean canLeave,
            List<Expense> expenses, Map<String,Long> balances, Map<String,String> names, long totalExpenses) {
        this(group,members,canLeave,expenses,balances,names,totalExpenses,true);
    }
    public GroupDetails(Group group,List<MemberIdentity> members,boolean canLeave,List<Expense> expenses,
            Map<String,Long> balances,Map<String,String> names,long totalExpenses,boolean accountingAvailable){
        this.accountingAvailable=accountingAvailable;
        this.group=group;
        this.members=Collections.unmodifiableList(new ArrayList<>(members));
        this.canLeave=canLeave;
        this.expenses=Collections.unmodifiableList(new ArrayList<>(expenses));
        this.balances=Collections.unmodifiableMap(new LinkedHashMap<>(balances));
        this.names=Collections.unmodifiableMap(new LinkedHashMap<>(names));
        this.totalExpenses=totalExpenses;
    }
}
