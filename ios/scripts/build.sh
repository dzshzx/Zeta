#!/bin/bash
# 打 iOS 版：build.sh [ipa|test|simulator]（默认 ipa）。先编核心（core.sh），再由 XcodeGen 照 project.yml 生成 Zeta.xcodeproj，交给 xcodebuild。
#   ipa        真机的 Release 构建，不签名（CODE_SIGNING_ALLOWED=NO），装进 Payload/ 打成 ios/build/Zeta-v<版本>.ipa，最后一行打印它的路径；
#              装到手机上前由用户用自己的开发者账号重签。
#   test       在 iOS 模拟器上跑 ZetaTests（核心桥的冒烟测试）；模拟器见 simulator.sh。
#   simulator  模拟器的 Debug 构建，产物在 ios/build/sim/，给 CI 截图用。
# 版本名是干净的 X.Y.Z 时是正式版（Zeta，com.dzshzx.zeta），别的是测试版（Zeta Dev，com.dzshzx.zeta.dev），两个能同时装着，同桌面版和手机版的做法。
# 要在装了 Xcode 和 XcodeGen（brew install xcodegen）的 macOS 上跑，先跑过 windows/scripts/assets.sh。
set -euo pipefail
ios=$(cd "$(dirname "$0")/.." && pwd)
what=${1:-ipa}
bash "$ios/scripts/core.sh"
read -r code version <"$ios/build/version"
short=${version%%-*}
if [[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  bundle=com.dzshzx.zeta name=Zeta icon=AppIcon
else
  bundle=com.dzshzx.zeta.dev name="Zeta Dev" icon=AppIconDev
fi
(cd "$ios" && xcodegen generate --quiet)
settings=(PRODUCT_BUNDLE_IDENTIFIER="$bundle" ASSETCATALOG_COMPILER_APPICON_NAME="$icon" ZETA_DISPLAY_NAME="$name" MARKETING_VERSION="$short" CURRENT_PROJECT_VERSION="$code")
xcodebuild -version
case $what in
  ipa)
    xcodebuild -project "$ios/Zeta.xcodeproj" -scheme Zeta -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
      -derivedDataPath "$ios/build/derived" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY= "${settings[@]}" build
    stage=$ios/build/ipa
    rm -rf "$stage"
    mkdir -p "$stage/Payload"
    cp -R "$ios/build/derived/Build/Products/Release-iphoneos/Zeta.app" "$stage/Payload/"
    ipa=$ios/build/Zeta-v$version.ipa
    rm -f "$ipa"
    (cd "$stage" && zip -qry "$ipa" Payload)
    echo "$ipa"
    ;;
  test)
    xcodebuild -project "$ios/Zeta.xcodeproj" -scheme Zeta -destination "platform=iOS Simulator,id=$(bash "$ios/scripts/simulator.sh")" \
      -derivedDataPath "$ios/build/derived" "${settings[@]}" test
    ;;
  simulator)
    xcodebuild -project "$ios/Zeta.xcodeproj" -scheme Zeta -configuration Debug -sdk iphonesimulator \
      -destination "platform=iOS Simulator,id=$(bash "$ios/scripts/simulator.sh")" -derivedDataPath "$ios/build/derived" "${settings[@]}" build
    rm -rf "$ios/build/sim"
    mkdir -p "$ios/build/sim"
    cp -R "$ios/build/derived/Build/Products/Debug-iphonesimulator/Zeta.app" "$ios/build/sim/"
    echo "$bundle" >"$ios/build/sim/bundle"
    ;;
  *)
    echo "build.sh: 不认识 $what（ipa、test、simulator）" >&2
    exit 1
    ;;
esac
