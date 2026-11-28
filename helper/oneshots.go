package main

import (
	"encoding/json"
	"errors"
	"strings"

	"github.com/dzshzx/Zeta/helper/core"
)

// 核心上做完就答的方法：存储页（storage.*）、文件浏览（files.*）和按路径取图片（images.file），见 docs/core.md「存储与文件」；
// 诊断页读的日志（diag.*，见 diag.go）。
// 每个和同名的子命令共用一份实现，子命令照旧留着（手机上看本机文件仍用它们，原因见 docs/core.md）。
// 各在自己的 goroutine 里做（core.Mux.HandleAsync），慢的（存储统计与清理）不挡同一条连接后面的请求。

// oneShotParams 是这一组方法的参数，和 id、method 平级；各方法用其中几项。
type oneShotParams struct {
	Keep    []string `json:"keep"` // 客户端认为正在进行、要保护的会话号
	Project string   `json:"project"`
	Kind    string   `json:"kind"`
	Path    string   `json:"path"`
	Bytes   int64    `json:"bytes"`
	Minutes *int     `json:"minutes"` // system.set_idle：闲置多少分钟结束进程，0 是不自动结束
	Redact  bool     `json:"redact"`  // diag.tail：给内容打码（见 helper/redact）
}

// oneShots 是这一组的方法。参数不合要求时整个请求出错，什么也不做（同子命令）。
var oneShots = map[string]func(e *env, p oneShotParams) (any, error){
	"storage.scan":         storageScanMethod,
	"storage.sessions":     storageSessionsMethod,
	"storage.clean":        storageCleanMethod,
	"files.ls":             lsMethod,
	"files.read":           readMethod,
	"images.file":          fileImageMethod,
	"system.cpu":           systemCPUMethod,
	"system.idle":          systemIdleMethod,
	"system.set_idle":      systemSetIdleMethod,
	"system.claude":        systemClaudeMethod,
	"system.claude_update": systemClaudeUpdateMethod,
	"system.agy":           systemAgyMethod,
	"system.agy_update":    systemAgyUpdateMethod,
	"diag.files":           diagFilesMethod,
	"diag.tail":            diagTailMethod,
}

// oneShotHandler 做 oneShots 里的方法，挂在 "storage."、"files."、"images."、"system."、"diag." 上（newCoreHandler）。
type oneShotHandler struct{ e *env }

func (h oneShotHandler) Request(_ *core.Client, r core.Request) (any, error) {
	run := oneShots[r.Method]
	if run == nil {
		return nil, errors.New("unknown method: " + r.Method)
	}
	var p oneShotParams
	if err := json.Unmarshal(r.Raw, &p); err != nil {
		return nil, errors.New("bad params: " + err.Error())
	}
	return run(h.e, p)
}

func (oneShotHandler) Gone(*core.Client) {}
func (oneShotHandler) Idle() bool        { return true }

var errBadRequest = errors.New("参数不合要求")

// keepIDs：要保护的会话号可以没有，有就都要像会话号。
func keepIDs(ids []string) bool { return allOf(ids, isSessionID) }

func storageScanMethod(e *env, p oneShotParams) (any, error) {
	if !keepIDs(p.Keep) {
		return nil, errBadRequest
	}
	return scanStorage(e, p.Keep), nil
}

func storageSessionsMethod(e *env, p oneShotParams) (any, error) {
	if p.Project == "" || !keepIDs(p.Keep) {
		return nil, errBadRequest
	}
	return storageSessions(e, p.Project, p.Keep)
}

func storageCleanMethod(e *env, p oneShotParams) (any, error) {
	if !cleanable(p.Kind) || !keepIDs(p.Keep) {
		return nil, errBadRequest
	}
	return cleanStorage(e, p.Kind, p.Keep), nil
}

func lsMethod(_ *env, p oneShotParams) (any, error) {
	if !strings.HasPrefix(p.Path, "/") {
		return nil, errBadRequest
	}
	return listPath(p.Path)
}

// previewBytes 是 files.read 不给 bytes 时读多少（同手机文件浏览的预览）。
const previewBytes = 256 * 1024

func readMethod(_ *env, p oneShotParams) (any, error) {
	if !strings.HasPrefix(p.Path, "/") || p.Bytes < 0 {
		return nil, errBadRequest
	}
	if p.Bytes == 0 {
		p.Bytes = previewBytes
	}
	return readPreview(p.Path, p.Bytes)
}

func fileImageMethod(e *env, p oneShotParams) (any, error) {
	if p.Path == "" {
		return nil, errBadRequest
	}
	return fileImage(e, p.Path)
}
