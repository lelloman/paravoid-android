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
        new AlertDialog.Builder(this).setTitle("Update available")
            .setMessage("Install version "+offer.payloadVersion+"? It will be used the next time the app starts.")
            .setPositiveButton("Install",(dialog,which)-> { UpdateRuntime.engine(this).thenAccept(engine->engine.updateNow(offer)); finish(); })
            .setNegativeButton("Not now",(dialog,which)-> { UpdateRuntime.engine(this).thenAccept(engine->engine.dismiss(offer)); finish(); })
            .setOnCancelListener(dialog->finish()).show();
    }
}
