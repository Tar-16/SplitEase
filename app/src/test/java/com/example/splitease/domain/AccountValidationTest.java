package com.example.splitease.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class AccountValidationTest {
    @Test public void normalizesLookupAndRejectsMalformedEmail() {
        assertEquals("person@example.com",AccountValidation.normalize(" Person@Example.COM "));
        for (String value : new String[]{"person@example.com","first.last+tag@example.co.in"," Person@Example.COM "}) assertTrue(value,AccountValidation.validEmail(value));
        for (String value : new String[]{"","not-email","a@","@example.com","a@localhost","a b@example.com","a..b@example.com","a@-bad.com","a@example..com"}) assertFalse(value,AccountValidation.validEmail(value));
    }
    @Test public void checklistEnforcesEachConfirmedRule() {
        assertArrayEquals(new boolean[]{true,true,true,true,true},AccountValidation.passwordChecklist("Strong1!".toCharArray()));
        assertFalse(AccountValidation.strongPassword("Short1!".toCharArray()));
        assertFalse(AccountValidation.strongPassword("lowercase1!".toCharArray()));
        assertFalse(AccountValidation.strongPassword("UPPERCASE1!".toCharArray()));
        assertFalse(AccountValidation.strongPassword("NoNumbers!".toCharArray()));
        assertFalse(AccountValidation.strongPassword("NoSpecial1".toCharArray()));
        assertFalse(AccountValidation.strongPassword("NoSpecial1 ".toCharArray()));
        assertTrue(AccountValidation.strongPassword("Longer password1!".toCharArray()));
    }
}
