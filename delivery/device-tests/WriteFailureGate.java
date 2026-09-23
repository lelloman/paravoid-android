import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.nio.file.*;
import java.util.*;

/** Test-only gate: pause a second archive/component write, then witness an ENOSPC IOException. */
public final class WriteFailureGate {
    public static void main(String[] args) throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        Map<String, Connector.Argument> options = connector.defaultArguments();
        options.get("hostname").setValue("127.0.0.1"); options.get("port").setValue(args[0]);
        options.get("timeout").setValue("15000");
        Path markers = Paths.get(args[1]);
        VirtualMachine vm = connector.attach(options);
        try {
            ReferenceType store = vm.classesByName("com.lelloman.paravoidandroid.runtime.lifecycle.GenerationStore").get(0);
            BreakpointRequest stop = vm.eventRequestManager().createBreakpointRequest(store.locationsOfLine(Integer.parseInt(args[2])).get(0));
            stop.addCountFilter(2); stop.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            BreakpointRequest component = null;
            if (args.length > 3) {
                component = vm.eventRequestManager().createBreakpointRequest(store.locationsOfLine(Integer.parseInt(args[3])).get(0));
                component.addCountFilter(1); component.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); component.enable();
            } else stop.enable();
            Files.writeString(markers.resolve("ready"), "armed\n");
            EventSet paused = null;
            long deadline = System.nanoTime() + 300_000_000_000L;
            while (System.nanoTime() < deadline) {
                if (paused != null && Files.exists(markers.resolve("resume"))) { paused.resume(); paused = null; }
                EventSet events = vm.eventQueue().remove(100);
                if (events == null) continue;
                boolean hold = false, failed = false;
                for (Event event : events) {
                    if (event instanceof BreakpointEvent) {
                        if (event.request() == component) {
                            component.disable(); stop.enable();
                            continue;
                        }
                        stop.disable();
                        ReferenceType io = vm.classesByName("java.io.IOException").get(0);
                        ExceptionRequest errors = vm.eventRequestManager().createExceptionRequest(io, true, true);
                        errors.addThreadFilter(((BreakpointEvent) event).thread());
                        errors.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); errors.enable();
                        hold = true; paused = events;
                        Files.writeString(markers.resolve("writing"), "one-write-completed\n");
                    }
                    if (event instanceof ExceptionEvent) {
                        ObjectReference error = ((ExceptionEvent) event).exception();
                        Value message = error.getValue(error.referenceType().fieldByName("detailMessage"));
                        System.out.println(error.referenceType().name() + ": " + message);
                        System.out.flush();
                        if (message instanceof StringReference &&
                                (((StringReference) message).value().contains("ENOSPC") ||
                                 ((StringReference) message).value().contains("No space left on device"))) {
                            Files.writeString(markers.resolve("enospc"), "ENOSPC\n"); failed = true;
                        }
                    }
                    if (event instanceof VMDisconnectEvent) throw new AssertionError("App exited before ENOSPC evidence");
                }
                if (!hold) events.resume();
                if (failed) return;
            }
            throw new AssertionError("write failure gate timed out");
        } finally { try { vm.dispose(); } catch (VMDisconnectedException expected) { } }
    }
}
