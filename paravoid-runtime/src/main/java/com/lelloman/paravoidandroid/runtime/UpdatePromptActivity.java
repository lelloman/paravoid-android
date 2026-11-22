package com.lelloman.paravoidandroid.runtime;

import android.app.*;
import android.os.Bundle;
import com.lelloman.paravoidandroid.contract.Protocol.ExpectedArchive;

/** Shell-owned confirmation, including when the payload is unavailable. */
public final class UpdatePromptActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ExpectedArchive offer=UpdateWire.offer(getIntent().getBundleExtra("offer"));
        if(offer==null) { finish(); return; }
        new AlertDialog.Builder(this).setTitle(ShellText.tr(this,"Update available"))
            .setMessage(ShellText.tr(this,"Install version %1$s? It will be used the next time the app starts.",offer.payloadVersion))
            .setPositiveButton(ShellText.tr(this,"Install"),(dialog,which)-> { UpdateRuntime.engine(this).thenAccept(engine->engine.updateNow(offer)); finish(); })
            .setNegativeButton(ShellText.tr(this,"Not now"),(dialog,which)-> { UpdateRuntime.engine(this).thenAccept(engine->engine.dismiss(offer)); finish(); })
            .setOnCancelListener(dialog->finish()).show();
    }
}
