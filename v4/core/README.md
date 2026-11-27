# V4 foundation prototype

No Minecraft dependencies. Reference sparse implementation, not production cache. Compile with a JDK supporting Java 21:

    javac --release 21 -d out src/main/java/com/wanstu/cartograph/v4/*.java src/test/java/com/wanstu/cartograph/v4/*.java
    java -cp out com.wanstu.cartograph.v4.FoundationTest

See docs/CARTOGRAPH_V4_ARCHITECTURE.md for gates. No game integration or performance claims yet.
