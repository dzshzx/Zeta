package chatview

import (
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
)

// 核心自己写的工具结果（已中断、! 命令的退出码）按界面语言出字；工具的原文照原样。
func TestStepResultSaysTheUILanguage(t *testing.T) {
	interrupted := items.Step{Kind: items.Tool, Name: "Bash", Status: items.ToolFailed, Result: "已中断", ResultCode: "stepInterrupted"}
	if _, detail := stepText(interrupted, "en"); !strings.HasSuffix(detail, "Interrupted") {
		t.Errorf("英文：%q", detail)
	}
	if _, detail := stepText(interrupted, "zh-Hans"); !strings.HasSuffix(detail, "已中断") {
		t.Errorf("中文：%q", detail)
	}
	shell := items.Step{Kind: items.Tool, Name: "Bash", Status: items.ToolFailed, Result: "out\n退出码 2",
		ResultCode: "stepShellExit", ResultArgs: map[string]any{"output": "out", "code": "2"}}
	if _, detail := stepText(shell, "en"); !strings.HasSuffix(detail, "out\nExit code 2") {
		t.Errorf("! 命令：%q", detail)
	}
	raw := items.Step{Kind: items.Tool, Name: "Bash", Status: items.ToolSuccess, Result: "命令的原文"}
	if _, detail := stepText(raw, "en"); !strings.HasSuffix(detail, "命令的原文") {
		t.Errorf("原文：%q", detail)
	}
}
