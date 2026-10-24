package com.example.splitease.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class RecurringScheduleTest {
 @Test public void monthAndClampedDaysUseUtc(){int feb=2028*12+1;assertEquals(feb,RecurringSchedule.month(Rules.dueDate(2028,2,29)));assertEquals(Rules.dueDate(2028,2,29),RecurringSchedule.due(feb,31));assertEquals(Rules.dueDate(2027,2,28),RecurringSchedule.due(2027*12+1,31));}
 @Test public void reminderWindowIncludesLeadAndDueButNotPastDue(){long due=Rules.dueDate(2027,1,1);assertTrue(RecurringSchedule.reminderDue(due-3*86400000L,due,3));assertFalse(RecurringSchedule.reminderDue(due-4*86400000L,due,3));assertTrue(RecurringSchedule.reminderDue(due+86399999L,due,3));assertFalse(RecurringSchedule.reminderDue(due+86400000L,due,3));}
 @Test public void invalidDatesAndLeadAreRejected(){assertThrows(IllegalArgumentException.class,()->RecurringSchedule.month(-1));assertThrows(IllegalArgumentException.class,()->RecurringSchedule.reminderDue(0,0,32));assertThrows(IllegalArgumentException.class,()->RecurringSchedule.due(2026*12,32));}
}
