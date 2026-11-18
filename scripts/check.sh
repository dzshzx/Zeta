#!/usr/bin/env bash
# Zeta 的门禁，也是 wt land 合入前跑的检查。工作站和手机 Debian 里都能跑：bash scripts/check.sh
# 核对仓里的 Python 只有清单里的、都能解析，再构建 debug 包（Kotlin 编译、资源、dex）；跑完后已跟踪文件须与开始时一致。
# 装到手机和真机上的体验不在此列，见 CLAUDE.md「构建与测试」。
set -euo pipefail
cd "$(dirname "$0")/.."

tracked_state() { git status --porcelain --untracked-files=no && git diff HEAD | git hash-object --stdin; }
before=$(tracked_state)

# shellcheck source=scripts/gatelib.sh
source scripts/gatelib.sh
gate_python_allowlist
git ls-files -z -- '*.py' | xargs -0 python3 -I -c 'import ast, sys; [ast.parse(open(f, encoding="utf-8").read(), f) for f in sys.argv[1:]]'
tools/gradle --console=plain :app:assembleDebug

if [[ "$(tracked_state)" != "$before" ]]; then
  echo "检查改动了已跟踪文件：" >&2
  git status --porcelain --untracked-files=no >&2
  exit 1
fi
