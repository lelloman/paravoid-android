import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.nio.file.*;
import java.util.*;

/** Disposable fixture only: pause actual selection publication, observe real permission IO. */
public final class SelectionWriteGate {
    public static void main(String[] args) throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        Map<String, Connector.Argument> options = connector.defaultArguments();
        options.get("hostname").setValue("127.0.0.1"); options.get("port").setValue(args[0]);
        options.get("timeout").setValue("15000");
        Path markers = Paths.get(args[1]);
        VirtualMachine vm = connector.attach(options);
        boolean watchIo = args.length < 4 || args[3].equals("io");
        try {
            ReferenceType journal = vm.classesByName("com.lelloman.paravoidandroid.runtime.lifecycle.SelectionJournal").get(0);
            BreakpointRequest stop = vm.eventRequestManager().createBreakpointRequest(journal.locationsOfLine(Integer.parseInt(args[2])).get(0));
            stop.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); stop.enable();
            Files.writeString(markers.resolve("ready"), "armed");
            EventSet paused = null;
            long deadline = System.nanoTime() + 120_000_000_000L;
            while (System.nanoTime() < deadline) {
                if (paused != null && Files.exists(markers.resolve("kill"))) {
                    vm.exit(73); Files.writeString(markers.resolve("dead"), "owner-exited"); return;
                }
                if (paused != null && Files.exists(markers.resolve("resume"))) {
                    paused.resume(); paused = null;
                    if (!watchIo) return;
                }
                EventSet events = vm.eventQueue().remove(100);
                if (events == null) continue;
                boolean hold = false, failed = false;
                for (Event event : events) {
                    if (event instanceof BreakpointEvent) {
                        stop.disable();
                        if (watchIo) {
                            ExceptionRequest errors = vm.eventRequestManager().createExceptionRequest(
                                vm.classesByName("java.io.IOException").get(0), true, true);
                            errors.addThreadFilter(((BreakpointEvent) event).thread());
                            errors.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); errors.enable();
                        }
                        hold = true; paused = events;
                        Files.writeString(markers.resolve("publishing"), "selection-write");
                    }
                    if (event instanceof ExceptionEvent) {
                        ObjectReference error = ((ExceptionEvent) event).exception();
                        Value message = error.getValue(error.referenceType().fieldByName("detailMessage"));
                        System.out.println(error.referenceType().name() + ": " + message);
                        if (error.referenceType().name().equals("java.nio.file.AccessDeniedException") ||
                            message instanceof StringReference && ((StringReference) message).value().contains("Permission denied")) {
                            Files.writeString(markers.resolve("denied"), "real-permission-IO"); failed = true;
                        }
                    }
                    if (event instanceof VMDisconnectEvent) throw new AssertionError("Owner exited before publication IO");
                }
                if (!hold) events.resume();
                if (failed) return;
            }
            throw new AssertionError("Publication gate timed out");
        } finally { try { vm.dispose(); } catch (VMDisconnectedException expected) { } }
    }
}
