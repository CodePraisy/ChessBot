#!/usr/bin/env bash
# Builds and opens Praise's Chess. Usage: ./run.sh [--dev-engine]
# Works in Git Bash / WSL on Windows and in a normal macOS/Linux terminal. Needs JDK 25 (javac + java).
set -e
cd "$(dirname "$0")"

JAVAC=javac; JAVA=java
if [ -n "$JAVA_HOME" ]; then JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"; fi

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *) SEP=':' ;;
esac

echo "Compiling..."
if ! "$JAVAC" -d bin -sourcepath src src/chessUI.java; then
  echo "Compile failed (is JDK 25 installed and on PATH?)."
  read -rp "Press Enter to close." _
  exit 1
fi

echo "Starting Praise's Chess..."
exec "$JAVA" -cp "bin${SEP}src" chessUI "$@"
