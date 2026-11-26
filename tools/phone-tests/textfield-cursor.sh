#!/bin/bash
# 输入框的光标跟着文字走（docs/ui.md「补上游缺口」里输入框那一条，要真的输入法才出现，单元测试测不到）：
# 从深链打开「外观与主题」，点「界面缩放」开输入对话框，点清除，再依次输入 9、5，框里应是 95；
# 光标没跟到末尾时第二个字插到前面，成了 59。最后按返回关掉对话框，不改缩放。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/textfield-cursor.sh
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

android "am start --display $DISPLAY_ID --activity-single-top -n $PKG/com.dzshzx.zeta.ui.MainActivity --es com.dzshzx.zeta.extra.SETTINGS_PAGE Appearance >/dev/null"
sleep 2
vd shot 01-appearance.png >/dev/null
vd tap-text '^界面缩放$' >/dev/null
sleep 1.5
vd shot 02-dialog.png >/dev/null
vd tap-text '^清除$' >/dev/null
sleep 0.5
vd text 9 >/dev/null
sleep 0.5
vd text 5 >/dev/null
sleep 0.5
vd shot 03-typed.png >/dev/null
fields=$(vd nodes '^(95|59)$' | cut -f3)
vd key 4 >/dev/null
sleep 1
case $fields in
95) echo "光标跟到了末尾：95" ;;
59) echo "光标停在开头：输入 9、5 得到 59" && exit 1 ;;
*) echo "框里不是 95 也不是 59：[$fields]（看 03-typed.png）" && exit 1 ;;
esac
