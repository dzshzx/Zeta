#!/bin/bash
# 把桌面版要带到主机上的程序和脚本放进 windows/internal/assets/gen（go:embed 读它，不进 Git）：
# zeta-helper 和 claude-switch 的 linux arm64、amd64 两份，编法同 app/build.gradle.kts 的 GoProgramTask，
# 所以和 APK 里的逐字节一样，主机上按内容哈希装的是同一份；托管脚本照抄 helper/host，账号脚本照抄 app/src/main/assets；还有关于页列的开源许可全文（见文件末尾）。
# 门禁在检查 windows/ 之前跑它；改了 helper/、这几个脚本或 windows/ 的依赖后，本地跑 windows/ 的测试前也先跑一次。
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
cp "$root/helper/host/zeta_host.sh" "$out/"

# 第三方许可证全文，关于页列它们（windows/internal/ui/about）：Windows 程序链接的模块（按 windows/amd64 编的 windows/ 全部包）记作 app，
# 带到主机上的 zeta-helper 和 claude-switch 链接的（按 linux 编的 helper/ 和它的 tool）记作 host，各取模块目录里的许可证文件；
# 两边都静态链接 Go 标准库和运行时，取 GOROOT 的 LICENSE；Zeta 自己的是仓根 LICENSE（windows/ 经 replace 引用的 helper 模块也是 Zeta 自己的，不另列）。index.tsv 一行一项：用在哪、模块、版本、文件名。
lic=$out/licenses
mkdir -p "$lic"
cp "$root/LICENSE" "$lic/Zeta"
go_version=$(cd "$root/windows" && go env GOVERSION)
cp "$(cd "$root/windows" && go env GOROOT)/LICENSE" "$lic/Go"
printf '%s\tGo\t%s\tGo\n' app "$go_version" host "$go_version" >"$lic/index.tsv"
licenses() { # <用在哪> <模块目录> <GOOS> <包模式...>
  local where=$1 dir=$2 goos=$3
  shift 3
  (cd "$dir" && GOOS=$goos GOARCH=amd64 CGO_ENABLED=0 go list -deps \
    -f '{{if not .Standard}}{{with .Module}}{{if not .Main}}{{.Path}} {{.Version}} {{.Dir}}{{end}}{{end}}{{end}}' "$@") |
    sed -e '/^$/d' -e '\#^github.com/dzshzx/Zeta/#d' | LC_ALL=C sort -u |
    while read -r path version moddir; do
      file=$(find "$moddir" -maxdepth 1 -type f \( -name 'LICENSE*' -o -name 'LICENCE*' -o -name 'COPYING*' \) | LC_ALL=C sort | head -1)
      [[ -n $file ]] || {
        echo "assets.sh: $path $version 里没有许可证文件" >&2
        exit 1
      }
      name=$(tr / _ <<<"$path@$version")
      # 两边用同一个模块的同一个版本时文件只放一份（模块缓存里的文件只读，再 cp 一次会失败）
      [[ -e $lic/$name ]] || cp "$file" "$lic/$name"
      printf '%s\t%s\t%s\t%s\n' "$where" "$path" "$version" "$name" >>"$lic/index.tsv"
    done
}
licenses app "$root/windows" windows ./...
licenses host "$root/helper" linux ./... tool
