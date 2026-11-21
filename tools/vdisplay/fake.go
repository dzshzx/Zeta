package main

import (
	"archive/tar"
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// scenario 核对剧本（每行一个带 steps 数组的 JSON 对象，空行不算），返回各轮那一行。
func scenario(data []byte, name string) ([]string, error) {
	var lines []string
	for line := range strings.Lines(string(data)) {
		line = strings.TrimRight(line, "\r\n")
		if strings.TrimSpace(line) != "" {
			lines = append(lines, line)
		}
	}
	for i, line := range lines {
		var turn map[string]json.RawMessage
		if err := json.Unmarshal([]byte(line), &turn); err != nil {
			return nil, fmt.Errorf("%s 第 %d 行不是 JSON 对象：%v", name, i+1, err)
		}
		var steps []json.RawMessage
		if json.Unmarshal(turn["steps"], &steps) != nil || steps == nil {
			return nil, fmt.Errorf("%s 第 %d 行没有 steps 数组", name, i+1)
		}
	}
	return lines, nil
}

// fakeArchive 把假 Claude 的脚本和剧本打成 tar。脚本先于剧本落地：zeta_host.sh 看到剧本时脚本已经在了。
func fakeArchive(script []byte, lines []string, mtime time.Time) []byte {
	var buf bytes.Buffer
	tw := tar.NewWriter(&buf)
	files := []struct {
		name string
		data []byte
		mode int64
	}{
		{"fake-claude.sh", script, 0o755},
		{"scenario.jsonl", []byte(strings.Join(lines, "\n") + "\n"), 0o644},
	}
	for _, f := range files {
		hdr := &tar.Header{Name: f.name, Mode: f.mode, Size: int64(len(f.data)), ModTime: mtime, Typeflag: tar.TypeReg}
		if tw.WriteHeader(hdr) != nil {
			die("打包假 Claude 失败")
		}
		tw.Write(f.data)
	}
	tw.Close()
	return buf.Bytes()
}

// fake 放上假 Claude 和剧本；--off 删掉。
func (v *vd) fake(path string) {
	if path == "--off" {
		fmt.Print(v.debian("rm -rf "+fakeDir+" && echo 假 Claude 已撤下", nil))
		return
	}
	data, err := os.ReadFile(path)
	if err != nil {
		die("%v", err)
	}
	lines, err := scenario(data, path)
	if err != nil {
		die("%s", err)
	}
	script, err := os.ReadFile(filepath.Join(v.tools, "fake-claude", "fake-claude.sh"))
	if err != nil {
		die("%v", err)
	}
	archive := fakeArchive(script, lines, time.Now())
	v.debian(fmt.Sprintf("mkdir -p %[1]s && rm -f %[1]s/scenario.jsonl.turn && tar -xf - -C %[1]s --no-same-owner", fakeDir), archive)
	fmt.Printf("假 Claude 已就位：剧本 %s，%d 轮；之后测试版起的对话都用它\n", filepath.Base(path), len(lines))
}
