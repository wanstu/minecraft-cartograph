package com.wanstu.cartographv3;

import com.sun.management.ThreadMXBean;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/** Real, read-only chunk samples verify equivalent inputs and measure parser allocation. */
public final class SurfaceNbtBenchmark {
    private record Sample(String name, byte[] data) {}
    private record Measurement(long nanos, long bytes, long checksum) {}
    public static void main(String[] args) throws Exception {
        List<Sample> samples = new ArrayList<>();
        try (var files = Files.newDirectoryStream(Path.of(args[0]), "*.cnbt")) {
            for (Path file : files) samples.add(new Sample(file.getFileName().toString(), Files.readAllBytes(file)));
        }
        if (samples.size() != 8) throw new AssertionError("Expected eight real samples");
        int fullChunks = 0;
        for (Sample sample : samples) {
            CompoundTag full = read(sample, false), narrow = read(sample, true);
            CompoundTag expected = full.copy();
            Set<String> fields = Set.of("Status", "xPos", "zPos", "Heightmaps", "sections", "structures", "Structures");
            for (String name : new ArrayList<>(MapNbt.keys(expected))) if (!fields.contains(name)) expected.remove(name);
            ListTag sections = MapNbt.list(expected, "sections", Tag.TAG_COMPOUND);
            for (int i = 0; i < sections.size(); i++) {
                CompoundTag section = MapNbt.compound(sections, i);
                section.remove("SkyLight"); section.remove("BlockLight");
            }
            if (!expected.equals(narrow)) throw new AssertionError("Changed map input: " + sample.name());
            String status = MapNbt.string(full, "Status");
            if (status.equals("full") || status.equals("minecraft:full")) fullChunks++;
        }
        // Warm both implementations before collecting allocation and elapsed time.
        measure(samples, false, 20); measure(samples, true, 20);
        Measurement full = measure(samples, false, 150), narrow = measure(samples, true, 150);
        Measurement narrow2 = measure(samples, true, 150), full2 = measure(samples, false, 150);
        long fullBytes = full.bytes() + full2.bytes(), narrowBytes = narrow.bytes() + narrow2.bytes();
        long fullNanos = full.nanos() + full2.nanos(), narrowNanos = narrow.nanos() + narrow2.nanos();
        String json = "{\n  \"real_samples\": "+samples.size()+",\n  \"full_samples\": "+fullChunks+
                ",\n  \"input_equivalence_passed\": true,\n  \"reads_per_variant\": "+(samples.size()*300)+
                ",\n  \"full_allocated_bytes\": "+fullBytes+",\n  \"selective_allocated_bytes\": "+narrowBytes+
                ",\n  \"allocation_reduction_percent\": "+(100.0*(fullBytes-narrowBytes)/fullBytes)+
                ",\n  \"full_elapsed_ms\": "+fullNanos/1e6+",\n  \"selective_elapsed_ms\": "+narrowNanos/1e6+
                ",\n  \"elapsed_reduction_percent\": "+(100.0*(fullNanos-narrowNanos)/fullNanos)+
                ",\n  \"checksum\": "+(full.checksum()+narrow.checksum())+"\n}\n";
        Path output = Path.of(args[1]); Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, json); System.out.println(json);
    }
    private static CompoundTag read(Sample sample, boolean selective) throws IOException {
        int id = sample.data()[0] & 255;
        RegionFileVersion version = RegionFileVersion.fromId(id);
        if (version == null || version == RegionFileVersion.VERSION_CUSTOM) throw new IOException("Unsupported sample compression");
        try (var compressed = new ByteArrayInputStream(sample.data(),1,sample.data().length-1);
             InputStream input = version.wrap(compressed)) {
            return selective ? RegionNbtReader.readSurfaceData(input)
                    : NbtIo.read(new DataInputStream(input), NbtAccounter.create(16*1024*1024));
        }
    }
    private static Measurement measure(List<Sample> samples, boolean selective, int rounds) throws Exception {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        long allocated = bean.getThreadAllocatedBytes(thread), started = System.nanoTime(), checksum = 0;
        for (int round = 0; round < rounds; round++) for (Sample sample : samples) {
            CompoundTag tag = read(sample, selective);
            checksum += MapNbt.intValue(tag,"xPos") + MapNbt.intValue(tag,"zPos");
        }
        return new Measurement(System.nanoTime()-started, bean.getThreadAllocatedBytes(thread)-allocated, checksum);
    }
}
