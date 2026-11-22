package com.lelloman.paravoidandroid.recovery;

/** What the recovery screen does after {@link RecoveryActionProvider#perform} returns. */
public final class RecoveryActionResult {
    public enum Kind { NONE, MESSAGE, OPEN_URI, SHARE_TEXT }
    public final Kind kind;
    /** Message to show for MESSAGE, or the shared text for SHARE_TEXT. */
    public final String text;
    /** URI for OPEN_URI, or the optional subject for SHARE_TEXT. */
    public final String target;

    private RecoveryActionResult(Kind kind, String text, String target) {
        this.kind = kind; this.text = text; this.target = target;
    }
    /** Nothing more to do. */
    public static RecoveryActionResult none() { return new RecoveryActionResult(Kind.NONE, null, null); }
    /** Shows a short message, already in the user's language, e.g. "Report sent. Thank you!". */
    public static RecoveryActionResult message(String text) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("text required");
        return new RecoveryActionResult(Kind.MESSAGE, text, null);
    }
    /**
     * Opens a URI with another app: an https:// page such as an issue form, or a mailto: link
     * (sent with ACTION_SENDTO). Other schemes are opened with ACTION_VIEW.
     */
    public static RecoveryActionResult openUri(String uri) {
        if (uri == null || uri.isEmpty()) throw new IllegalArgumentException("uri required");
        return new RecoveryActionResult(Kind.OPEN_URI, null, uri);
    }
    /** Lets the user share plain text, such as a prepared bug report, with an app they choose. */
    public static RecoveryActionResult shareText(String subject, String text) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("text required");
        return new RecoveryActionResult(Kind.SHARE_TEXT, text, subject);
    }
}
