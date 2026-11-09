#!/usr/bin/env python3
"""Static architecture checks, not a substitute for real-world GameTest."""
from pathlib import Path
root=Path(__file__).resolve().parent.parent
code=root/"java/adapters/1.21.1-neoforge/src/main/java/com/wanstu/cartograph"
index=(code/"WorldSurfaceIndex.java").read_text()
sampler=(code/"SurfaceSampler.java").read_text()
atlas=(code/"AtlasMapServer.java").read_text()
exports=(code/"AtlasExports.java").read_text()
mod=(code/"CartographMod.java").read_text()
for name,source in (("AtlasMapServer",atlas),("AtlasExports",exports)):
    assert "SurfaceSampler.surface(" not in source, name+": synchronous scanner regression"
    assert "WorldSurfaceIndex.of(" in source, name+": no shared index"
    assert "WorldSurfaceIndex.State.PENDING" in source, name+": no pending handling"
for token in ("new ArrayBlockingQueue<>(MAX_PENDING_DISK)",
              "MAX_CACHED_CHUNKS = 8192", "ConcurrentLinkedQueue<Completed>",
              "readers.execute(", "readers.shutdownNow()",
              "submittedRevision()", "getChunkNow(",
              "ServerStoppedEvent", "Source.SAVED", "Source.LOADED"):
    assert token in index, "missing index safety rule: "+token
for token in ("public record SavedRequest(Path regionDir",
              "public static Optional<SurfaceTile> saved(SavedRequest request)",
              "McaChunkReader.readNbt(", "public static Optional<SurfaceTile> loaded(ServerLevel level"):
    assert token in sampler, "missing sampling rule: "+token
assert "public static Optional<SurfaceTile> surface(ServerLevel" not in sampler
disk=sampler.split("public static Optional<SurfaceTile> saved(SavedRequest request)",1)[1].split("private static SurfaceTile sampleLoaded(",1)[0]
assert "getChunkSource()" not in disk and "ServerLevel" not in disk, "worker references live world"
assert "WorldSurfaceIndex::serverStopped" in mod
assert "CartographAsyncSurfaceProbe" not in mod
print("PASS async index no tick I/O, bounded queue, live/saved separation, revision safety, cleanup")
