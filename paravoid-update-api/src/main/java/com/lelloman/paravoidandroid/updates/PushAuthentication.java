package com.lelloman.paravoidandroid.updates;

import java.util.Map;
/** Optional shell-packaged authentication hook. No token format or identity provider is assumed. */
public interface PushAuthentication {
    /** Return request headers for this configured endpoint. Never log credentials. */
    Map<String,String> headers(PushRequest request) throws Exception;
}
