package agystore

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// SavedImage 是 Zeta 为 agy 会话落盘保存的一张用户上传图片记录（存于 brain/<id>/.zeta_images.json）。
type SavedImage struct {
	Turn      int    `json:"turn"`
	File      string `json:"file"`
	MediaType string `json:"mediaType"`
}

// ImageInput 是写入 brain/<id>/.zeta_images/ 的一张图片。
type ImageInput struct {
	MediaType string
	Data      string // base64
}

// SaveUserImages 把一轮用户发送的图片写到 brain/<id>/.zeta_images/ 并追加索引到 .zeta_images.json。
// turn 为该轮在会话中从 0 计数的用户消息序号；图片只存不覆盖，历史重放按 turn 挂回 ref。
func SaveUserImages(agyDir, id string, turn int, imgs []ImageInput) {
	if !IsSessionID(id) || len(imgs) == 0 {
		return
	}
	brain := filepath.Join(agyDir, "brain", id)
	if st, err := os.Stat(brain); err != nil || !st.IsDir() {
		return
	}
	imgDir := filepath.Join(brain, ".zeta_images")
	if err := os.MkdirAll(imgDir, 0o700); err != nil {
		return
	}
	existing := ReadUserImages(agyDir, id)
	for i, img := range imgs {
		if img.Data == "" {
			continue
		}
		raw, err := base64.StdEncoding.DecodeString(img.Data)
		if err != nil || len(raw) == 0 {
			continue
		}
		mt := img.MediaType
		if mt == "" {
			mt = "image/png"
		}
		name := fmt.Sprintf("t%d-%d%s", turn, i, extForMime(mt))
		dst := filepath.Join(imgDir, name)
		if writeFileAtomic(dst, raw, 0o600) != nil {
			continue
		}
		existing = append(existing, SavedImage{Turn: turn, File: name, MediaType: mt})
	}
	if b, err := json.Marshal(existing); err == nil {
		_ = writeFileAtomic(filepath.Join(brain, ".zeta_images.json"), b, 0o644)
	}
}

// ReadUserImages 读取 brain/<id>/.zeta_images.json 里的用户图片索引；不存在时返回 nil。
func ReadUserImages(agyDir, id string) []SavedImage {
	if !IsSessionID(id) {
		return nil
	}
	data, err := os.ReadFile(filepath.Join(agyDir, "brain", id, ".zeta_images.json"))
	if err != nil {
		return nil
	}
	var list []SavedImage
	if json.Unmarshal(data, &list) != nil {
		return nil
	}
	return list
}

// ReadSavedImage 按 "agy:<filename>" 引用从 brain/<id>/.zeta_images/ 读回图片（base64）。
func ReadSavedImage(agyDir, id, ref string) (mediaType, data string, err error) {
	name, ok := strings.CutPrefix(ref, "agy:")
	if !ok || !IsSessionID(id) || name == "" || strings.Contains(name, "/") || strings.Contains(name, "\\") || strings.Contains(name, "..") {
		return "", "", errors.New("bad agy image ref")
	}
	p := filepath.Join(agyDir, "brain", id, ".zeta_images", name)
	fi, err := os.Lstat(p)
	if err != nil || !fi.Mode().IsRegular() {
		return "", "", errors.New("agy image not found")
	}
	raw, err := os.ReadFile(p)
	if err != nil {
		return "", "", err
	}
	mt := mimeForExt(filepath.Ext(name))
	for _, entry := range ReadUserImages(agyDir, id) {
		if entry.File == name && entry.MediaType != "" {
			mt = entry.MediaType
			break
		}
	}
	return mt, base64.StdEncoding.EncodeToString(raw), nil
}

func extForMime(mt string) string {
	switch strings.ToLower(mt) {
	case "image/jpeg", "image/jpg":
		return ".jpg"
	case "image/webp":
		return ".webp"
	case "image/gif":
		return ".gif"
	default:
		return ".png"
	}
}

func mimeForExt(ext string) string {
	switch strings.ToLower(ext) {
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".webp":
		return "image/webp"
	case ".gif":
		return "image/gif"
	default:
		return "image/png"
	}
}

func writeFileAtomic(path string, data []byte, perm os.FileMode) error {
	dir := filepath.Dir(path)
	f, err := os.CreateTemp(dir, "."+filepath.Base(path)+".*")
	if err != nil {
		return err
	}
	tmp := f.Name()
	if _, err := f.Write(data); err != nil {
		_ = f.Close()
		_ = os.Remove(tmp)
		return err
	}
	if err := f.Chmod(perm); err != nil {
		_ = f.Close()
		_ = os.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}
