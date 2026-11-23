#!/bin/bash
# 把编好的 exe 打成 MSI：msi.sh <exe> <版本> <输出的 .msi>。平时经 build.sh 调用；installer_test.go 拿一个假的 exe 调它，核对打出来的表。
# 版本名是干净的 X.Y.Z 时打正式版 Zeta，别的（0.7.2-dev.3+gabc1234）打和它并存的测试版 Zeta Dev（同 internal/update 的
# Version.Release；两个版本的身份写在 Zeta.wxs 开头）。
# 要 wixl，和 msitools 包里的 msibuild（改 Tab 顺序）、msiinfo（测试读 MSI）：Debian/Ubuntu 上 apt install wixl msitools。
set -euo pipefail
exe=$1 version=$2 out=$3
here=$(cd "$(dirname "$0")" && pwd)
for tool in wixl msibuild; do
  command -v "$tool" >/dev/null || {
    echo "msi.sh: 缺 $tool（Debian/Ubuntu 上 sudo apt install wixl msitools）" >&2
    exit 1
  }
done
variant=dev
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] && variant=release
# MSI 的版本只认 X.Y.Z 三段数字：预发布（0.7.2-dev.3+gabc1234）取前面的 0.7.2
# 安装向导（ZetaUI.wxs）用 wixl 自带的 WixUI 界面：--ext ui
wixl -a x64 --ext ui -D "Variant=$variant" -D "Version=${version%%-*}" -D "Exe=$exe" -D "Here=$here" -o "$out" "$here/Zeta.wxs" "$here/ZetaUI.wxs"

# tab_order <对话框> <控件…>：把对话框的 Tab 顺序按给的控件连成一圈，第一个是打开时的焦点，别的控件不在圈里。
# wixl 排 Tab 顺序只收按钮和图片，路径输入框、目录下拉框、目录列表都漏掉（也不认 TabSkip）：按 Tab 到不了它们。
# wizard_test.go 的 TestTabOrder 核对每个对话框的圈。
tab_order() {
  local dialog=$1
  shift
  local controls=("$@") i next
  msibuild "$out" -q "UPDATE \`Control\` SET \`Control_Next\`='' WHERE \`Dialog_\`='$dialog'"
  for ((i = 0; i < ${#controls[@]}; i++)); do
    next=${controls[(i + 1) % ${#controls[@]}]}
    msibuild "$out" -q "UPDATE \`Control\` SET \`Control_Next\`='$next' WHERE \`Dialog_\`='$dialog' AND \`Control\`='${controls[i]}'"
  done
  msibuild "$out" -q "UPDATE \`Dialog\` SET \`Control_First\`='${controls[0]}' WHERE \`Dialog\`='$dialog'"
}
tab_order InstallDirDlg Folder ChangeFolder Next Back Cancel
tab_order BrowseDlg PathEdit OK Cancel DirectoryCombo WixUI_Bmp_Up NewFolder DirectoryList
# 下面几页是 wixl 自带的：出错页（打开时的焦点 ErrorText 和各个按钮都不在圈里）、准备页、两种文件占用页（应用列表键盘到不了）
tab_order ErrorDlg ErrorText Y A C I N O R
tab_order PrepareDlg Cancel Back Next
tab_order FilesInUse Retry Ignore Exit List
tab_order MsiRMFilesInUse OK Cancel ShutdownOption List
