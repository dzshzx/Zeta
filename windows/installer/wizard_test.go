package installer

import (
	"strings"
	"testing"
)

// 双击 MSI 是安装向导：欢迎 → 安装位置（能改、能浏览）→ 确认；已装时是修复、卸载。静默安装不走这些页。
func TestWizard(t *testing.T) {
	m, _ := msi(t, dev.version)
	dialogs := map[string]bool{}
	for _, row := range table(t, m, "Dialog") {
		dialogs[row["Dialog"]] = true
	}
	for _, d := range []string{"WelcomeDlg", "InstallDirDlg", "BrowseDlg", "VerifyReadyDlg", "ProgressDlg", "ExitDialog",
		"MaintenanceWelcomeDlg", "MaintenanceTypeDlg", "MsiRMFilesInUse", "CancelDlg"} {
		if !dialogs[d] {
			t.Errorf("no dialog %s", d)
		}
	}
	// 对话框之间的跳转：「对话框 控件 事件 参数 条件」
	events := map[string]bool{}
	for _, row := range table(t, m, "ControlEvent") {
		events[strings.Join([]string{row["Dialog_"], row["Control_"], row["Event"], row["Argument"], row["Condition"]}, " ")] = true
	}
	for _, e := range []string{
		"WelcomeDlg Next NewDialog InstallDirDlg NOT Installed",
		"InstallDirDlg Back NewDialog WelcomeDlg 1",
		"InstallDirDlg Next SetTargetPath INSTALLDIR 1",
		"InstallDirDlg Next NewDialog VerifyReadyDlg 1",
		"InstallDirDlg ChangeFolder [_BrowseProperty] INSTALLDIR 1",
		"InstallDirDlg ChangeFolder SpawnDialog BrowseDlg 1",
		"BrowseDlg OK SetTargetPath [_BrowseProperty] 1",
		"VerifyReadyDlg Back NewDialog InstallDirDlg NOT Installed",
		"VerifyReadyDlg Back NewDialog MaintenanceTypeDlg Installed",
		"MaintenanceWelcomeDlg Next NewDialog MaintenanceTypeDlg 1",
		"ExitDialog Finish DoAction LaunchZeta WIXUI_EXITDIALOGOPTIONALCHECKBOX = 1 AND NOT Installed",
	} {
		if !events[e] {
			t.Errorf("no control event %q", e)
		}
	}
	seq := map[string]string{}
	for _, row := range table(t, m, "InstallUISequence") {
		seq[row["Action"]] = row["Condition"]
	}
	if seq["WelcomeDlg"] != "NOT Installed OR PATCH" || !strings.HasPrefix(seq["MaintenanceWelcomeDlg"], "Installed") {
		t.Errorf("UI sequence: WelcomeDlg %q, MaintenanceWelcomeDlg %q", seq["WelcomeDlg"], seq["MaintenanceWelcomeDlg"])
	}
}

// focusable 是能拿到键盘焦点的控件种类：每一个都要在它对话框的 Tab 圈里。
var focusable = map[string]bool{"PushButton": true, "PathEdit": true, "Edit": true, "MaskedEdit": true, "CheckBox": true,
	"RadioButtonGroup": true, "ComboBox": true, "ListBox": true, "ListView": true, "DirectoryCombo": true,
	"DirectoryList": true, "SelectionTree": true, "VolumeCostList": true, "VolumeSelectCombo": true, "ScrollableText": true}

// 每个对话框从第一个控件起顺着 Control_Next 走一圈回到它，能聚焦的控件都在圈里：不在圈里的控件按 Tab 到不了。
// wixl 自己排的漏掉输入框和目录列表，msi.sh 的 tab_order 补上。
func TestTabOrder(t *testing.T) {
	m, _ := msi(t, dev.version)
	first := map[string]string{}
	for _, row := range table(t, m, "Dialog") {
		first[row["Dialog"]] = row["Control_First"]
	}
	next := map[string]map[string]string{}
	kinds := map[string]map[string]string{}
	for _, row := range table(t, m, "Control") {
		d := row["Dialog_"]
		if next[d] == nil {
			next[d], kinds[d] = map[string]string{}, map[string]string{}
		}
		next[d][row["Control"]] = row["Control_Next"]
		kinds[d][row["Control"]] = row["Type"]
	}
	for d, f := range first {
		inCycle := map[string]bool{}
		c := f
		for !inCycle[c] && c != "" {
			inCycle[c] = true
			c = next[d][c]
		}
		if c != f {
			t.Errorf("%s: the tab order from %s does not come back to it (stops at %q)", d, f, c)
		}
		for name, kind := range kinds[d] {
			if focusable[kind] && !inCycle[name] {
				t.Errorf("%s: %s %s is not in the tab order", d, kind, name)
			}
		}
	}
}

// 安装位置记在 HKCU 下各自的键里（TestFilesAndShortcut 核对写入），装下一版时 AppSearch 读回来，升级、应用内更新照旧装在那里。
func TestInstallDir(t *testing.T) {
	for _, v := range variants {
		t.Run(v.name, func(t *testing.T) {
			m, _ := msi(t, v.version)
			search := table(t, m, "AppSearch")
			if len(search) != 1 || search[0]["Property"] != "INSTALLDIR" {
				t.Fatalf("app search %v", search)
			}
			loc := table(t, m, "RegLocator")
			if len(loc) != 1 || loc[0]["Signature_"] != search[0]["Signature_"] || loc[0]["Root"] != "1" ||
				loc[0]["Key"] != v.regKey || loc[0]["Name"] != "InstallDir" {
				t.Errorf("registry locator %v", loc)
			}
		})
	}
}

// 完成页勾着「启动 <名字>」，按完成打开装好的 exe（异步，不等它）。
func TestLaunchAfterInstall(t *testing.T) {
	for _, v := range variants {
		t.Run(v.name, func(t *testing.T) {
			m, _ := msi(t, v.version)
			props := properties(t, m)
			if props["WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT"] != "Launch "+v.name || props["WIXUI_EXITDIALOGOPTIONALCHECKBOX"] != "1" {
				t.Errorf("exit dialog checkbox %q, checked %q", props["WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT"], props["WIXUI_EXITDIALOGOPTIONALCHECKBOX"])
			}
			var launch map[string]string
			for _, row := range table(t, m, "CustomAction") {
				if row["Action"] == "LaunchZeta" {
					launch = row
				}
			}
			// 210 = 18（运行装上的文件）+ 64（不管退出码）+ 128（异步）
			if launch == nil || launch["Type"] != "210" || launch["Source"] != "ZetaExe" {
				t.Errorf("launch action %v", launch)
			}
		})
	}
}
