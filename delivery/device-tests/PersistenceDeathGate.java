import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.nio.file.*;
import java.util.*;

/** Installed debuggable fixture only: terminate at a production persistence statement. */
public final class PersistenceDeathGate {
    public static void main(String[] args) throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        Map<String, Connector.Argument> options = connector.defaultArguments();
        options.get("hostname").setValue("127.0.0.1"); options.get("port").setValue(args[0]);
        options.get("timeout").setValue("15000");
        Path markers = Paths.get(args[1]);
        VirtualMachine vm = connector.attach(options);
        try {
            ReferenceType type = vm.classesByName("com.lelloman.paravoidandroid.delivery." + args[2]).get(0);
            List<Location> locations = type.locationsOfLine(Integer.parseInt(args[3]));
            if (locations.isEmpty()) throw new AssertionError("Missing production line information");
            for (Location location : locations) {
                BreakpointRequest stop = vm.eventRequestManager().createBreakpointRequest(location);
                if (args.length > 4 && args.length < 6) stop.addCountFilter(Integer.parseInt(args[4]));
                stop.setSuspendPolicy(EventRequest.SUSPEND_ALL); stop.enable();
            }
            Files.writeString(markers.resolve("ready"), "armed\n");
            long deadline = System.nanoTime() + 90_000_000_000L;
            while (System.nanoTime() < deadline) {
                EventSet events = vm.eventQueue().remove(100);
                if (events == null) continue;
                for (Event event : events) {
                    if (event instanceof BreakpointEvent) {
                        BreakpointEvent breakpoint = (BreakpointEvent) event;
                        if (args.length >= 6) {
                            // The unified writer also persists intent/attempt/schedule.
                            // Filter by the real Properties argument, not a fragile
                            // number of writes or a test-mutated persisted record.
                            StackFrame frame = breakpoint.thread().frame(0);
                            LocalVariable variable = frame.visibleVariableByName("p");
                            if (variable == null) throw new AssertionError("Missing writer Properties debug variable");
                            ObjectReference record = (ObjectReference) frame.getValue(variable);
                            Method getter = record.referenceType().allMethods().stream()
                                .filter(method -> method.name().equals("getProperty") && method.argumentTypeNames().size() == 1)
                                .findFirst().orElseThrow(() -> new AssertionError("Missing Properties getter on " + record.referenceType().name()));
                            Value phase = record.invokeMethod(breakpoint.thread(), getter,
                                List.of(vm.mirrorOf("phase")), ObjectReference.INVOKE_SINGLE_THREADED);
                            if (!(phase instanceof StringReference) || !args[5].equals(((StringReference) phase).value())) continue;
                            if (args.length >= 7) {
                                Value attempts = record.invokeMethod(breakpoint.thread(), getter,
                                    List.of(vm.mirrorOf("attempts")), ObjectReference.INVOKE_SINGLE_THREADED);
                                if (!(attempts instanceof StringReference) || !args[6].equals(((StringReference) attempts).value())) continue;
                            }
                        }
                        Files.writeString(markers.resolve("reached"), args[2] + ":" + args[3]);
                        vm.exit(73); return;
                    }
                    if (event instanceof VMDisconnectEvent) throw new AssertionError("Process exited before persistence boundary");
                }
                events.resume();
            }
            throw new AssertionError("Persistence boundary timed out");
        } finally { try { vm.dispose(); } catch (VMDisconnectedException expected) { } }
    }
}
