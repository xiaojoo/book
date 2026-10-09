#!/usr/bin/env bash
# 本机没有 gradle 命令，用 Gradle 缓存里已解压的 9.7.1 发行版直接跑。
set -euo pipefail
export JAVA_HOME='D:\Program\Java21'
# 用 $HOME 而不是写死的用户目录：这台机的 Windows 用户名不该进公开仓库
GRADLE="$HOME/.gradle/wrapper/dists/gradle-9.7.1-bin/1w1c7tv4s851m17nbqdsro2tv/gradle-9.7.1/bin/gradle.bat"
cd "$(dirname "$0")"
exec "$GRADLE" --console=plain "$@"
