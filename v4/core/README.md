# V4 foundation prototype

Standalone Java 21 core. No Minecraft runtime required. The page codec experiment uses the
existing Minecraft 1.21.1 LZ4 Java library (org.lz4:lz4-java:1.8.0, safe Java implementation).
Supply its jar on the compile and runtime classpath. Production dependency review and
packaging are still pending.

PowerShell, from this directory, with LZ4_JAR set to the jar path:

    $src = Get-ChildItem src -Recurse -Filter '*.java' | ForEach-Object FullName
    javac --release 21 -cp $env:LZ4_JAR -d out $src
    $cp = "out;$env:LZ4_JAR"
    foreach ($test in 'FoundationTest','CameraTest','RegionStoreTest','WorkerTest','CapturePumpTest','DensePyramidTest','PageCodecTest') {
        java -Xmx256m -cp $cp "com.wanstu.cartograph.v4.$test"
        if ($LASTEXITCODE -ne 0) { throw "$test failed" }
    }
    java -Xmx256m -cp $cp com.wanstu.cartograph.v4.ScaleTest 64 256
    java -Xmx256m -cp $cp com.wanstu.cartograph.v4.ScaleTest 128 256
    java -Xmx256m -cp $cp com.wanstu.cartograph.v4.ScaleTest 320 256

On Linux use ':' rather than ';' as the classpath separator.
The synthetic benchmark uses repeated pseudo-random samples, 11 exact LODs and durable
transactions. It is not a game benchmark. Optional encoder thread count:
-Dcartograph.v4.encodeThreads=1, 2 (default), or 4.

V4 only: no compatibility or migration, including earlier experimental formats.
Use an empty V4 database. Never point this store at a legacy cache directory.
See docs/CARTOGRAPH_V4_ARCHITECTURE.md for gates and measured limitations.