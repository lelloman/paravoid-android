import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.nio.file.*;
import java.util.*;

/** Test-only JDWP gate: suspend only the verified-download thread until APK replacement kills it. */
public final class StagingGate {
    public static void main(String[] args) throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        Map<String, Connector.Argument> options = connector.defaultArguments();
        options.get("hostname").setValue("127.0.0.1");
        options.get("port").setValue(args[0]);
        options.get("timeout").setValue("15000");
        Path markers = Paths.get(args[1]);
        VirtualMachine vm = connector.attach(options);
        boolean paused = false;
        try {
            MethodExitRequest request = vm.eventRequestManager().createMethodExitRequest();
            request.addClassFilter("com.lelloman.paravoidandroid.contract.CompleteVpkVerifier");
            request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            request.enable();
            Files.writeString(markers.resolve("ready"), "armed\n");
            long deadline = System.nanoTime() + 90_000_000_000L;
            while (System.nanoTime() < deadline) {
                EventSet events = vm.eventQueue().remove(1000);
                if (events == null) continue;
                boolean hold = false;
                for (Event event : events) {
                    if (event instanceof MethodExitEvent
                            && ((MethodExitEvent) event).method().name().equals("verifyDownloaded")) {
                        request.disable(); paused = hold = true;
                        Files.writeString(markers.resolve("verified"), "verified-download-thread-suspended\n");
                    }
                    if (event instanceof VMDisconnectEvent) {
                        if (!paused) throw new AssertionError("VM exited before staging boundary");
                        Files.writeString(markers.resolve("disconnected"), "owner-exited\n");
                        return;
                    }
                }
                if (!hold) events.resume();
            }
            throw new AssertionError("staging gate timed out");
        } finally {
            try { vm.dispose(); } catch (VMDisconnectedException expected) { }
        }
    }
}
