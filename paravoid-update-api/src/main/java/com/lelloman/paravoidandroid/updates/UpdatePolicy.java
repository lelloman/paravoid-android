package com.lelloman.paravoidandroid.updates;

/** Automatic-work eligibility only. Explicit user commands bypass this policy. */
public interface UpdatePolicy {
    Decision evaluate(Context context) throws Exception;
    final class Context {
        public final boolean download;
        public final long nowSeconds, lastCheckSeconds;
        public final String previousError;
        public final java.util.Map<String,String> data;
        public Context(boolean download, long now, long lastCheck, String previousError, java.util.Map<String,String> data) {
            this.download=download; nowSeconds=now; lastCheckSeconds=lastCheck; this.previousError=previousError;
            this.data=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data));
        }
    }
    final class Decision {
        public enum Kind { ALLOW, SKIP, DEFER }
        public final Kind kind;
        public final long untilSeconds;
        private Decision(Kind kind, long until) { this.kind=kind; untilSeconds=until; }
        public static Decision allow() { return new Decision(Kind.ALLOW,0); }
        public static Decision skip() { return new Decision(Kind.SKIP,0); }
        public static Decision deferUntil(long seconds) {
            if(seconds < 0) throw new IllegalArgumentException();
            return new Decision(Kind.DEFER,seconds);
        }
    }
}
