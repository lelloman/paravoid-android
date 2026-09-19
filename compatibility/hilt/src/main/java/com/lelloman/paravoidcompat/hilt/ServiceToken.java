package com.lelloman.paravoidcompat.hilt;

import dagger.hilt.android.scopes.ServiceScoped;
import javax.inject.Inject;

@ServiceScoped
public final class ServiceToken {
    @Inject public ServiceToken() {}
}
