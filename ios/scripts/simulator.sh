#!/bin/bash
# 打印一台能用的 iPhone 模拟器的 UDID：ZETA_SIMULATOR 给了就用它，否则挑最新的 iOS 运行时里的第一台 iPhone。build.sh 和 CI 的截图用。
set -euo pipefail
if [[ -n ${ZETA_SIMULATOR:-} ]]; then
  echo "$ZETA_SIMULATOR"
  exit 0
fi
udid=$(xcrun simctl list devices available -j |
  jq -r '[.devices | to_entries[] | select(.key | test("iOS")) | {runtime: .key, device: .value[] | select(.name | startswith("iPhone"))}]
    | sort_by(.runtime | sub(".*iOS-"; "") | split("-") | map(tonumber)) | last | .device.udid // empty')
[[ -n $udid ]] || { echo "simulator.sh: 没有能用的 iPhone 模拟器" >&2; exit 1; }
echo "$udid"
