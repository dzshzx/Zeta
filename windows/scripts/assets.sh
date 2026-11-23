#!/bin/bash
# 把桌面版要带到主机上的程序和脚本放进 windows/internal/assets/gen（go:embed 读它，不进 Git）：
# zeta-helper 和 claude-switch 的 linux arm64、amd64 两份，编法同 app/build.gradle.kts 的 GoProgramTask，
# 所以和 APK 里的逐字节一样，主机上按内容哈希装的是同一份；托管脚本照抄 helper/host；排公式的 RaTeX（wasm 和 KaTeX 字体，从 npm 下载，按 sha256 核对，缓存在用户缓存目录）；还有关于页列的开源许可全文（见文件末尾）。
# 门禁在检查 windows/ 之前跑它；改了 helper/、这几个脚本或 windows/ 的依赖后，本地跑 windows/ 的测试前也先跑一次。
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
out=$root/windows/internal/assets/gen
rm -rf "$out"
mkdir -p "$out"
# zeta-helper 的构建序号（main.build）：helper/ 最后一次提交的时刻，同 build.gradle.kts 的 helperBuild
build=$(git -C "$root" log -1 --format=%ct -- helper 2>/dev/null || true)
for arch in arm64 amd64; do
  (
    cd "$root/helper"
    export CGO_ENABLED=0 GOOS=linux GOARCH=$arch
    go build -trimpath -buildvcs=false -ldflags="-s -w -buildid= -X=main.build=${build:-0}" -o "$out/zeta-helper/linux-$arch" .
    go build -trimpath -buildvcs=false -ldflags='-s -w -buildid=' -o "$out/claude-switch/linux-$arch" github.com/dzshzx/claude-switch
  )
done
cp "$root/helper/host/zeta_host.sh" "$out/"

# 排公式的 RaTeX（同手机版的 ratex-android，版本取 gradle/libs.versions.toml 的 ratex）：npm 包 ratex-wasm 里编好的 wasm 和 KaTeX 的 TTF 字体，
# 桌面版用 wazero 跑它（windows/internal/mathtex）。包按 sha256 核对，存在用户缓存目录里，核对过的就不再下载。
# 手机版升级 RaTeX 后这里对不上版本会失败：把 ratex_version、ratex_sha256 改成新版本的（npm 包的 sha256）。
ratex_version=0.1.16
ratex_sha256=8d43782743633071097884384e534476b27e95f5249a95df3eac031b40d3fcf8
want=$(sed -n 's/^ratex = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")
[[ $want == "$ratex_version" ]] || { echo "assets.sh: 手机版的 RaTeX 是 ${want:-（没读出）}，这里钉的是 $ratex_version：改 ratex_version 和 ratex_sha256" >&2; exit 1; }
cache=${XDG_CACHE_HOME:-$HOME/.cache}/zeta-assets
tgz=$cache/ratex-wasm-$ratex_version.tgz
if ! [[ -f $tgz ]] || ! sha256sum --status -c <<<"$ratex_sha256  $tgz"; then
  mkdir -p "$cache"
  curl -fsSL --retry 3 -o "$tgz.part" "https://registry.npmjs.org/ratex-wasm/-/ratex-wasm-$ratex_version.tgz"
  sha256sum --status -c <<<"$ratex_sha256  $tgz.part" || { echo "assets.sh: 下载的 ratex-wasm-$ratex_version.tgz 和钉的 sha256 对不上" >&2; exit 1; }
  mv "$tgz.part" "$tgz"
fi
mkdir -p "$out/ratex/fonts"
tar -xzf "$tgz" -O package/pkg/ratex_wasm_bg.wasm >"$out/ratex/ratex.wasm"
tar -xzf "$tgz" -C "$out/ratex/fonts" --strip-components=2 --wildcards 'package/fonts/KaTeX_*.ttf'

# 第三方许可证全文，关于页列它们（windows/internal/ui/about）：Windows 程序链接的模块（按 windows/amd64 编的 windows/ 全部包）记作 app，
# 带到主机上的 zeta-helper 和 claude-switch 链接的（按 linux 编的 helper/ 和它的 tool）记作 host，各取模块目录里的许可证文件（包所在目录里另有许可证的，那个包单列一项）；
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
  # 每个模块一行（一定要有许可证）；包所在目录不是模块根目录的，那个包也一行（目录里另有许可证时单列，像 mygo 的 internal/shader 是 Apache-2.0）
  (cd "$dir" && GOOS=$goos GOARCH=amd64 CGO_ENABLED=0 go list -deps \
    -f '{{if not .Standard}}{{with .Module}}{{if not .Main}}{{.Path}} {{.Version}} {{.Dir}} module{{if ne .Dir $.Dir}}{{"\n"}}{{$.ImportPath}} {{.Version}} {{$.Dir}} package{{end}}{{end}}{{end}}{{end}}' "$@") |
    sed -e '/^$/d' -e '\#^github.com/dzshzx/Zeta/#d' | LC_ALL=C sort -u |
    while read -r path version from kind; do
      file=$(find "$from" -maxdepth 1 -type f \( -name 'LICENSE*' -o -name 'LICENCE*' -o -name 'COPYING*' \) | LC_ALL=C sort | head -1)
      if [[ -z $file ]]; then
        [[ $kind == package ]] && continue
        echo "assets.sh: $path $version 里没有许可证文件" >&2
        exit 1
      fi
      name=$(tr / _ <<<"$path@$version")
      # 两边用同一个模块的同一个版本时文件只放一份（模块缓存里的文件只读，再 cp 一次会失败）
      [[ -e $lic/$name ]] || cp "$file" "$lic/$name"
      printf '%s\t%s\t%s\t%s\n' "$where" "$path" "$version" "$name" >>"$lic/index.tsv"
    done
}
licenses app "$root/windows" windows ./...
# widget/icons.go 里照搬了 COUI 2 自带图标（coui-icons）的路径数据，它是 Apache-2.0：许可全文同 APK 带的那份，版本取手机版用的 COUI 2
coui_version=$(sed -n 's/^coui2 = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")
[[ -n $coui_version ]] || { echo "assets.sh: gradle/libs.versions.toml 里没有 coui2 的版本" >&2; exit 1; }
cp "$root/app/src/main/assets/licenses/Apache-2.0.txt" "$lic/coui-icons"
printf 'app\tgithub.com/suqi8/coui（coui-icons 图标）\t%s\tcoui-icons\n' "$coui_version" >>"$lic/index.tsv"
# 排公式的 RaTeX（wasm，MIT；它静态链接的 Rust 标准库和 crate 见它的 Cargo.lock）和它用的 KaTeX 字体（OFL-1.1，版权行同 THIRD_PARTY.md）
{
  tar -xzf "$tgz" -O package/LICENSE
  printf '\nratex.wasm 静态链接的 Rust 标准库和 crate 按各自的许可证（MIT、Apache-2.0、Unicode-3.0）分发，清单见 https://github.com/erweixin/RaTeX/blob/v%s/Cargo.lock\n' "$ratex_version"
} >"$lic/ratex"
printf 'app\tgithub.com/erweixin/RaTeX（ratex-wasm）\t%s\tratex\n' "$ratex_version" >>"$lic/index.tsv"
{
  printf 'Copyright (c) 2009-2010, Design Science, Inc. (<www.mathjax.org>)\n'
  printf 'Copyright (c) 2014-2018 Khan Academy (<www.khanacademy.org>),\n'
  printf 'with Reserved Font Names KaTeX_AMS, KaTeX_Caligraphic, KaTeX_Fraktur, KaTeX_Main, KaTeX_Math, KaTeX_SansSerif, KaTeX_Script, KaTeX_Size1, KaTeX_Size2, KaTeX_Size3, KaTeX_Size4, KaTeX_Typewriter.\n\n'
  cat "$root/app/src/main/assets/licenses/OFL-1.1.txt"
} >"$lic/katex-fonts"
printf 'app\tgithub.com/KaTeX/KaTeX（KaTeX 字体）\t随 RaTeX %s\tkatex-fonts\n' "$ratex_version" >>"$lic/index.tsv"
licenses host "$root/helper" linux ./... tool
