#!/bin/bash
# SessionStart hook for Claude Code cloud sessions.
#
# The cloud container ships Java 21 and Maven, but this project compiles with
# --release 25 (see CLAUDE.md: Java 25, the Mandrel ceiling). Without this hook
# every cloud session would fail at `./mvnw test` before touching any code.
#
# Local machines are left alone: `.sdkmanrc` + `sdk env` own the toolchain there.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

JDK_HOME=/usr/lib/jvm/java-25-openjdk-amd64

# Ubuntu's own archive carries OpenJDK 25, which is the only JDK 25 source the
# session's egress policy allows. Adoptium, SDKMAN, Corretto and Azul are all
# blocked, so Temurin (the local and CI distribution) is not an option here.
# For running JVM tests any OpenJDK 25 build is equivalent.
if [ ! -x "$JDK_HOME/bin/java" ]; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq --no-install-recommends openjdk-25-jdk-headless
fi

# JAVA_HOME in the container points at 21 and mvnw honours it, so override it
# for the rest of the session.
export JAVA_HOME="$JDK_HOME"
export PATH="$JDK_HOME/bin:$PATH"
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export JAVA_HOME=\"$JDK_HOME\""
    echo "export PATH=\"$JDK_HOME/bin:\$PATH\""
  } >> "$CLAUDE_ENV_FILE"
fi

# Warm the Maven cache. The container is snapshotted after this hook, so the
# Quarkus BOM, plugins and test dependencies only get downloaded once rather
# than on every session's first build. Tests themselves are skipped: a red
# test should fail a session's work, not its startup.
cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}"
./mvnw -B -q -DskipTests test

java -version 2>&1 | grep -v JAVA_TOOL_OPTIONS | head -1
