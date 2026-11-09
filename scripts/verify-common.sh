#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
root="$(pwd)"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
find "$root/java/common/src/main/java" "$root/java/common/src/test/java" -name '*.java' -print0 | xargs -0 javac --release 17 -d "$build"
java -cp "$build" com.wanstu.cartograph.core.CoreSelfTest
