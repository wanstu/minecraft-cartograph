import java.nio.file.Path;
import java.util.*;
import jdk.jfr.consumer.*;

/** Stream only scalar event fields, avoiding expansion of JFR constant-pool metadata. */
class JfrPhaseEvents {
    static String quote(String value) {
        if (value == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch(c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 32) b.append(String.format("\\u%04x",(int)c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }
    public static void main(String[] args) throws Exception {
        long from=Long.parseLong(args[1]), until=Long.parseLong(args[2]);
        Set<String> wanted=Set.of("jdk.ExecutionSample","jdk.NativeMethodSample",
            "jdk.GCPhasePause","jdk.JavaMonitorEnter","jdk.FileRead","jdk.FileWrite",
            "jdk.ObjectAllocationSample","jdk.ThreadPark");
        try (RecordingFile file=new RecordingFile(Path.of(args[0]))) {
            while(file.hasMoreEvents()) {
                RecordedEvent event=file.readEvent();
                String type=event.getEventType().getName();
                if (!wanted.contains(type)) continue;
                if(args.length>3 && args[3].equals("gc") && !type.equals("jdk.GCPhasePause")) continue;
                long time=event.getStartTime().toEpochMilli();
                if (time<from || time>until) continue;
                RecordedThread thread=event.hasField("sampledThread") ? event.getThread("sampledThread") : event.getThread();
                String name=thread==null ? "?" : thread.getJavaName();
                StringJoiner methods=new StringJoiner(",","[","]");
                RecordedStackTrace trace=event.getStackTrace();
                if(trace!=null) for(RecordedFrame frame: trace.getFrames()) {
                    RecordedMethod method=frame.getMethod();
                    methods.add(quote(method.getType().getName()+"."+method.getName()));
                }
                int gcId=event.hasField("gcId") ? event.getInt("gcId") : -1;
                String path=event.hasField("path") ? event.getString("path") : null;
                long weight=event.hasField("weight") ? event.getLong("weight") : 0;
                String allocationClass = event.hasField("objectClass") ? event.getClass("objectClass").getName() : null;
                System.out.println("{\"type\":"+quote(type)+",\"time\":"+time+
                    ",\"duration_ms\":"+(event.getDuration().toNanos()/1e6)+
                    ",\"gc_id\":"+gcId+",\"thread\":"+quote(name)+",\"methods\":"+methods+
                    ",\"path\":"+quote(path)+",\"weight\":"+weight+
                    ",\"allocation_class\":"+quote(allocationClass)+"}");
            }
        }
    }
}
