#!/bin/bash
# 聊天里的两个裸命令在应用内打开现有页面，不发起 Claude Code 的一轮。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

vd fake "$HERE/fake/text.jsonl"
new_chat
send /permissions
wait_text '^工具权限$' 10 || {
	echo '/permissions 没打开工具权限页'
	exit 1
}
vd key 4
wait_text '^有什么可以帮你' 10 || {
	echo '/permissions 返回后不是空白聊天页'
	exit 1
}
send /config
wait_text '^新对话默认$' 10 || {
	echo '/config 没打开设备设置页'
	exit 1
}
vd key 4
wait_text '^有什么可以帮你' 10 || {
	echo '/config 返回后不是空白聊天页'
	exit 1
}
