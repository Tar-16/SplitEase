package com.example.splitease.debug;

import androidx.fragment.app.Fragment;
import com.example.splitease.ui.groups.GroupDependencies;
/** Release has no cloud group graph or mode selector. */
public final class CloudGroups {
    private CloudGroups(){}
    public static GroupDependencies dependencies(Fragment fragment){return null;}
}
