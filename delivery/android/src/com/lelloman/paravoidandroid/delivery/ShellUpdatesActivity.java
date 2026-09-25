package com.lelloman.paravoidandroid.delivery;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.View;
import android.widget.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;

/** Framework-only update controls, usable without loading the downstream payload. */
public final class ShellUpdatesActivity extends Activity {
    public interface RestartAction { void restart(java.util.function.Consumer<Boolean> result); }
    private static volatile RestartAction installedRestart;
    private static volatile UpdateControl installedController;
    private UpdateControl controller;
    private LinearLayout content, details;
    private TextView status, description, versions, timing, progressLabel, diagnostics;
    private ProgressBar progress;
    private Switch checks, downloads, unmetered;
    private Spinner retention;
    private Button primary, checkNow, cancel, restart, retryGeneration, detailsToggle;
    private boolean rendering, restarting, expanded;
    private LifecycleSnapshot lastLifecycle;
    private int foreground, secondary, accent;
    private final DeliveryController.Listener listener = snapshot -> runOnUiThread(() -> render(snapshot));

    /** Shell bootstrap installs these controls in the recovery process. */
    public static void installController(UpdateControl controller) { installedController = controller; }
    public static void installRestartAction(RestartAction action) { installedRestart = action; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("App updates");
        if(getActionBar()!=null) getActionBar().hide();
        foreground=color(android.R.attr.textColorPrimary);
        secondary=color(android.R.attr.textColorSecondary);
        accent=color(android.R.attr.colorAccent);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int side=20+Math.max(0,(getResources().getConfiguration().screenWidthDp-720)/2);
        content.setPadding(dp(side),dp(12),dp(side),dp(28));
        scroll.addView(content); setContentView(scroll);
        // Keep controls clear of system bars when downstream apps target edge-to-edge Android versions.
        scroll.setOnApplyWindowInsetsListener((view,insets)-> {
            view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.requestApplyInsets();
        int lightBars=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        View decor=getWindow().getDecorView();
        decor.setSystemUiVisibility(Color.luminance(color(android.R.attr.colorBackground))>0.5f
            ? decor.getSystemUiVisibility()|lightBars : decor.getSystemUiVisibility()&~lightBars);
        Button back=button(content,"‹ Back",view->finish()); back.setContentDescription("Back");
        back.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));
        text(content,"App updates",28,true);
        TextView intro=text(content,"Manage downloads and choose when to apply an update.",15,false);
        intro.setPadding(0,dp(4),0,dp(24));

        LinearLayout summary=card();
        status=text(summary,"Loading update status…",23,true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        description=text(summary,"Connecting to update controls.",15,false);
        description.setPadding(0,dp(8),0,dp(16));
        versions=text(summary,"",15,false);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000); progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams track=new LinearLayout.LayoutParams(-1,dp(8)); track.topMargin=dp(16);
        summary.addView(progress,track);
        progressLabel=text(summary,"",14,false); progressLabel.setVisibility(View.GONE);
        timing=text(summary,"",13,false); timing.setPadding(0,dp(12),0,dp(12));
        primary=button(summary,"Check for updates",view->controller.checkNow()); primary.setEnabled(false);
        GradientDrawable primarySurface=new GradientDrawable(); primarySurface.setCornerRadius(dp(12)); primarySurface.setColor(Color.WHITE);
        primary.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),primarySurface,null));
        primary.setBackgroundTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},
            new int[]{(foreground&0x00ffffff)|0x18000000,accent}));
        primary.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},
            new int[]{secondary,Color.luminance(accent)>0.179f ? Color.BLACK : Color.WHITE}));
        checkNow=button(summary,"Check again",view->controller.checkNow()); checkNow.setVisibility(View.GONE);
        cancel=button(summary,"Cancel download",view->controller.cancelDownload()); cancel.setVisibility(View.GONE);

        controller=installedController;
        if(controller==null) {
            status.setText("Updates unavailable");
            description.setText("Close and reopen the app to try again. If this continues, get a replacement app installer from your distributor.");
            primary.setVisibility(View.GONE); return;
        }
        LinearLayout preferences=card();
        text(preferences,"Automatic updates",19,true);
        text(preferences,"These preferences apply to background updates. You can always check or download manually.",14,false);
        checks=toggle(preferences,"Check for updates automatically");
        downloads=toggle(preferences,"Allow automatic downloads");
        unmetered=toggle(preferences,"Download only on unmetered networks");
        text(preferences,"Unmetered networks usually include Wi-Fi. Installation and restart prompts follow this app’s configuration.",13,false);
        checks.setEnabled(false); downloads.setEnabled(false); unmetered.setEnabled(false);

        detailsToggle=button(content,"Show advanced details",view->setExpanded(!expanded));
        details=new LinearLayout(this); details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(16),dp(8),dp(16),dp(16)); content.addView(details);
        diagnostics=text(details,"",13,false); diagnostics.setTextIsSelectable(true);
        text(details,"Saved previous versions",17,true);
        text(details,"Keep previous downloads for troubleshooting. This does not enable rollback or restore app data.",14,false);
        retention=new Spinner(this);
        retention.setContentDescription("Number of previous versions to keep");
        retention.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Keep none","Keep 1 version","Keep 2 versions","Keep 3 versions"}));
        retention.setMinimumHeight(dp(48)); details.addView(retention); retention.setEnabled(false);
        retention.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                if(!rendering && lastLifecycle!=null && position!=lastLifecycle.retainedPrevious) controller.retainedPrevious(position);
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        restart=button(details,"Restart app…",view->confirmRestart());
        restart.setVisibility(installedRestart==null ? View.GONE : View.VISIBLE); restart.setEnabled(false);
        retryGeneration=button(details,"Retry failed app version…",view->confirmGenerationRetry());
        retryGeneration.setVisibility(View.GONE);
        setExpanded(state!=null && state.getBoolean("detailsExpanded"));
    }
    @Override protected void onStart() { super.onStart(); if(controller!=null) controller.listen(listener); }
    @Override protected void onStop() { if(controller!=null) controller.unlisten(listener); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("detailsExpanded",expanded); super.onSaveInstanceState(state);
    }
    private void setExpanded(boolean value) {
        expanded=value; details.setVisibility(value ? View.VISIBLE : View.GONE);
        detailsToggle.setText(value ? "Hide advanced details" : "Show advanced details");
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private int color(int attribute) {
        TypedValue value=new TypedValue(); getTheme().resolveAttribute(attribute,value,true);
        return value.resourceId!=0 ? getColorStateList(value.resourceId).getDefaultColor() : value.data;
    }
    private TextView text(LinearLayout parent,String value,int size,boolean heading) {
        TextView view=new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(heading ? foreground : secondary);
        if(heading) { view.setTypeface(null,Typeface.BOLD); view.setAccessibilityHeading(true); }
        view.setPadding(0,dp(4),0,dp(4)); parent.addView(view); return view;
    }
    private LinearLayout card() {
        LinearLayout panel=new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20),dp(18),dp(20),dp(18));
        GradientDrawable background=new GradientDrawable(); background.setCornerRadius(dp(20));
        background.setColor((foreground&0x00ffffff)|0x08000000);
        background.setStroke(dp(1),(foreground&0x00ffffff)|0x22000000); panel.setBackground(background);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.bottomMargin=dp(18);
        content.addView(panel,params); return panel;
    }
    private Button button(LinearLayout parent,String label,View.OnClickListener action) {
        Button button=new Button(this); button.setText(label); button.setAllCaps(false);
        button.setMinimumHeight(dp(48)); button.setPadding(dp(16),dp(8),dp(16),dp(8));
        button.setTextColor(accent); button.setStateListAnimator(null);
        GradientDrawable surface=new GradientDrawable(); surface.setCornerRadius(dp(12)); surface.setColor(Color.TRANSPARENT);
        GradientDrawable mask=new GradientDrawable(); mask.setCornerRadius(dp(12)); mask.setColor(Color.WHITE);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf((accent&0x00ffffff)|0x22000000),surface,mask));
        button.setOnClickListener(action);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(4); params.bottomMargin=dp(4);
        parent.addView(button,params); return button;
    }
    private Switch toggle(LinearLayout parent,String label) {
        Switch control=new Switch(this); control.setText(label); control.setTextSize(15);
        control.setTextColor(foreground); control.setMinimumHeight(dp(56));
        control.setPadding(0,dp(8),0,dp(8)); control.setSwitchPadding(dp(16));
        parent.addView(control,new LinearLayout.LayoutParams(-1,-2));
        control.setOnCheckedChangeListener((view,checked)-> {
            if(!rendering) {
                downloads.setEnabled(checks.isChecked());
                unmetered.setEnabled(checks.isChecked() && downloads.isChecked());
                controller.preferences(new DeliveryPreferences(checks.isChecked(),downloads.isChecked(),unmetered.isChecked()));
            }
        });
        return control;
    }
    private void render(DeliveryController.Snapshot snapshot) {
        if(isFinishing() || isDestroyed()) return;
        rendering=true;
        try {
            lastLifecycle=snapshot.lifecycle;
            boolean checking=snapshot.activity==DeliveryController.Activity.CHECKING;
            boolean downloading=snapshot.activity==DeliveryController.Activity.DOWNLOADING;
            boolean staging=snapshot.activity==DeliveryController.Activity.STAGING;
            boolean busy=checking || downloading || staging;
            ExpectedArchive pending=lastLifecycle==null ? null : lastLifecycle.pending;
            ExpectedArchive active=lastLifecycle==null ? null : lastLifecycle.active;
            String title, message, action="Check for updates";
            View.OnClickListener command=view-> { primary.setEnabled(false); controller.checkNow(); };
            if(checking) { title="Checking for updates"; message="Looking for a compatible version of this app."; action="Checking…"; }
            else if(downloading) { title="Downloading update"; message="You can keep using the app while the download finishes."; action="Downloading…"; }
            else if(staging) { title="Preparing update"; message="Verifying the download and getting it ready to use."; action="Preparing…"; }
            else if(pending!=null) {
                title="Ready to restart"; message="Version "+pending.payloadVersion+" is downloaded. Restart the app to start using it.";
                action="Restart to apply update"; command=view->confirmRestart();
                if(installedRestart==null) { action="Check for updates"; command=view->controller.checkNow(); message="Version "+pending.payloadVersion+" is ready. It will be applied the next time the app starts."; }
            } else if(snapshot.activity==DeliveryController.Activity.WAITING_TO_RETRY) {
                title="Waiting to retry"; message="The last attempt could not finish. The app will try again when the scheduled retry can run.";
                action="Retry now"; command=view-> { primary.setEnabled(false); controller.retry(); };
            } else if(snapshot.errorCode!=null || snapshot.activity==DeliveryController.Activity.ERROR) {
                title="Update couldn’t finish"; message=errorMessage(snapshot.errorCode);
                action="Try again"; command=view-> { primary.setEnabled(false); controller.retry(); };
                if("OFFER_CHANGED".equals(snapshot.errorCode) || "NO_COMPATIBLE_RELEASE".equals(snapshot.errorCode)
                        || "SHELL_UPDATE_REQUIRED".equals(snapshot.errorCode)) {
                    action="Check again"; command=view-> { primary.setEnabled(false); controller.checkNow(); };
                }
            } else if(snapshot.available!=null) {
                title="Update available"; message="Version "+snapshot.available.payloadVersion+" is available to download.";
                action="Download update"; ExpectedArchive offer=snapshot.available;
                command=view-> { primary.setEnabled(false); controller.updateNow(offer); };
            } else if(snapshot.activity==DeliveryController.Activity.CANCELLED) {
                title="Update cancelled"; message="Check again whenever you’re ready to continue.";
            } else if(lastLifecycle!=null && lastLifecycle.availability==Availability.EMPTY) {
                title="Download the app to begin"; message="Check for an available version to get started.";
            } else if(lastLifecycle!=null && lastLifecycle.availability==Availability.RECOVERY) {
                title="An app repair is needed"; message="Check for a newer version. If none is available, contact your distributor for a replacement installer.";
            } else { title=snapshot.lastCheckSeconds>0 ? "No update available" : "Ready to check"; message="Check for the latest compatible version of this app."; }
            status.setText(restarting ? "Restarting app…" : title);
            description.setText(restarting ? "Stopping app work and opening the app again." : message);
            versions.setText("Installed version   "+(lastLifecycle==null ? "Unavailable" : active==null ? "Not installed" : Long.toString(active.payloadVersion))
                +(pending==null ? "" : "\nReady to install      "+pending.payloadVersion));
            timing.setText(snapshot.lastCheckSeconds==0 ? "Not checked yet" : "Last checked "+relative(snapshot.lastCheckSeconds));
            if(snapshot.activity==DeliveryController.Activity.WAITING_TO_RETRY && snapshot.nextDueSeconds>0)
                timing.append("\nNext attempt "+relative(snapshot.nextDueSeconds));
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
            progress.setIndeterminate(!downloading || snapshot.totalBytes<=0);
            progressLabel.setVisibility(downloading ? View.VISIBLE : View.GONE);
            if(downloading) {
                String transferred=Formatter.formatShortFileSize(this,Math.max(0,snapshot.bytes));
                if(snapshot.totalBytes>0) {
                    int amount=(int)Math.min(1000,Math.max(0,(double)snapshot.bytes/snapshot.totalBytes*1000));
                    progress.setProgress(amount);
                    progressLabel.setText(transferred+" of "+Formatter.formatShortFileSize(this,snapshot.totalBytes)+" · "+amount/10+"%");
                } else progressLabel.setText(transferred+" downloaded");
            }
            primary.setVisibility(busy ? View.GONE : View.VISIBLE);
            primary.setText(action); primary.setOnClickListener(command); primary.setEnabled(!busy && !restarting);
            checkNow.setVisibility(!busy && !action.equals("Check again") && !action.equals("Check for updates")
                && (snapshot.available!=null || pending!=null || snapshot.errorCode!=null) ? View.VISIBLE : View.GONE);
            checkNow.setEnabled(!restarting);
            cancel.setVisibility(checking || downloading || snapshot.activity==DeliveryController.Activity.WAITING_TO_RETRY ? View.VISIBLE : View.GONE);
            cancel.setText(checking ? "Cancel check" : downloading ? "Cancel download" : "Cancel retry"); cancel.setEnabled(!restarting);
            checks.setChecked(snapshot.preferences.automaticChecks); checks.setEnabled(!restarting);
            downloads.setChecked(snapshot.preferences.automaticDownloads); downloads.setEnabled(checks.isChecked() && !restarting);
            unmetered.setChecked(snapshot.preferences.unmeteredOnly); unmetered.setEnabled(checks.isChecked() && downloads.isChecked() && !restarting);
            downloads.setAlpha(downloads.isEnabled() ? 1f : 0.5f); unmetered.setAlpha(unmetered.isEnabled() ? 1f : 0.5f);
            restart.setEnabled(!busy && !restarting);
            retention.setEnabled(lastLifecycle!=null && !busy && !restarting);
            if(lastLifecycle!=null) retention.setSelection(lastLifecycle.retainedPrevious);
            diagnostics.setText("Installed release: "+identity(active)+"\nAvailable release: "+identity(snapshot.available)
                +"\nPending release: "+identity(pending)
                +(lastLifecycle==null ? "\nApp state unavailable" : "\nApp state: "+lastLifecycle.availability.name()
                    +"\nUpdate storage: "+Formatter.formatShortFileSize(this,lastLifecycle.storageBytes)
                    +(lastLifecycle.error==null ? "" : "\nApp state error: "+lastLifecycle.error.name()))
                +(snapshot.errorCode==null ? "" : "\nUpdate error: "+snapshot.errorCode));
            retryGeneration.setVisibility(RecoveryActions.canOfferGenerationRetry(lastLifecycle) ? View.VISIBLE : View.GONE);
            retryGeneration.setEnabled(!busy && !restarting);
        } finally { rendering=false; }
    }
    private CharSequence relative(long seconds) {
        return DateUtils.getRelativeTimeSpanString(seconds*1000,System.currentTimeMillis(),DateUtils.MINUTE_IN_MILLIS);
    }
    private String errorMessage(String code) {
        if("CREDENTIAL_UNAVAILABLE".equals(code)) return "Update access is unavailable. Try again, or get a newly authorized installer from your distributor. Your installed app remains available offline.";
        if("CLOCK_INVALID".equals(code)) return "Check the device’s date and time, then try again.";
        if("INSUFFICIENT_STORAGE".equals(code)) return "Free up some space on this device, then try again.";
        if("OFFER_CHANGED".equals(code)) return "The available version changed. Check again before downloading.";
        if("SHELL_UPDATE_REQUIRED".equals(code)) return "Get a new app installer from your distributor to continue receiving updates.";
        if("NO_COMPATIBLE_RELEASE".equals(code)) return "No compatible version is currently available for this app.";
        return "The update could not be completed. Try again, or open advanced details if the problem continues.";
    }
    private void confirmRestart() {
        if(installedRestart==null || restarting) return;
        new AlertDialog.Builder(this).setTitle("Restart app now?")
            .setMessage("This stops ongoing app work, including playback and jobs. Unsaved changes may be lost. A downloaded update will be applied when the app opens again.")
            .setNegativeButton("Not now",null).setPositiveButton("Restart",(dialog,which)-> {
                restarting=true; primary.setEnabled(false); restart.setEnabled(false); checkNow.setEnabled(false);
                checks.setEnabled(false); downloads.setEnabled(false); unmetered.setEnabled(false);
                cancel.setEnabled(false); retention.setEnabled(false); retryGeneration.setEnabled(false);
                status.setText("Restarting app…"); description.setText("Stopping app work and opening the app again.");
                installedRestart.restart(success->runOnUiThread(()-> {
                    if(isFinishing() || isDestroyed()) return;
                    if(success) finish();
                    else {
                        restarting=false; controller.refreshSnapshot();
                        new AlertDialog.Builder(this).setTitle("Couldn’t restart the app")
                            .setMessage("Stop any ongoing app work and try again. You can also close the app from Android settings and reopen it.")
                            .setPositiveButton("OK",null).show();
                    }
                }));
            }).show();
    }
    private void confirmGenerationRetry() {
        if(!RecoveryActions.canOfferGenerationRetry(lastLifecycle)) return;
        ExpectedArchive captured=lastLifecycle.active;
        new AlertDialog.Builder(this).setTitle("Retry failed app version?")
            .setMessage("Retry version "+captured.payloadVersion+" the next time the app starts? It previously failed to start and may fail again. This does not restore older app data.")
            .setNegativeButton("Cancel",null).setPositiveButton("Retry version",(dialog,which)->controller.retryQuarantinedAfterConfirmation(captured)).show();
    }
    private static String identity(ExpectedArchive release) {
        return release==null ? "None" : release.releaseId+" (version "+release.payloadVersion+")";
    }
}
