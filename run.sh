#!/usr/bin/env bash
# Build and run with just a JDK (17+). No Maven/Gradle needed.
#   ./run.sh        -> runs the demo (Main)
#   ./run.sh test   -> runs the tests
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac --release 17 -d out $(find src -name '*.java')
if [ "$1" == "test" ]; then
  java -cp out com.lld.inventory.InventoryTests
else
  java -cp out com.lld.inventory.Main
fi
