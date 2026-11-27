#!/bin/bash
# 编 iOS 版的核心：hub/cmd/zeta-ios 按 c-archive 编成真机和模拟器两份静态库（都是 arm64），连同头文件和 module map 合成
# ios/build/ZetaCore.xcframework，Swift 里 import ZetaCore。版本号、版本名同桌面版（tools/version.sh），经 -ldflags -X 写进
# hub/appversion：不是干净 X.Y.Z 的构建在主机上用 zeta-dev 包（同手机测试版）。
# 要在装了 Xcode 的 macOS 上跑，先跑过 hub/scripts/assets.sh（编进核心的 zeta-helper 等）。
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
out=$root/ios/build
min=$(sed -n 's/^ *iOS: "\(.*\)"$/\1/p' "$root/ios/project.yml")
[[ -n $min ]] || {
  echo "core.sh: project.yml 里没读出 deploymentTarget 的 iOS 版本" >&2
  exit 1
}
read -r code version release < <(bash "$root/tools/version.sh")
pkg=github.com/dzshzx/Zeta/hub/appversion
ldflags="-s -w -X $pkg.versionName=$version -X $pkg.versionCode=$code"
rm -rf "$out/core" "$out/ZetaCore.xcframework"

build() { # <目录名> <SDK> <clang 的 -target>
  local dir=$out/core/$1 sdk
  sdk=$(xcrun --sdk "$2" --show-sdk-path)
  mkdir -p "$dir/include"
  (
    cd "$root/hub"
    CC=$(xcrun --sdk "$2" -f clang)
    export CC CGO_ENABLED=1 GOOS=ios GOARCH=arm64
    export CGO_CFLAGS="-isysroot $sdk -target $3" CGO_LDFLAGS="-isysroot $sdk -target $3"
    go build -trimpath -buildvcs=false -buildmode=c-archive -ldflags "$ldflags" -o "$dir/libzeta.a" ./cmd/zeta-ios
  )
  mv "$dir/libzeta.h" "$dir/include/"
  printf 'module ZetaCore {\n  header "libzeta.h"\n  export *\n}\n' >"$dir/include/module.modulemap"
}
# 两份互不相干，同时编
build device iphoneos "arm64-apple-ios$min" &
device=$!
build simulator iphonesimulator "arm64-apple-ios$min-simulator"
wait "$device"
xcodebuild -create-xcframework \
  -library "$out/core/device/libzeta.a" -headers "$out/core/device/include" \
  -library "$out/core/simulator/libzeta.a" -headers "$out/core/simulator/include" \
  -output "$out/ZetaCore.xcframework"
echo "$code $version $release" >"$out/version"
