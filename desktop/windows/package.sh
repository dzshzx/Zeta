#!/bin/bash
# 在 Linux 上打 Zeta 桌面版的 Windows 安装包：package.sh <程序目录> <版本> <输出的 .msi>。平时经 tools/gradle :desktop:packageWindows 调用。
# 1. Windows 的 JRE（Temurin，版本和 sha256 定在下面）下载一次，存在 ${XDG_CACHE_HOME:-~/.cache}/zeta-desktop，每次用前核对 sha256；
# 2. 安装目录 = runtime/（这份 JRE）+ app/（Gradle 的 windowsApp：本模块的 jar 和 Windows 版的依赖）+ zeta.ico；
# 3. wixl-heat 列出全部文件，wixl 按 Zeta.wxs 打成 MSI：按用户安装到 %LOCALAPPDATA%\Programs\Zeta，不要管理员；
#    开始菜单的快捷方式直接起 runtime\bin\javaw.exe。要 wixl（Debian/Ubuntu 的 wixl 包）。
# 升 JRE：换 JRE_URL 和 JRE_SHA256（Adoptium 的 API：/v3/assets/latest/25/hotspot?os=windows&architecture=x64&image_type=jre）。
set -euo pipefail
JRE_URL=https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5.1%2B2/OpenJDK25U-jre_x64_windows_hotspot_25.0.5.1_2.zip
JRE_SHA256=3260a1b9899f30ac80d737daafc053042c525249f52d066101fbf81d78d73f25

app=$1 version=$2 out=$3
here=$(cd "$(dirname "$0")" && pwd)
command -v wixl >/dev/null || { echo "package.sh: 缺 wixl（Debian/Ubuntu 上 apt install wixl）" >&2; exit 1; }

cache=${XDG_CACHE_HOME:-$HOME/.cache}/zeta-desktop
mkdir -p "$cache"
jre=$cache/jre-$JRE_SHA256.zip
if ! [[ -f $jre ]] || ! echo "$JRE_SHA256  $jre" | sha256sum -c --status; then
  curl -fsSL --retry 3 -o "$jre.part" "$JRE_URL"
  echo "$JRE_SHA256  $jre.part" | sha256sum -c --status || { echo "package.sh: 下载的 JRE 校验和不对" >&2; exit 1; }
  mv "$jre.part" "$jre"
fi

work=$(dirname "$out")/msi-work
rm -rf "${work:?}"
mkdir -p "$work/jre" "$work/image"
unzip -q "$jre" -d "$work/jre"
mv "$work"/jre/*/ "$work/image/runtime"
cp -r "$app" "$work/image/app"
cp "$here/../icons/zeta.ico" "$work/image/zeta.ico"

(cd "$work" && find image -type f | LC_ALL=C sort |
  wixl-heat -p image/ --component-group AppFiles --var var.SourceDir --directory-ref INSTALLDIR >files.wxs)
# MSI 的版本只认 X.Y.Z 三段数字：预发布（0.7.2-dev.3+gabc1234）取前面的 0.7.2
wixl -a x64 -D "Version=${version%%-*}" -D "SourceDir=$work/image" -o "$out" "$here/Zeta.wxs" "$work/files.wxs"
echo "$out"
