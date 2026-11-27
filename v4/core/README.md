# V4 foundation prototype

Standalone Java 21 core. No Minecraft runtime or external compression dependency required.
The current format uses lossless adaptive byte-plane offsets and CRC32C.

PowerShell, from this directory:

    $src = Get-ChildItem src -Recurse -Filter '*.java' | ForEach-Object FullName
    javac --release 21 -d out $src
    $cp = "out"
    foreach ($test in 'FoundationTest','CameraTest','RegionStoreTest','WorkerTest','CapturePumpTest','DensePyramidTest','PageCodecTest','FreshRegionBuilderTest','PageFrameCodecTest','CheckpointTest','WorldGeographyTest','ActiveChunkSchedulerTest','SavedDirtyIndexTest','AnvilRegionReaderTest','SavedChunkSurfaceTest','WorldIoServiceTest','OfflineRegionBackfillTest','ArchiveReconcilerTest','MapPresentationTest','MapTileCoverageTest','ReliefMeshTest','MapProjectionTest','MapTransformTest','MapRayPickerTest','RetainedGroupsTest','ContourLinesTest','ContourOverlayCacheTest','ContourDrapeTest','MarkerStoreTest','StructureIndexTest','StructureViewTest','NamedRegionTest','StructureCatalogTest','SurfaceAnchorTest','MapLayersTest','ContourLabelVisibilityTest','MapHudLayoutTest','MapViewOptionsTest','MapContextLayoutTest','MapIconsTest','PlayerHeadingTest','MapDisplaySettingsTest') {
        java -Xmx256m -cp $cp "com.wanstu.cartograph.v4.$test"
        if ($LASTEXITCODE -ne 0) { throw "$test failed" }
    }
    java -Xmx256m -cp $cp com.wanstu.cartograph.v4.ScaleTest 64 256
    java -Xmx256m -cp $cp com.wanstu.cartograph.v4.ScaleTest 128 256
    java -Xmx256m -cp $cp com.wanstu.cartograph.v4.ScaleTest 320 256

The synthetic benchmark uses repeated pseudo-random samples, 11 exact LODs and durable
transactions. It is not a game benchmark. Optional encoder thread count:
-Dcartograph.v4.encodeThreads=1, 2 (default), or 4.

V4 only: no compatibility or migration, including earlier experimental formats.
Use an empty V4 database. Never point this store at a legacy cache directory.
See docs/CARTOGRAPH_V4_ARCHITECTURE.md for gates and measured limitations.
Experimental fresh-shard benchmark (102400 chunks, 25 shards, 2 workers, 256 MiB):

    java '-Dcartograph.v4.encodeThreads=1' '-XX:ActiveProcessorCount=4' -Xmx256m -cp $cp com.wanstu.cartograph.v4.ShardScaleTest 5 2 256 fresh
    java '-Dcartograph.v4.encodeThreads=1' '-XX:ActiveProcessorCount=4' -Xmx256m -cp $cp com.wanstu.cartograph.v4.ShardScaleTest 5 2 256 unique

The fresh mode uses prebuilt repeated primitive snapshots; unique mode generates
independent coordinate-seeded synthetic snapshots inside the timed workload.
These are experimental shard builders, not the Minecraft runtime or render page assembly.
Larger independent-snapshot benchmark (262144 chunks, 2 workers):

    java '-Dcartograph.v4.encodeThreads=1' '-XX:ActiveProcessorCount=4' -Xmx256m -cp $cp com.wanstu.cartograph.v4.ShardScaleTest 8 2 256 unique

The worker stage timings are summed elapsed time across workers, not CPU time.
The JVM active processor setting is not a hard operating-system CPU limit.
Million-chunk independent-snapshot benchmark (1,048,576 chunks, 256 shards, two workers):

    java '-Dcartograph.v4.encodeThreads=1' '-XX:ActiveProcessorCount=4' -Xmx256m -cp $cp com.wanstu.cartograph.v4.ShardScaleTest 16 2 256 unique
Fresh-shard benchmarks now also report checkpoint recovery scan bytes, cross-shard cold overview assembly and warm immutable page cache requests. These are not rendered frames.

Real region read-only probe:

    java -Xmx256m -cp out com.wanstu.cartograph.v4.SavedRegionProbe <region-file> -64 384 128

For actual game input development see ../neoforge-1.21.1/README.md.