package com.lelloman.paravoidcompat.complete;
import android.app.job.*;
public final class ProbeJob extends JobService {
    public boolean onStartJob(JobParameters parameters) {
        getSharedPreferences("probe", 0).edit().putString("job", getString(R.string.generation)).commit();
        return false;
    }
    public boolean onStopJob(JobParameters parameters) { return true; }
}
