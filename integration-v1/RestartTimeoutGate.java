import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.nio.file.*;
import java.util.*;

/** Test-only scheduling gate. No return values, clocks or process lists are replaced. */
public final class RestartTimeoutGate {
    static final String TYPE = "com.lelloman.paravoidandroid.runtime.RestartProcessGate";
    static void arm(VirtualMachine vm, ReferenceType type, String[] args) throws Exception {
        for (int n = 2; n < 5; n++) {
            BreakpointRequest request = vm.eventRequestManager().createBreakpointRequest(type.locationsOfLine(Integer.parseInt(args[n])).get(0));
            request.putProperty("boundary", n);
            request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); request.enable();
        }
    }
    public static void main(String[] args) throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        Map<String, Connector.Argument> options = connector.defaultArguments();
        options.get("hostname").setValue("127.0.0.1"); options.get("port").setValue(args[0]);
        options.get("timeout").setValue("15000");
        Path markers = Paths.get(args[1]);
        VirtualMachine vm = connector.attach(options);
        try {
            List<ReferenceType> loaded = vm.classesByName(TYPE);
            if (loaded.isEmpty()) {
                ClassPrepareRequest request = vm.eventRequestManager().createClassPrepareRequest();
                request.addClassFilter(TYPE); request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); request.enable();
            } else arm(vm, loaded.get(0), args);
            Files.writeString(markers.resolve("ready"), "armed\n");
            EventSet paused = null;
            long started = 0, deadline = System.nanoTime() + 120_000_000_000L;
            int polls = 0;
            while (System.nanoTime() < deadline) {
                if (paused != null && Files.exists(markers.resolve("resume"))) {
                    started = System.nanoTime(); paused.resume(); paused = null;
                }
                EventSet events = vm.eventQueue().remove(100);
                if (events == null) continue;
                boolean hold = false, done = false;
                for (Event event : events) {
                    if (event instanceof ClassPrepareEvent) {
                        arm(vm, ((ClassPrepareEvent) event).referenceType(), args); event.request().disable();
                    }
                    if (event instanceof BreakpointEvent) {
                        int boundary = (Integer) event.request().getProperty("boundary");
                        if (boundary == 2) {
                            event.request().disable(); hold = true; paused = events;
                            Files.writeString(markers.resolve("stopped"), "initial-kill-pass-finished\n");
                        } else if (boundary == 3) polls++;
                        else {
                            long millis = (System.nanoTime() - started) / 1_000_000;
                            if (started == 0 || millis < 4900 || polls < 2) throw new AssertionError("Not a polling timeout: " + millis + "/" + polls);
                            Files.writeString(markers.resolve("timeout"), "elapsedMs=" + millis + ";polls=" + polls + "\n");
                            done = true;
                        }
                    }
                    if (event instanceof VMDisconnectEvent) throw new AssertionError("Recovery process exited");
                }
                if (!hold) events.resume();
                if (done) return;
            }
            throw new AssertionError("restart gate timed out");
        } finally { try { vm.dispose(); } catch (VMDisconnectedException expected) { } }
    }
}
