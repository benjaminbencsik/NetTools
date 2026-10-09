#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
javac -d "$test_dir" app/src/main/java/com/benbencsik/nettools/NetworkTools.java tests/NetworkToolsTest.java
java -cp "$test_dir" com.benbencsik.nettools.NetworkToolsTest
