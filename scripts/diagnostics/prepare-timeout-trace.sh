#!/bin/sh
# Explicit test tooling; never loaded by CodeFit or packaged Electron.
set -eu
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:?Java 21 JDK required}"
probe_maven=${CODEFIT_MAVEN:-mvn}
"$probe_maven" -Ptimeout-trace -q test-compile dependency:build-classpath -Dmdep.outputFile=target/timeout-trace-classpath.txt
probe_cp="target/test-classes:target/classes:$(cat target/timeout-trace-classpath.txt)"
mkdir -p target/timeout-trace-classes
if command -v rg >/dev/null 2>&1; then
    probe_sources=$(rg --files src/diagnostics/java -g '*.java')
else
    probe_sources=$(find src/diagnostics/java -name '*.java')
fi
"$JAVA_HOME/bin/javac" --release 21 -cp "$probe_cp" -d target/timeout-trace-classes $probe_sources
printf 'Premain-Class: com.codefit.diagnostics.TimeoutAgent\n\n' > target/timeout-trace-manifest.txt
"$JAVA_HOME/bin/jar" cfm target/timeout-trace-agent.jar target/timeout-trace-manifest.txt -C target/timeout-trace-classes .
printf '%s\n' "target/timeout-trace-agent.jar:$probe_cp" > target/timeout-trace-runtime.txt
