package com.example.splitease.ui.groups;

import com.example.splitease.auth.AuthService;
import com.example.splitease.repository.GroupRepository;

/** Composition boundary for the isolated debug graph; never a global data-mode toggle. */
public final class GroupDependencies {
    public final AuthService auth;
    public final GroupRepository groups;
    public final com.example.splitease.repository.ExpenseRepository expenses;
    public GroupDependencies(AuthService auth,GroupRepository groups){        this(auth,groups,null);
    }
    public GroupDependencies(AuthService auth,GroupRepository groups,com.example.splitease.repository.ExpenseRepository expenses){
        this.auth=auth;this.groups=groups;this.expenses=expenses;}
}
