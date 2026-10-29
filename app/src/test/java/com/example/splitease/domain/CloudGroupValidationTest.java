package com.example.splitease.domain;

import com.example.splitease.model.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class CloudGroupValidationTest {
 @Test public void initialMembershipKeepsOpaqueUidsSortedAndCreatorAutomatic(){assertEquals(Arrays.asList("cloud-A","cloud-M","cloud-Z"),CloudGroupValidation.initialMembers("cloud-Z",Arrays.asList("cloud-M","cloud-A")));}
 @Test public void duplicatesSelfAndMissingIdentitiesAreRejected(){for(List<String> invalid:Arrays.asList(Arrays.asList("other","other"),Collections.singletonList("creator"),Collections.singletonList(""),Collections.singletonList((String)null)))try{CloudGroupValidation.initialMembers("creator",invalid);fail();}catch(IllegalArgumentException expected){}}
 @Test public void initialRuleBudgetIsEightIncludingCreator(){List<String> ids=new ArrayList<>();for(int i=0;i<7;i++)ids.add("member-"+i);assertEquals(8,CloudGroupValidation.initialMembers("creator",ids).size());ids.add("ninth");try{CloudGroupValidation.initialMembers("creator",ids);fail();}catch(IllegalArgumentException expected){}}
 @Test public void groupNameIsTrimmedAndBoundedWithoutChangingLocalRules(){assertEquals("Goa trip",CloudGroupValidation.name(" Goa trip "));for(String invalid:Arrays.asList(""," \n",new String(new char[101]).replace('\0','x')))try{CloudGroupValidation.name(invalid);fail();}catch(IllegalArgumentException expected){}}
 @Test public void cloudUnknownAccountingIsExplicitAndCannotAllowLeaving(){Group group=new Group(UUID.randomUUID().toString(),"Home",Group.Type.FLAT,"opaque_uid",1);GroupDetails cloud=new GroupDetails(group,Collections.emptyList(),false,Collections.emptyList(),Collections.emptyMap(),Collections.emptyMap(),0,false);assertFalse(cloud.accountingAvailable);assertFalse(cloud.canLeave);assertTrue(cloud.balances.isEmpty());GroupDetails local=new GroupDetails(group,Collections.emptyList(),true,Collections.emptyList(),Collections.emptyMap(),Collections.emptyMap(),0);assertTrue(local.accountingAvailable);}
}
