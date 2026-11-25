#!/bin/bash
# 聊天里的两个裸命令在应用内打开现有页面，不发起 Claude Code 的一轮。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

check_page() {
	send "$1"
	wait_text "$2" 10 || {
		echo "$1 没打开目标页：$2"
		return 1
	}
	vd key 4
	wait_text '^有什么可以帮你' 10 || {
		echo "$1 返回后不是空白聊天页"
		return 1
	}
}

vd fake "$HERE/fake/text.jsonl"
new_chat
check_page /permissions '^工具权限$'
check_page /config '^新对话默认$'
