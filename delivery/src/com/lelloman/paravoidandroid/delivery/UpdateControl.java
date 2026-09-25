package com.lelloman.paravoidandroid.delivery;
import com.lelloman.paravoidandroid.contract.Protocol.ExpectedArchive;
import com.lelloman.paravoidandroid.updates.UpdateSchedule;
/** UI-neutral command port. Android's remote implementation never exposes transport objects. */
public interface UpdateControl {
    void listen(DeliveryController.Listener listener);
    void unlisten(DeliveryController.Listener listener);
    void refreshSnapshot();
    void foreground();
    void checkNow();
    void updateNow();
    void updateNow(ExpectedArchive offer);
    default void dismiss(ExpectedArchive offer) {}
    void retry();
    void cancelDownload();
    void preferences(DeliveryPreferences value);
    void schedule(UpdateSchedule value);
    void retainedPrevious(int count);
    void retryQuarantinedAfterConfirmation(ExpectedArchive release);
}
