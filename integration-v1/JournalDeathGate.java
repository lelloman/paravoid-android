import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.nio.file.*;
import java.util.*;

/** Test-only JDWP observer. Exits the real installed process at a selection write boundary. */
public final class JournalDeathGate {
    public static void main(String[] args) throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        Map<String, Connector.Argument> options = connector.defaultArguments();
        options.get("hostname").setValue("127.0.0.1"); options.get("port").setValue(args[0]);
        options.get("timeout").setValue("15000");
        Path marker = Paths.get(args[1]);
        VirtualMachine vm = connector.attach(options);
        try {
            String boundary = args[2];
            ReferenceType record = vm.classesByName("com.lelloman.paravoidandroid.runtime.lifecycle.AtomicRecord").get(0);
            BreakpointRequest stop = vm.eventRequestManager().createBreakpointRequest(
                    record.locationsOfLine(Integer.parseInt(args[3])).get(0));
            stop.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); stop.enable();
            Files.writeString(marker.resolve("ready"), "armed\n");
            long deadline = System.nanoTime() + 180_000_000_000L;
            while (System.nanoTime() < deadline) {
                EventSet events = vm.eventQueue().remove(200);
                if (events == null) continue;
                for (Event event : events) {
                    if (!(event instanceof BreakpointEvent)) continue;
                    ThreadReference thread = ((BreakpointEvent) event).thread();
                    boolean pending = thread.frames().stream().anyMatch(frame ->
                            frame.location().declaringType().name().equals(
                                    "com.lelloman.paravoidandroid.runtime.lifecycle.SelectionJournal")
                                    && frame.location().method().name().equals("pending"));
                    if (!pending) continue;
                    Files.writeString(marker.resolve("reached"), boundary + " pending selection\n");
                    vm.exit(73);
                    return;
                }
                events.resume();
            }
            throw new AssertionError("Pending selection boundary not reached");
        } finally {
            try { vm.dispose(); } catch (VMDisconnectedException expected) { }
        }
    }
}
