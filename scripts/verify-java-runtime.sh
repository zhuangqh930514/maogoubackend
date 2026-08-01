#!/usr/bin/env bash
set -euo pipefail

if [[ "${JAVA_HOME:-}" == "" ]]; then
  echo "JAVA_HOME 未设置。猫狗智投后端测试需要 JDK 17。" >&2
  exit 1
fi

java_major="$(${JAVA_HOME}/bin/java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)"
if [[ "${java_major}" != "17" ]]; then
  echo "当前 JAVA_HOME 不是 JDK 17：${JAVA_HOME}（检测到 ${java_major:-unknown}）" >&2
  exit 1
fi

echo "Java runtime:"
"${JAVA_HOME}/bin/java" -version
echo "Maven runtime:"
mvn -version
