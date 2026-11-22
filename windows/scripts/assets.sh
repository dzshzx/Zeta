#!/bin/bash
# 把桌面版要带到主机上的程序和脚本放进 windows/internal/assets/gen（go:embed 读它，不进 Git）：
# zeta-helper 和 claude-switch 的 linux arm64、amd64 两份，编法同 app/build.gradle.kts 的 GoProgramTask，
# 所以和 APK 里的逐字节一样，主机上按内容哈希装的是同一份；托管脚本和账号脚本照抄 app/src/main/assets。
# 门禁在检查 windows/ 之前跑它；改了 helper/ 或这几个脚本后，本地跑 windows/ 的测试前也先跑一次。
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
out=$root/windows/internal/assets/gen
rm -rf "$out"
mkdir -p "$out"
for arch in arm64 amd64; do
  (
    cd "$root/helper"
    export CGO_ENABLED=0 GOOS=linux GOARCH=$arch
    go build -trimpath -buildvcs=false -ldflags='-s -w -buildid=' -o "$out/zeta-helper/linux-$arch" .
    go build -trimpath -buildvcs=false -ldflags='-s -w -buildid=' -o "$out/claude-switch/linux-$arch" github.com/dzshzx/claude-switch
  )
done
cp "$root/app/src/main/assets/zeta_host.sh" "$root/app/src/main/assets/zeta_accounts.sh" "$out/"
