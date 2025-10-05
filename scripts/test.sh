#!/bin/bash
# 1) compile to a temp dir
TMP=/tmp/jtreg-classes
rm -rf "$TMP" && mkdir -p "$TMP"
"/home/oli/Documents/education/eth/msc-thesis/code/jdk/build/linux-x86_64-server-fastdebug/jdk/bin/javac" \
  -d "$TMP" \
  /home/oli/Documents/education/eth/msc-thesis/code/jdk/test/hotspot/jtreg/compiler/codegen/Test6431242.java

# 2a) If using javap, confirm main exists:
"/home/oli/Documents/education/eth/msc-thesis/code/jdk/build/linux-x86_64-server-fastdebug/jdk/bin/javap" \
  -classpath "$TMP" -public compiler.codegen.Test6431242 | grep main

# 2b) Run it:
"/home/oli/Documents/education/eth/msc-thesis/code/jdk/build/linux-x86_64-server-fastdebug/jdk/bin/java" \
  -cp "$TMP" compiler.codegen.Test6431242
