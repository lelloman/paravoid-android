package com.lelloman.paravoidcompat.os;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import org.json.JSONObject;

/** Registration is unconditional and in a stable order on every Activity creation. */
final class ResultProbe {
    private final ComponentActivity activity;
    private final ActivityResultLauncher<Intent> launcher;
    private String run;
    private ProbeParcel checkpoint;
    private boolean restored;
    private boolean cancel;
    private boolean envelope;

    ResultProbe(ComponentActivity activity) {
        this.activity = activity;
        launcher = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::received);
    }

    void start(Bundle state, String token, boolean canceled) {
        envelope = SavedStateEnvelopeProbe.check(activity);
        restored = state != null;
        cancel = canceled;
        run = restored ? state.getString("resultRun") : token;
        checkpoint = restored ? state.getParcelable("checkpoint") : new ProbeParcel("saved-λ-" + run);
        if (!restored) {
            android.content.SharedPreferences prefs = activity.getSharedPreferences("os-probe", Context.MODE_PRIVATE);
            prefs.edit().putInt("resultLaunches", prefs.getInt("resultLaunches", 0) + 1).commit();
            launcher.launch(new Intent().setClassName("com.lelloman.paravoidcompat.os.peer",
                "com.lelloman.paravoidcompat.os.peer.PeerActivity")
                .putExtra("scenario", "result").putExtra("run", run).putExtra("cancel", cancel));
        }
    }

    void save(Bundle state) {
        if (run == null) return;
        state.putString("resultRun", run);
        state.putParcelable("checkpoint", checkpoint);
        activity.getSharedPreferences("os-probe", Context.MODE_PRIVATE).edit()
            .putString("resultSavedRun", run).putInt("resultSavedPid", android.os.Process.myPid()).commit();
    }

    private void received(ActivityResult result) {
        try {
            Intent data = result.getData();
            android.content.SharedPreferences prefs = activity.getSharedPreferences("os-probe", Context.MODE_PRIVATE);
            int callbacks = prefs.getInt("resultCallbacks", 0) + 1;
            JSONObject report = new JSONObject().put("run", run).put("restored", restored)
                .put("pid", android.os.Process.myPid()).put("callbacks", callbacks)
                .put("launches", prefs.getInt("resultLaunches", 0))
                .put("savedParcel", checkpoint != null && ("saved-λ-" + run).equals(checkpoint.value))
                .put("envelope", envelope)
                .put("payloadLoader", result.getClass().getClassLoader() == activity.getClass().getClassLoader())
                .put("code", result.getResultCode() == (cancel ? Activity.RESULT_CANCELED : Activity.RESULT_OK))
                .put("data", cancel ? data == null : data != null && run.equals(data.getStringExtra("run"))
                    && "reply-λ".equals(data.getStringExtra("reply"))
                    && data.getIntExtra("uid", -1) > 0 && data.getIntExtra("uid", -1) != android.os.Process.myUid());
            prefs.edit().putInt("resultCallbacks", callbacks).putString("activityResult", report.toString()).commit();
        } catch (Exception error) {
            activity.getSharedPreferences("os-probe", Context.MODE_PRIVATE).edit()
                .putString("resultError", error.toString()).commit();
        }
    }
}
