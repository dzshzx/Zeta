#!/bin/bash
# 真机：edit_note 在小米便签里新建、修改一条标题带「Zeta 测试」的便签，读回核对；再用 search_personal 按标题独立查一次，
# 并确认 edit_note 拒绝删除。每步一轮假 Claude（经 Zeta 真调手机工具，剧本按上一步拿到的便签编号现写）。
#
# 便签没有开放删除（见 MiuiNoteWrites），测试便签由收尾在后台屏上点便签的界面删掉：编辑页「更多 → 删除」，再到
# 「文件夹 → 最近删除」里长按它「永久删除」。每一步先核对屏上的字（标题、确认框的话、已选择1项），对不上就停，报告残留。
# 界面步骤读到的屏幕里有别的便签的摘要：只打印核对用的行，存屏幕的文件用完就删，不留在产物里。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/notes-write.sh
set -uo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
mark="Zeta 测试 $(date +%m%d-%H%M%S)"
id='' title='' TEXT='' FAILED=''

# turn <名字> <手机工具步骤的 JSON 数组> <存到哪>：经 lib.sh 的 fake_turn 跑一轮假 Claude（等 60 秒），
# 这一轮各次工具结果的文字依次存进文件；TEXT、FAILED 是最后一次的文字和是否出错
turn() {
  local name=$1 file=$3 start=$SECONDS results
  results=results-notes-$name.jsonl
  if ! FAKE_TURN_WAIT=60 fake_turn "notes-$name" 便签写入测试 "$2"; then
    echo "$name 这一轮没跑完"
    TEXT='' FAILED=true
    return 1
  fi
  jq -r .text "$results" >"$file"
  TEXT=$(tail -n 1 "$results" | jq -r .text)
  FAILED=$(tail -n 1 "$results" | jq -r .error)
  echo "$name（$((SECONDS - start)) 秒）：$(head -n 1 <<<"$TEXT" | cut -c1-120)"
}

# 便签自己的工具结果，存进产物
step() { turn "$1" "$2" "step-$1.txt"; }

# 点便签界面的一步：屏幕存进 ui-<名字>.txt（收尾删掉），只打印和 $3 匹配的行（不打印别的便签的卡片）
ui() {
  turn "$1" "$2" "ui-$1.txt" >/dev/null || return 1
  grep -vE 'cardView|tvContent|tvTitle' "ui-$1.txt" | grep -E "$3" | cut -c1-140 | sed "s/^/  [$1] /" || true
}

# 在后台屏上删掉测试便签 #$id（标题 $title），再从「最近删除」里永久删除
remove_note() {
  ui open "[{\"phone\":\"open_app\",\"input\":{\"app\":\"com.miui.notes\",\"uri\":\"minote://note/$id\",\"display\":\"background\"}},{\"pause\":2500},{\"phone\":\"screen\",\"input\":{\"max_nodes\":80}}]" '前台应用|EditText' || return 1
  grep -qF "EditText \"$title\"" ui-open.txt || { echo "打开的不是测试便签"; return 1; }
  ui menu '[{"phone":"tap","input":{"match":"更多"}},{"pause":1200},{"phone":"screen","input":{"max_nodes":80}}]' '"删除"' || return 1
  grep -q 'LinearLayout 描述="删除"' ui-menu.txt || { echo "菜单里没有「删除」"; return 1; }
  ui ask '[{"phone":"tap","input":{"match":"删除"}},{"pause":1200},{"phone":"screen","input":{"max_nodes":40}}]' 'message|button' || return 1
  grep -q '确认删除该条笔记' ui-ask.txt && grep -q 'Button "删除" id=button1' ui-ask.txt || { echo "没看到删除确认框"; return 1; }
  ui trashed "[{\"phone\":\"tap\",\"input\":{\"id\":\"button1\"}},{\"pause\":2000},{\"phone\":\"search_personal\",\"input\":{\"source\":\"notes\",\"query\":$(jq -n --arg q "$mark" '$q')}}]" '没有符合条件' || return 1
  grep -q '没有符合条件的便签' ui-trashed.txt || { echo "删除后列表里还查得到"; return 1; }
  ui bin '[{"phone":"open_app","input":{"app":"com.miui.notes","display":"background"}},{"pause":2500},{"phone":"tap","input":{"match":"文件夹"}},{"pause":1500},{"phone":"tap","input":{"match":"最近删除"}},{"pause":1500},{"phone":"screen","input":{"max_nodes":80}}]' 'action_bar_title' || return 1
  grep -qF "描述=\"$title，" ui-bin.txt || { echo "最近删除里没看到测试便签"; return 1; }
  ui pick "[{\"phone\":\"long_press\",\"input\":{\"match\":$(jq -n --arg t "$title" '$t')}},{\"pause\":1500},{\"phone\":\"screen\",\"input\":{\"max_nodes\":80}}]" '已选择|永久删除' || return 1
  grep -q '"已选择1项"' ui-pick.txt && grep -q 'Button 描述="永久删除"' ui-pick.txt || { echo "没有只选中这一条"; return 1; }
  ui ask2 '[{"phone":"tap","input":{"match":"永久删除"}},{"pause":1500},{"phone":"screen","input":{"max_nodes":40}}]' 'message|button' || return 1
  grep -q '确认永久删除所选的1条笔记' ui-ask2.txt && grep -q 'Button "删除" id=button1' ui-ask2.txt || { echo "没看到永久删除确认框"; return 1; }
  ui purged '[{"phone":"tap","input":{"id":"button1"}},{"pause":2000},{"phone":"screen","input":{"max_nodes":80}}]' 'action_bar_title' || return 1
  ! grep -qF "$title" ui-purged.txt || { echo "最近删除里还有测试便签"; return 1; }
}

cleanup() {
  local rc=$?
  if [[ -n $id ]]; then
    if remove_note; then
      echo "测试便签 #$id 已删除，最近删除里也清掉了"
    else
      echo "残留：测试便签 #$id「$title」没删干净，请在便签里手动删除（含最近删除）"
      rc=1
    fi
  fi
  # fake_turn 的结果文件里有界面步骤读到的屏幕（别的便签的摘要），和 ui-*.txt 一起删；便签自己的结果另存在 step-*.txt
  rm -f ui-*.txt results-notes-*.jsonl
  exit $rc
}
trap cleanup EXIT
trap 'exit 143' TERM INT

step create "$(jq -nc --arg t "$mark" '[{phone: "edit_note", input: {action: "create", title: $t, content: "第一行\n第二行 **加粗**"}}]')"
id=$(grep -oE '^便签 #[0-9]+' <<<"$TEXT" | head -n 1 | tr -dc 0-9)
[[ -n $id ]] && title=$mark
[[ $FAILED == false && $TEXT == 已新建，读回核对一致。* && -n $id ]] || { echo "新建不对"; exit 1; }
echo "测试便签 #$id"

step update "$(jq -nc --argjson id "$id" --arg t "$mark 改" '[{phone: "edit_note", input: {action: "update", note_id: $id, title: $t, content: "改过的正文\n- 列表一\n- 列表二"}}]')"
[[ $TEXT == *"标题：$mark 改"* ]] && title="$mark 改"
[[ $FAILED == false && $TEXT == 已修改，读回核对一致。* && $TEXT == *"标题：$mark 改"* ]] || { echo "修改不对"; exit 1; }

step search "$(jq -nc --arg q "$mark" '[{phone: "search_personal", input: {source: "notes", query: $q}}]')"
[[ $TEXT == *"#$id "* ]] || { echo "search_personal 按标题查不到 #$id"; exit 1; }

step refuse "$(jq -nc --argjson id "$id" '[{phone: "edit_note", input: {action: "delete", note_id: $id}}]')"
[[ $FAILED == true && $TEXT == *"便签没有开放删除"* ]] || { echo "删除没有被拒绝"; exit 1; }

echo "新建、修改都读回核对过，删除被拒绝"
