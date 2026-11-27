#!/usr/bin/env bash
# Zeta 的门禁，也是 wt land 合入前跑的检查。工作站和手机 Debian 里都能跑：bash scripts/check.sh
# 核对仓里没有 Python；Kotlin 按 ktfmt 排版、函数认知复杂度不超过上限、文件不超过行数上限
# （scripts/format.sh）；检查六个 Go 模块，helper/ 的 zeta-helper、tools/release 的发版工具、tools/vdisplay 的虚拟屏驱动 tools/vd、
# client/、hub/ 的客户端共用代码和 windows/ 的 Go 桌面版（版本、格式、
# vet、staticcheck、测试、认知复杂度、第三方模块只许各自清单里的，helper/go.mod 按 tool 编进 APK 的程序另有一份清单）；
# 再构建 debug 包（Kotlin 编译、资源、dex，含编 zeta-helper 和 claude-switch），跑单元测试；跑完后已跟踪文件须与开始时一致。
# 合入时（wt land）单元测试不跑截图测试（shots 包里类名以 Shots 结尾的，依据见 app/build.gradle.kts 的测试任务）。
# 构建和单元测试在改动全落在它们读不到的路径里时整个跳过（见下面的 gradle_blind）。别的检查每次都跑。
# ZETA_GATE_FULL=1 是全量：构建照跑，单元测试一个不落，Go 的测试不取缓存。每晚的 CI 和发版前跑的是它。
# 装到手机和真机上的体验不在此列，见 CLAUDE.md「构建与测试」。
set -euo pipefail
cd "$(dirname "$0")/.."

tracked_state() { git status --porcelain --untracked-files=no && git diff HEAD | git hash-object --stdin; }
before=$(tracked_state)

# 这次要合入的改动涉及的文件，一行一个：相对 origin/master 分叉点的全部改动（已提交、未提交的都算，改名记新旧两个），
# 加上未跟踪、未忽略的新文件。wt land 先把分支变基到刚拉取的 origin/master 上再跑门禁，分叉点就是它；
# 在 master 自己的提交上（CI、release.yml）分叉点是 HEAD，没有未提交的改动时输出为空。
# 不在 git 仓里、没有 origin/master、git 出错时失败。
changed_files() {
  local base
  base=$(git merge-base HEAD refs/remotes/origin/master 2>/dev/null) || return 1
  git -c core.quotePath=false diff --name-only --no-renames "$base" -- &&
    git -c core.quotePath=false ls-files --others --exclude-standard
}

# Gradle 构建（含编进 assets 的 Go 程序）和单元测试都读不到的路径：只有设备流程、发版、文档和 CI 用。
# 构建读 app/、shared/、helper/、client/（zeta-ssh 编进 APK）、gradle/、gradlew、根目录的 *.gradle.kts 与 gradle.properties、LICENSE、release/，
# 经 tools/gradle、tools/java-home、tools/own-scope.sh 启动，版本号由 tools/version.sh 算；单元测试另读 tools/fake-claude/、THIRD_PARTY.md。
# 各仓共用的 scripts/gatelib.sh、scripts/format.sh 也读不到：它们的函数只用在上面的排版、lint 和 Go 模块检查里（那些
# 每次都跑），下面起 Gradle 的那一段只经 tools/gradle。本文件不算：它决定 Gradle 怎么起。
# 这些和拿不准的路径都不往这里加：不在这里的一律当作会影响构建。
gradle_blind() {
  case $1 in
    CLAUDE.md | README.md | CONTRIBUTING.md | SECURITY.md | docs/*) ;;
    scripts/gatelib.sh | scripts/format.sh) ;;
    .github/* | .gitignore | .gitleaks-test-values.toml) ;;
    tools/phone-tests/* | tools/emu | tools/phone-lock | tools/phone-run | tools/install.sh | tools/config.sh) ;;
    tools/shots | tools/vd | tools/vdisplay/* | tools/release/*) ;;
    windows/* | ios/* | hub/*) ;;
    *) return 1 ;;
  esac
}

# 要跑 Gradle 时输出为空；可以跳过时输出这次改动的文件（以顿号分隔）。改动为空或算不出时都要跑。
gradle_skip_reason() {
  local files f
  [[ ${ZETA_GATE_FULL:-} == 1 ]] && return 0
  files=$(changed_files) || return 0
  [[ -n $files ]] || return 0
  while IFS= read -r f; do
    gradle_blind "$f" || return 0
  done <<<"$files"
  sort -u <<<"$files" | paste -sd '、'
}
skip_gradle=$(gradle_skip_reason)

# shellcheck source=scripts/gatelib.sh
source scripts/gatelib.sh
gate_python_allowlist # 没有清单文件：仓里不许有 .py

# Kotlin 的工具（ktfmt、detekt）要的 JDK 由 scripts/format.conf 找
scripts/format.sh --check
scripts/format.sh --lint

# Go 模块：版本只由各自 go.mod 的 go 行定；之后用这份工具链的绝对路径。
# go test 用测试缓存，没变的包取上次的结果：缓存认测试进程读过的模块内文件和环境变量，测试经子进程或在模块目录外
# 用到的本仓文件由测试自己先读一遍（见 client/testinput）。机器状态（/proc、本机工具）缓存认不到，
# 全量（ZETA_GATE_FULL=1）不用缓存，全部重跑。
go_test_count=()
[[ ${ZETA_GATE_FULL:-} == 1 ]] && go_test_count=(-count=1)
# check_go_module <目录> [系统...]：系统是这个模块的程序发给哪些系统，默认 linux。vet、staticcheck、测试按本机跑；
# 每个系统各核对一遍依赖清单（各系统链接的模块要和清单一样），linux 以外的再按它跑一遍 vet 和 staticcheck（gate_go_goos）。
check_go_module() {
  local dir=$1 goos go_root unformatted
  shift
  [[ $# -gt 0 ]] || set -- linux
  gate_go_version "$dir"
  go_root=$(cd "$dir" && go env GOROOT)
  unformatted=$(cd "$dir" && "$go_root/bin/gofmt" -l .)
  if [[ -n $unformatted ]]; then
    printf 'gate: %s/ 里这些文件没按 gofmt 排版：\n%s\n' "$dir" "$unformatted" >&2
    exit 1
  fi
  (
    cd "$dir"
    "$go_root/bin/go" vet ./...
    gate_go_run "$STATICCHECK" ./...
    # go vet 已整体跑过。-timeout：一个包的测试 3 分钟没跑完就当挂住，失败并打出各 goroutine 的栈（正常最慢的包约 25 秒；
    # 默认的 10 分钟太长，一个不会来的事件等一分钟超时、攒上五六个，门禁就悄悄多出五分钟还照样算通过）
    gate_test_tmpdir "$go_root/bin/go" test "${go_test_count[@]}" -timeout=3m -vet=off ./...
    gate_go_run "$GOCOGNIT" -over "$MAX_COGNITIVE" .
    for goos in "$@"; do
      [[ $goos == linux ]] || gate_go_goos "$goos"
      gate_go_deps -goos "$goos" go-deps-allowlist.txt ./...
    done
  )
  gate_no_marker 'gocognit:ignore' "$dir/*.go"
}
check_go_module helper
# helper/go.mod 的 tool：单独编进 APK 的程序（claude-switch），它们链接的模块另有一份清单，不算 zeta-helper 的依赖
(cd helper && gate_go_deps go-tools-allowlist.txt tool)
check_go_module tools/release
check_go_module tools/vdisplay
# 客户端共用的 SSH 与主机连接，中枢和桌面版链接它（go.mod 的 replace），所以排在前面；手机和 Linux 主机上的 zeta-ssh 也用它
check_go_module client linux windows
# 先放好中枢要嵌进程序的 zeta-helper、claude-switch 和脚本（见 hub/scripts/assets.sh），桌面版和 iOS 版都带它们
hub/scripts/assets.sh
# 客户端共用的中枢（桌面版和 iOS 版的核心），桌面版链接它
check_go_module hub linux windows
check_go_module windows windows

# 文字表（docs/i18n.md）：mygo 的核对只把解析不了、占位对不上算错，英文缺了某句只警告、界面显示中文，这里警告也算失败；
# 再重新生成取字代码，和文字表对不上时最后的「已跟踪文件不变」会报出来。mygo 命令是桌面版的 tool，从 windows/ 调。
# check_catalogs <目录> [go]：目录里的 mygo.json 指着文字表；带 go 时再用 mygo 重新生成 Go 的取字代码。
check_catalogs() {
  local out
  if ! out=$(cd windows && go tool mygo i18n check "../$1" 2>&1) || grep -q 'warning:' <<<"$out"; then
    printf 'gate: %s 的文字表没过核对：\n%s\n' "$1" "$out" >&2
    exit 1
  fi
  [[ ${2:-} == go ]] || return 0
  if ! out=$(cd windows && go tool mygo generate "../$1" 2>&1); then
    printf 'gate: %s 的取字代码生成失败：\n%s\n' "$1" "$out" >&2
    exit 1
  fi
}
check_catalogs windows go
check_catalogs hub go
check_catalogs helper go
check_catalogs app
# 手机版的 Kotlin 取字代码（核心的和手机版自己的文字表各一份，生成到 shared 的 com.dzshzx.zeta.i18n）
if ! out=$(cd helper && go run ./cmd/ktlocales 2>&1); then
  printf 'gate: 手机版的取字代码生成失败：\n%s\n' "$out" >&2
  exit 1
fi

results=app/build/test-results/testDebugUnitTest
if [[ -n $skip_gradle ]]; then
  echo "gate: 跳过 Gradle 构建与单元测试（ZETA_GATE_FULL=1 时照跑）：改动只涉及构建和测试读不到的文件：$skip_gradle"
elif [[ ${ZETA_GATE_FULL:-} == 1 ]]; then
  tools/gradle --console=plain :app:assembleDebug :app:testDebugUnitTest
  # 截图测试只在这里跑，所以核对它们真的跑了：有结果、一个都没被跳过。对不上就是哪里把它们排除掉了
  shots=$(cat "$results"/TEST-com.dzshzx.zeta.shots.*Shots.xml 2>/dev/null || true)
  ran=$(grep -c '<testcase ' <<<"$shots" || true)
  skipped=$(grep -c '<skipped' <<<"$shots" || true)
  if ((ran == 0 || skipped > 0)); then
    echo "gate: 全量门禁里截图测试没有都跑到：有结果的 $ran 个，其中跳过 $skipped 个" >&2
    exit 1
  fi
else
  echo "gate: 合入门禁不跑截图测试，ZETA_GATE_FULL=1 时全跑"
  tools/gradle --console=plain :app:assembleDebug :app:testDebugUnitTest -Pzeta.gate=fast
fi
if [[ -z $skip_gradle ]]; then
  # 单元测试里一个用例超过 60 秒就失败：正常时最慢的约 20 秒（拿真 claude 跑的契约测试），超过的多半在白等一个不会来的事件。
  # 一个结果都没读到也失败
  awk -v dir="$results" '
    match($0, /<testcase name="[^"]*" classname="[^"]*" time="[0-9.]+"/) {
      n++
      split(substr($0, RSTART, RLENGTH), f, "\"")
      if (f[6] + 0 > 60) slow = slow sprintf("  %s.%s：%s 秒\n", f[4], f[2], f[6])
    }
    END {
      if (n == 0) {
        printf "gate: 没读到单元测试的结果（%s），核对不了各用例的用时\n", dir > "/dev/stderr"
        exit 1
      }
      if (slow != "") {
        printf "gate: 这些单元测试用例超过 60 秒（先查是不是在白等，见 app/build.gradle.kts 的测试任务）：\n%s", slow > "/dev/stderr"
        exit 1
      }
    }' "$results"/TEST-*.xml
fi

if [[ "$(tracked_state)" != "$before" ]]; then
  echo "检查改动了已跟踪文件：" >&2
  git status --porcelain --untracked-files=no >&2
  exit 1
fi
