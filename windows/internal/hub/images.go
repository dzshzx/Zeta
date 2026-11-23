package hub

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/chat"
)

// imageData 是核心取来的图（sessions.image、images.file）的内容；空的算没取到。
func imageData(im coreapi.Image, err error) ([]byte, error) {
	if err != nil {
		return nil, err
	}
	if len(im.Data) == 0 {
		return nil, errors.New("没取到图片")
	}
	return im.Data, nil
}

// InlineImageLoader 是聊天列表取对话 conv 里实时收到的图的办法：核心推来的条目里它们只有 key（options.image_refs），
// 原文经核心的 conv.image 按对话的 key 取，走取大块数据的连接（见 bulk.go；在后台 goroutine 上调用）。
func (h *Hub) InlineImageLoader(backend string, conv *chat.Conversation) func(key string) ([]byte, error) {
	core := h.bulkCall(backend)
	return func(key string) ([]byte, error) {
		ctx, cancel := context.WithTimeout(context.Background(), imageTimeout)
		defer cancel()
		return imageData(core.ConvImage(ctx, conv.State().Key, key))
	}
}

// maxDownload 是网络图片最大多少（同手机版 ImageStore 的 MAX_DOWNLOAD）。
const maxDownload = 12 << 20

// imageHTTP 是下载网络图片用的客户端。
var imageHTTP = &http.Client{Timeout: imageTimeout}

// LinkedImageLoader 是聊天列表取回答里写的图片的办法（在后台 goroutine 上调用）：http、https 的网址从这台电脑下载
// （太大的不要）；别的是设备 backend 上的路径，经核心的 images.file 取（~ 开头的从那边的家目录算），走取大块数据的连接。
func (h *Hub) LinkedImageLoader(backend string) func(url string) ([]byte, error) {
	core := h.bulkCall(backend)
	return func(url string) ([]byte, error) {
		ctx, cancel := context.WithTimeout(context.Background(), imageTimeout)
		defer cancel()
		if strings.HasPrefix(url, "https://") || strings.HasPrefix(url, "http://") {
			return download(ctx, url)
		}
		return imageData(core.FileImage(ctx, url))
	}
}

// download 下载一张网络图片：要 2xx 的应答，超过 maxDownload 的不要。
func download(ctx context.Context, url string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "image/*")
	resp, err := imageHTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("下载图片：%s", resp.Status)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxDownload+1))
	if err != nil {
		return nil, err
	}
	if len(data) > maxDownload {
		return nil, errors.New("图片太大（超过 12 MB）")
	}
	return data, nil
}
