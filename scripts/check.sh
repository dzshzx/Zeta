#!/usr/bin/env bash
# Zeta 的门禁，也是 wt land 合入前跑的检查。工作站和手机 Debian 里都能跑：bash scripts/check.sh
# 核对仓里的 Python 只有清单里的、都能解析；检查 helper/ 的 Go 程序 zeta-helper（版本、格式、vet、staticcheck、测试、
# 认知复杂度、第三方模块只许清单里的）；再构建 debug 包（Kotlin 编译、资源、dex，含编 zeta-helper）、跑单元测试；跑完后已跟踪文件须与开始时一致。
# 装到手机和真机上的体验不在此列，见 CLAUDE.md「构建与测试」。
set -euo pipefail
cd "$(dirname "$0")/.."

tracked_state() { git status --porcelain --untracked-files=no && git diff HEAD | git hash-object --stdin; }
before=$(tracked_state)

# shellcheck source=scripts/gatelib.sh
source scripts/gatelib.sh
gate_python_allowlist
git ls-files -z -- '*.py' | xargs -0 python3 -I -c 'import ast, sys; [ast.parse(open(f, encoding="utf-8").read(), f) for f in sys.argv[1:]]'

# zeta-helper：版本只由 helper/go.mod 的 go 行定；之后用这份工具链的绝对路径
gate_go_version helper
GO_ROOT=$(cd helper && go env GOROOT)
unformatted=$(cd helper && "$GO_ROOT/bin/gofmt" -l .)
if [[ -n $unformatted ]]; then
  printf 'gate: helper/ 里这些文件没按 gofmt 排版：\n%s\n' "$unformatted" >&2
  exit 1
fi
(
  cd helper
  "$GO_ROOT/bin/go" vet ./...
  gate_go_run "$STATICCHECK" ./...
  # go vet 已整体跑过；测试读临时目录和 /proc，缓存看不到这些输入，每次都真跑
  "$GO_ROOT/bin/go" test -count=1 -vet=off ./...
  gate_go_run "$GOCOGNIT" -over "$MAX_COGNITIVE" .
  gate_go_deps go-deps-allowlist.txt .
)
gate_no_marker 'gocognit:ignore' 'helper/*.go'

tools/gradle --console=plain :app:assembleDebug :app:testDebugUnitTest

if [[ "$(tracked_state)" != "$before" ]]; then
  echo "检查改动了已跟踪文件：" >&2
  git status --porcelain --untracked-files=no >&2
  exit 1
fi
