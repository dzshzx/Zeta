package main

import (
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
)

var imageTypes = map[string]string{".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp",
	".gif": "image/gif", ".bmp": "image/bmp", ".heic": "image/heic", ".avif": "image/avif"}

const maxImageBytes = 30 * 1024 * 1024

// cmdImage 输出一行 zeta_image（见 sessionImage）。
func cmdImage(o *emitter, path, position string) error {
	img, err := sessionImage(path, position)
	if err != nil {
		return err
	}
	o.out(obj{"type": "zeta_image", "media_type": img.MediaType, "data": img.Data})
	return nil
}

// recordImage 是取回的一张图片（会话记录里的或主机上的文件），Data 是 base64。
type recordImage struct {
	MediaType any `json:"media_type"`
	Data      any `json:"data"`
}

// sessionImage 按 history 给出的位置（行号.块序号[.内层序号]）取回会话记录 path 里的一张图片。记录只追加不改写，所以行号是稳定的。
// zeta-helper image 和核心的 sessions.image 都用它。
func sessionImage(path, position string) (recordImage, error) {
	var index []int
	for _, part := range strings.Split(position, ".") {
		n, err := strconv.Atoi(part)
		if err != nil {
			return recordImage{}, fmt.Errorf("bad position: %q", position)
		}
		index = append(index, n)
	}
	line, err := lineAt(path, index[0])
	if err != nil {
		return recordImage{}, err
	}
	block, err := imageBlock(line, index)
	if err != nil {
		return recordImage{}, err
	}
	source := asObj(block["source"])
	if block["type"] != "image" || source["type"] != "base64" {
		return recordImage{}, errors.New("not an image")
	}
	return recordImage{MediaType: source["media_type"], Data: source["data"]}, nil
}

// lineAt 是记录的第 number 行（从 0 数）。
func lineAt(path string, number int) ([]byte, error) {
	f, err := openFile(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	lr := newLineReader(f)
	for n := 0; ; n++ {
		line, ok := lr.next()
		if !ok {
			if lr.err != nil {
				return nil, lr.err
			}
			return nil, errors.New("no such line")
		}
		if n == number {
			return line, nil
		}
	}
}

// imageBlock 在一行记录里按序号找到那个块：消息内容（或插话附件的内容）的第 index[1] 块，有 index[2] 时再取它内容里的那一块。
func imageBlock(line []byte, index []int) (obj, error) {
	o, ok := parseObject(line)
	if !ok {
		return nil, errors.New("not a JSON object")
	}
	if len(index) < 2 {
		return nil, errors.New("no block index")
	}
	content := asObj(o["message"])["content"]
	if !truthy(content) {
		content = asObj(o["attachment"])["prompt"]
	}
	block, err := nth(content, index[1])
	if err != nil {
		return nil, err
	}
	if len(index) > 2 {
		if block, err = nth(asObj(block)["content"], index[2]); err != nil {
			return nil, err
		}
	}
	m, ok := block.(obj)
	if !ok {
		return nil, errors.New("not an image")
	}
	return m, nil
}

func nth(list any, i int) (any, error) {
	l, ok := list.([]any)
	if !ok || i < 0 || i >= len(l) {
		return nil, errors.New("no such block")
	}
	return l[i], nil
}

// cmdFileImage 输出一行 zeta_image（见 fileImage）。
func cmdFileImage(e *env, o *emitter, path string) error {
	img, err := fileImage(e, path)
	if err != nil {
		return err
	}
	o.out(obj{"type": "zeta_image", "media_type": img.MediaType, "data": img.Data})
	return nil
}

// fileImage 读 Linux 环境里的一个图片文件（按扩展名认类型，最大 30 MB；~ 开头的从家目录算）。
// zeta-helper file-image 和核心的 images.file 都用它。
func fileImage(e *env, path string) (recordImage, error) {
	path = e.expandUser(path)
	kind := imageTypes[strings.ToLower(extension(path))]
	fi, err := os.Stat(path)
	if kind == "" || err != nil || !fi.Mode().IsRegular() || fi.Size() > maxImageBytes {
		return recordImage{}, errors.New("not a readable image")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return recordImage{}, err
	}
	return recordImage{MediaType: kind, Data: base64.StdEncoding.EncodeToString(data)}, nil
}

// extension 是文件名的扩展名（含点）；以点开头的名字（.png）没有扩展名。
func extension(p string) string {
	sep := strings.LastIndexByte(p, '/')
	dot := strings.LastIndexByte(p, '.')
	if dot <= sep {
		return ""
	}
	for i := sep + 1; i < dot; i++ {
		if p[i] != '.' {
			return p[dot:]
		}
	}
	return ""
}
