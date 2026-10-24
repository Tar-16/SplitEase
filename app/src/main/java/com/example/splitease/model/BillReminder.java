package com.example.splitease.model;
public final class BillReminder {
 public final String billId,cycleId,title,groupName; public final long dueDate;
 public BillReminder(String billId,String cycleId,String title,String groupName,long dueDate) { this.billId=billId;this.cycleId=cycleId;this.title=title;this.groupName=groupName;this.dueDate=dueDate; }
}
