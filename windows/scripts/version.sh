#!/bin/bash
# 打印这个提交的版本号和版本名，用空格隔开（桌面版 installer/build.sh、iOS 版 ios/scripts/build.sh 用）。算法同根目录 build.gradle.kts 的
# commitCount、versionLabel（手机版用的那个）：版本号是 HEAD 的提交数，发版 tag 上版本名是干净的 X.Y.Z，别的提交是 X.Y.(Z+1)-dev.N+g<提交号>。
set -euo pipefail
dir=$(cd "$(dirname "$0")" && pwd)
code=$(git -C "$dir" rev-list --count HEAD)
hash=$(git -C "$dir" rev-parse --short=7 HEAD)
described=$(git -C "$dir" describe --tags --long --match 'v[0-9]*.[0-9]*.[0-9]*' 2>/dev/null || true) # v0.7.1-3-gabc1234
if [[ $described =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)-([0-9]+)-g[0-9a-f]+$ ]]; then
  major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]} ahead=${BASH_REMATCH[4]}
  if [[ $ahead == 0 ]]; then
    version=$major.$minor.$patch
  else
    version=$major.$minor.$((patch + 1))-dev.$ahead+g$hash
  fi
else
  version=0.7.0-dev.$code+g$hash
fi
echo "$code $version"
