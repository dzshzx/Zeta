#!/usr/bin/env bash
# Zeta 的门禁，也是 wt land 合入前跑的检查。工作站和手机 Debian 里都能跑：bash scripts/check.sh
# 核对仓里没有 Python；Kotlin 按 ktfmt 排版、函数认知复杂度不超过上限、文件不超过行数上限
# （scripts/format.sh）；检查四个 Go 模块，helper/ 的 zeta-helper、tools/release 的发版工具、windows/ 的 Go 桌面版和 tools/vdisplay 的
# 虚拟屏驱动 tools/vd（版本、格式、
# vet、staticcheck、测试、认知复杂度、第三方模块只许各自清单里的，helper/go.mod 按 tool 编进 APK 的程序另有一份清单）；
# 再构建 debug 包（Kotlin 编译、资源、dex，含编 zeta-helper 和 claude-switch）和桌面版（desktop/），
# 跑单元测试；跑完后已跟踪文件须与开始时一致。
# 合入时（wt land）单元测试不跑截图测试（shots 包里类名以 Shots 结尾的，依据见 app/build.gradle.kts 的测试任务）。
# 构建和单元测试在改动全落在它们读不到的路径里时整个跳过（见下面的 gradle_blind）。别的检查每次都跑。
# ZETA_GATE_FULL=1 是全量：构建照跑，单元测试一个不落。每晚的 CI 和发版前跑的是它。
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
# 构建读 app/、shared/、desktop/、helper/、gradle/、gradlew、根目录的 *.gradle.kts 与 gradle.properties、LICENSE、release/，
# 经 tools/gradle、tools/java-home、tools/own-scope.sh 启动；单元测试另读 tools/fake-claude/、THIRD_PARTY.md。
# 这些和拿不准的路径都不往这里加：不在这里的一律当作会影响构建。
gradle_blind() {
  case $1 in
    CLAUDE.md | README.md | CONTRIBUTING.md | SECURITY.md | docs/*) ;;
    .github/* | .gitignore | .gitleaks-test-values.toml) ;;
    tools/phone-tests/* | tools/emu | tools/phone-lock | tools/phone-run | tools/install.sh | tools/config.sh) ;;
    tools/shots | tools/vd | tools/vdisplay/* | tools/migrate-workspace.sh | tools/release/*) ;;
    windows/*) ;;
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

# Go 模块：版本只由各自 go.mod 的 go 行定；之后用这份工具链的绝对路径
check_go_module() {
  local dir=$1 go_root unformatted
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
    # go vet 已整体跑过；测试读临时目录和 /proc，缓存看不到这些输入，每次都真跑
    "$go_root/bin/go" test -count=1 -vet=off ./...
    gate_go_run "$GOCOGNIT" -over "$MAX_COGNITIVE" .
    gate_go_deps go-deps-allowlist.txt ./...
  )
  gate_no_marker 'gocognit:ignore' "$dir/*.go"
}
check_go_module helper
# helper/go.mod 的 tool：单独编进 APK 的程序（claude-switch），它们链接的模块另有一份清单，不算 zeta-helper 的依赖
(cd helper && gate_go_deps go-tools-allowlist.txt tool)
check_go_module tools/release
check_go_module tools/vdisplay
# 桌面版：先放好它要嵌进程序的 zeta-helper、claude-switch 和脚本（见 windows/scripts/assets.sh）
windows/scripts/assets.sh
check_go_module windows

if [[ -n $skip_gradle ]]; then
  echo "gate: 跳过 Gradle 构建与单元测试（ZETA_GATE_FULL=1 时照跑）：改动只涉及构建和测试读不到的文件：$skip_gradle"
elif [[ ${ZETA_GATE_FULL:-} == 1 ]]; then
  tools/gradle --console=plain :app:assembleDebug :app:testDebugUnitTest :desktop:jvmTest
  # 截图测试只在这里跑，所以核对它们真的跑了：有结果、一个都没被跳过。对不上就是哪里把它们排除掉了
  shots=$(cat app/build/test-results/testDebugUnitTest/TEST-com.dzshzx.zeta.shots.*Shots.xml 2>/dev/null || true)
  ran=$(grep -c '<testcase ' <<<"$shots" || true)
  skipped=$(grep -c '<skipped' <<<"$shots" || true)
  if ((ran == 0 || skipped > 0)); then
    echo "gate: 全量门禁里截图测试没有都跑到：有结果的 $ran 个，其中跳过 $skipped 个" >&2
    exit 1
  fi
else
  echo "gate: 合入门禁不跑截图测试，ZETA_GATE_FULL=1 时全跑"
  tools/gradle --console=plain :app:assembleDebug :app:testDebugUnitTest -Pzeta.gate=fast :desktop:jvmTest
fi

if [[ "$(tracked_state)" != "$before" ]]; then
  echo "检查改动了已跟踪文件：" >&2
  git status --porcelain --untracked-files=no >&2
  exit 1
fi
