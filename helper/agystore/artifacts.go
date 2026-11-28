package agystore

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

const maxArtifactBytes = 512 * 1024

// ArtifactInfo 是会话 brain/<sessionID> 目录下的一份产物摘要。
type ArtifactInfo struct {
	Name       string `json:"name"`
	Summary    string `json:"summary,omitempty"`
	UserFacing bool   `json:"userFacing,omitempty"`
	UpdatedAt  string `json:"updatedAt,omitempty"`
	Size       int64  `json:"size"`
}

// ArtifactContent 是读取单份产物后的正文与元信息。
type ArtifactContent struct {
	Name      string `json:"name"`
	Summary   string `json:"summary,omitempty"`
	Content   string `json:"content"`
	Truncated bool   `json:"truncated,omitempty"`
	Size      int64  `json:"size"`
}

type artifactMeta struct {
	Summary    string `json:"summary"`
	UpdatedAt  string `json:"updatedAt"`
	UserFacing bool   `json:"userFacing"`
}

// ListArtifacts 列出 <agyDir>/brain/<sessionID> 下的可见产物（排除 .system_generated、.zeta_*、*.metadata.json）。
func ListArtifacts(agyDir, sessionID string) []ArtifactInfo {
	root, ok := resolveBrainDir(agyDir, sessionID)
	if !ok {
		return nil
	}
	entries, err := os.ReadDir(root)
	if err != nil {
		return nil
	}
	var out []ArtifactInfo
	for _, de := range entries {
		name := de.Name()
		if !isVisibleArtifactName(name) || de.IsDir() {
			continue
		}
		target, info, err := resolveArtifactFile(root, name)
		if err != nil {
			continue
		}
		meta := readArtifactMeta(target + ".metadata.json")
		out = append(out, ArtifactInfo{
			Name:       name,
			Summary:    strings.TrimSpace(meta.Summary),
			UserFacing: meta.UserFacing,
			UpdatedAt:  strings.TrimSpace(meta.UpdatedAt),
			Size:       info.Size(),
		})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].UserFacing != out[j].UserFacing {
			return out[i].UserFacing
		}
		return out[i].Name < out[j].Name
	})
	return out
}

// ReadArtifact 读取单份产物内容，拒绝路径穿越与指向 brain/<sessionID> 之外的符号链接，最多读取 512 KiB。
func ReadArtifact(agyDir, sessionID, name string) (ArtifactContent, error) {
	if !isVisibleArtifactName(name) || filepath.Base(name) != name || strings.ContainsAny(name, `/\`) {
		return ArtifactContent{}, errors.New("产物文件名不合法")
	}
	root, ok := resolveBrainDir(agyDir, sessionID)
	if !ok {
		return ArtifactContent{}, errors.New("找不到会话产物目录")
	}
	target, info, err := resolveArtifactFile(root, name)
	if err != nil {
		return ArtifactContent{}, err
	}
	f, err := os.Open(target)
	if err != nil {
		return ArtifactContent{}, err
	}
	defer f.Close()
	buf, err := io.ReadAll(io.LimitReader(f, maxArtifactBytes+1))
	if err != nil {
		return ArtifactContent{}, err
	}
	truncated := len(buf) > maxArtifactBytes
	if truncated {
		buf = buf[:maxArtifactBytes]
	}
	meta := readArtifactMeta(target + ".metadata.json")
	return ArtifactContent{
		Name:      name,
		Summary:   strings.TrimSpace(meta.Summary),
		Content:   string(buf),
		Truncated: truncated,
		Size:      info.Size(),
	}, nil
}

func isVisibleArtifactName(name string) bool {
	if name == "" || strings.HasPrefix(name, ".") || strings.HasSuffix(name, ".metadata.json") {
		return false
	}
	return name != ".system_generated"
}

func resolveBrainDir(agyDir, sessionID string) (string, bool) {
	sessionID = strings.TrimSpace(sessionID)
	if agyDir == "" || sessionID == "" || filepath.Base(sessionID) != sessionID || strings.ContainsAny(sessionID, `/\`) {
		return "", false
	}
	dir := filepath.Join(agyDir, "brain", sessionID)
	real, err := filepath.EvalSymlinks(dir)
	if err != nil {
		return "", false
	}
	st, err := os.Stat(real)
	if err != nil || !st.IsDir() {
		return "", false
	}
	return real, true
}

func resolveArtifactFile(realRoot, name string) (string, os.FileInfo, error) {
	cand := filepath.Join(realRoot, name)
	real, err := filepath.EvalSymlinks(cand)
	if err != nil {
		return "", nil, err
	}
	rel, err := filepath.Rel(realRoot, real)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) || filepath.IsAbs(rel) {
		return "", nil, errors.New("拒绝访问目录外的符号链接")
	}
	st, err := os.Stat(real)
	if err != nil || !st.Mode().IsRegular() {
		return "", nil, errors.New("产物不是普通文件")
	}
	return real, st, nil
}

func readArtifactMeta(path string) artifactMeta {
	data, err := os.ReadFile(path)
	if err != nil {
		return artifactMeta{}
	}
	var m artifactMeta
	_ = json.Unmarshal(data, &m)
	return m
}
