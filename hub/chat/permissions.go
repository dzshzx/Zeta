package chat

import "github.com/dzshzx/Zeta/hub/locales"

// PermissionModes 是对话输入栏与设备设置页共用的有序权限模式选项。
// Label 在显示时按当前语言取字。
var PermissionModes = []struct {
	Value string
	Label func() string
}{
	{"default", locales.ClaudecfgModeDefault},
	{"acceptEdits", locales.ClaudecfgModeAcceptEdits},
	{"plan", locales.ClaudecfgModePlan},
	{"auto", locales.ClaudecfgModeAuto},
	{"bypassPermissions", locales.ComposerModeBypass},
}
