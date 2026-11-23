package main

import (
	"os"
	"path/filepath"
	"testing"
)

// installFakeClaude 在 e 的家目录 ~/.local/bin 放一个假的 claude：--version 报 version 文件里的版本，
// update 把它换成 next（next 为空时说已是最新）。PATH 收窄到系统目录，免得找到本机真的 claude。
func installFakeClaude(t *testing.T, e *env, version, next string) {
	t.Helper()
	t.Setenv("PATH", "/usr/bin:/bin")
	bin := filepath.Join(e.home, ".local/bin")
	if err := os.MkdirAll(bin, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(e.home, "version"), []byte(version), 0o644); err != nil {
		t.Fatal(err)
	}
	script := `#!/bin/bash
v=$(cat "$HOME/version")
case $1 in
--version) echo "$v (Claude Code)" ;;
update)
  echo "Current version: $v"
  if [ -n "` + next + `" ]; then echo "` + next + `" > "$HOME/version"; echo "Successfully updated from $v to version ` + next + `"
  else echo "Claude Code is up to date ($v)"; fi ;;
esac
`
	if err := os.WriteFile(filepath.Join(bin, "claude"), []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
}

func TestSystemClaude(t *testing.T) {
	e, c := startOneShots(t)
	t.Setenv("PATH", "/usr/bin:/bin")
	if v := result(c, "system.claude", ""); v["version"] != nil {
		t.Errorf("没装时版本是 null：%v", v)
	}
	if r := result(c, "system.claude_update", ""); r["ok"] != false || r["before"] != nil || r["version"] != nil {
		t.Errorf("没装时更新不成：%v", r)
	}
	installFakeClaude(t, e, "2.1.330", "2.1.331")
	if v := result(c, "system.claude", ""); v["version"] != "2.1.330" {
		t.Errorf("版本：%v", v)
	}
	r := result(c, "system.claude_update", "")
	if r["ok"] != true || r["before"] != "2.1.330" || r["version"] != "2.1.331" || r["output"] != "Current version: 2.1.330\nSuccessfully updated from 2.1.330 to version 2.1.331" {
		t.Errorf("更新到新版：%v", r)
	}
	installFakeClaude(t, e, "2.1.331", "")
	if r := result(c, "system.claude_update", ""); r["ok"] != true || r["before"] != r["version"] {
		t.Errorf("已是最新：%v", r)
	}
}
