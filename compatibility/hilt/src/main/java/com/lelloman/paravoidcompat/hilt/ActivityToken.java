package com.lelloman.paravoidcompat.hilt;

import dagger.hilt.android.scopes.ActivityScoped;
import javax.inject.Inject;

@ActivityScoped
public final class ActivityToken {
    @Inject public ActivityToken() {}
}
