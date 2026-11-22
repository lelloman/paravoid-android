package com.lelloman.paravoidandroid.runtime;

import android.app.Activity;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.lelloman.paravoidandroid.contract.Protocol.ExpectedArchive;

/**
 * Visible shell coordinator survives the payload processes it stops.
 *
 * <p>Full-screen, framework-only UI: it runs in the payload-free recovery process. Its look comes
 * from its manifest theme: the plugin-generated {@code Theme.Paravoid.Restart}, or the app theme
 * selected with {@code paravoid.updates.restartTheme}. Texts and icon come from paravoidRestart*
 * theme attributes, with built-in {@link ShellText} fallbacks if a theme does not define them.
 */
public final class RestartActivity extends Activity {
    static final String PREFERENCES="paravoid-restart-v1";
    /** Keeps the progress page readable before stopping processes, which hands over to the relaunch. */
    static final long MIN_PROGRESS_MILLIS=1000;
    private enum Screen { CONFIRM, RESTARTING, FAILED }
    private boolean started;
    private ExpectedArchive offer;
    private Screen screen;
    private TextView title, message;
    private ProgressBar progress;
    private LinearLayout actions;
    private CharSequence label;
    private long progressShown;
    private final Runnable stopProcesses=this::stopProcesses;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        offer=UpdateWire.offer(getIntent().getBundleExtra("offer"));
        label=getApplicationInfo().loadLabel(getPackageManager());
        setContentView(layout());
        show(getIntent().getBooleanExtra("confirmation",false) ? Screen.CONFIRM : Screen.RESTARTING);
    }
    @Override protected void onPostResume() {
        super.onPostResume();
        if(started) return;
        started=true;
        if(offer!=null && !ShellApplication.requireInstance().pendingUpdateMatches(offer)) { finish(); return; }
        if(offer!=null && getIntent().getBooleanExtra("policy",false)
                && offer.archiveSha256.equals(getSharedPreferences(PREFERENCES,0).getString("handled",""))) { finish(); return; }
        if(screen!=Screen.CONFIRM) restart();
    }
    @Override protected void onDestroy() {
        // A recreated instance restarts on its own; never stop processes for a destroyed page.
        if(title!=null) title.removeCallbacks(stopProcesses);
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        // Stopping processes is already under way; leaving would only hide its outcome.
        if(screen==Screen.RESTARTING) return;
        if(screen==Screen.CONFIRM) remember();
        finish();
    }
    private boolean remember() {
        return offer==null || !getIntent().getBooleanExtra("policy",false)
            || getSharedPreferences(PREFERENCES,0).edit().putString("handled",offer.archiveSha256).commit();
    }
    private void restart() {
        if(offer!=null && !ShellApplication.requireInstance().pendingUpdateMatches(offer)) { finish(); return; }
        // Persist before stopping processes so a failed activation cannot form an automatic restart loop.
        if(!remember()) { failed(); return; }
        if(screen!=Screen.RESTARTING) show(Screen.RESTARTING);
        long remaining=MIN_PROGRESS_MILLIS-(android.os.SystemClock.uptimeMillis()-progressShown);
        if(remaining>0) title.postDelayed(stopProcesses,remaining); else stopProcesses();
    }
    private void stopProcesses() {
        if(isFinishing() || isDestroyed()) return;
        java.util.function.Consumer<Boolean> result=success-> { if(success) finish(); else failed(); };
        if(CrashRecovery.instance!=null) CrashRecovery.instance.restart(result);
        else ShellRestart.restart(getApplication(),result);
    }
    private void failed() {
        if(isFinishing() || isDestroyed()) return;
        show(Screen.FAILED);
    }

    private View layout() {
        int padding=dp(24);
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView icon=new ImageView(this);
        icon.setImageDrawable(icon());
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(icon,new LinearLayout.LayoutParams(dp(72),dp(72)));

        title=new TextView(this);
        title.setTextAppearance(android.R.style.TextAppearance_Material_Headline);
        title.setGravity(Gravity.CENTER);
        title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(title,margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT),dp(24)));

        message=new TextView(this);
        message.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        android.content.res.ColorStateList secondary=themeColors(android.R.attr.textColorSecondary);
        if(secondary!=null) message.setTextColor(secondary);
        message.setGravity(Gravity.CENTER);
        content.addView(message,margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT),dp(8)));

        progress=new ProgressBar(this);
        progress.setIndeterminate(true);
        content.addView(progress,margins(new LinearLayout.LayoutParams(dp(40),dp(40)),dp(32)));

        actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        content.addView(actions,margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT),dp(32)));

        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        FrameLayout center=new FrameLayout(this);
        FrameLayout.LayoutParams centered=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.CENTER);
        center.addView(content,centered);
        scroll.addView(center,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setPadding(padding,padding,padding,padding);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((view,insets) -> {
            // Needed once targetSdk 35 forces edge-to-edge; system window insets exist on every supported API.
            view.setPadding(padding+insets.getSystemWindowInsetLeft(),padding+insets.getSystemWindowInsetTop(),
                padding+insets.getSystemWindowInsetRight(),padding+insets.getSystemWindowInsetBottom());
            return insets;
        });
        return scroll;
    }

    private void show(Screen next) {
        screen=next;
        actions.removeAllViews();
        switch(next) {
            case CONFIRM:
                title.setText(text("paravoidRestartConfirmTitle","Restart to update?"));
                message.setText(text("paravoidRestartConfirmMessage","%1$s will close and reopen. Ongoing work stops and unsaved changes may be lost. A ready update is applied when it reopens."));
                progress.setVisibility(View.GONE);
                action(text("paravoidRestartDismissButton","Not now"),false,v -> { remember(); finish(); });
                action(text("paravoidRestartConfirmButton","Restart"),true,v -> restart());
                break;
            case RESTARTING:
                progressShown=android.os.SystemClock.uptimeMillis();
                title.setText(text("paravoidRestartProgressTitle","Restarting %1$s"));
                message.setText(text("paravoidRestartProgressMessage","This only takes a moment."));
                progress.setVisibility(View.VISIBLE);
                break;
            case FAILED:
                title.setText(text("paravoidRestartFailedTitle","Restart unavailable"));
                message.setText(text("paravoidRestartFailedMessage","%1$s could not restart. You can try again from the app’s update controls."));
                progress.setVisibility(View.GONE);
                action(text("paravoidRestartCloseButton","Close"),true,v -> finish());
                break;
        }
        actions.setVisibility(actions.getChildCount()==0 ? View.GONE : View.VISIBLE);
    }
    private void action(CharSequence text,boolean primary,View.OnClickListener listener) {
        Button button=new Button(this,null,0,primary ? android.R.style.Widget_Material_Button_Colored
            : android.R.style.Widget_Material_Button_Borderless_Colored);
        button.setText(text);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(8));
        actions.addView(button,params);
    }

    /** A theme string attribute, resolved by name so this shell class never links the library R class. */
    private CharSequence text(String attribute,String fallback) {
        CharSequence value=null;
        int id=getResources().getIdentifier(attribute,"attr",getPackageName());
        if(id!=0) {
            TypedArray array=getTheme().obtainStyledAttributes(new int[] { id });
            try { value=array.getText(0); } finally { array.recycle(); }
        }
        if(value==null) value=ShellText.tr(this,fallback);
        String template=value.toString();
        if(!template.contains("%1$s")) return value;
        try { return String.format(template,label); }
        catch(java.util.IllegalFormatException invalid) { return template; }
    }
    private Drawable icon() {
        int id=getResources().getIdentifier("paravoidRestartIcon","attr",getPackageName());
        if(id!=0) {
            TypedArray array=getTheme().obtainStyledAttributes(new int[] { id });
            try { Drawable custom=array.getDrawable(0); if(custom!=null) return custom; }
            catch(RuntimeException unavailable) { /* Fall back to the launcher icon. */ }
            finally { array.recycle(); }
        }
        return getApplicationInfo().loadIcon(getPackageManager());
    }
    private android.content.res.ColorStateList themeColors(int attribute) {
        TypedArray array=getTheme().obtainStyledAttributes(new int[] { attribute });
        try { return array.getColorStateList(0); } finally { array.recycle(); }
    }
    private static <T extends ViewGroup.MarginLayoutParams> T margins(T params,int top) { params.topMargin=top; return params; }
    private int dp(int value) {
        return (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,value,getResources().getDisplayMetrics());
    }
}
