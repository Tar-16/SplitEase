package com.example.splitease.ui;

import androidx.navigation.NavOptions;
import com.example.splitease.R;

/** Shared screen transitions. Property animators honour the system animation scale. */
public final class Transitions {
    private Transitions() {}
    /** Forward/back between a screen and its detail. */
    public static NavOptions.Builder page() {
        return new NavOptions.Builder().setEnterAnim(R.animator.page_enter).setExitAnim(R.animator.page_exit)
                .setPopEnterAnim(R.animator.page_pop_enter).setPopExitAnim(R.animator.page_pop_exit);
    }
    /** Fade-through between sibling home tabs. */
    public static NavOptions.Builder tab() {
        return new NavOptions.Builder().setEnterAnim(R.animator.tab_enter).setExitAnim(R.animator.tab_exit)
                .setPopEnterAnim(R.animator.tab_enter).setPopExitAnim(R.animator.tab_exit);
    }
}
