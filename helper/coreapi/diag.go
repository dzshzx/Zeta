package coreapi

import "context"

// 这一组是 docs/core.md「诊断」的 diag.* 方法：这台主机上 Claude Code 那边的日志。

// 日志文件的类别（DiagFile.Kind），diag.files 按这个顺序列。
const (
	DiagRunning  = "running"  // 在跑的对话进程的标准错误（托管目录的 err.log）
	DiagFinished = "finished" // 结束的对话进程留下的那份（~/.cache/<名字>/stderr/）
	DiagDebug    = "debug"    // Claude Code 的调试日志（~/.claude/debug/）
)

// DiagTailBytes 是 diag.tail 最多取的字节数（文件的最后这么多）。
const DiagTailBytes = 64 * 1024

// DiagFile 是一个能看的日志文件。
type DiagFile struct {
	Kind     string `json:"kind"`
	Path     string `json:"path"`
	Size     int64  `json:"size"`
	Modified int64  `json:"modified"` // 毫秒
	Title    string `json:"title"`    // 对话标题，只有在跑的有
}

// DiagFiles 列出这台主机上能看的日志：在跑的、结束的最近 20 份、调试日志最近 30 个，各类里新的在前（diag.files）。
func (cl *Client) DiagFiles(ctx context.Context) ([]DiagFile, error) {
	r, err := get[struct {
		Files []DiagFile `json:"files"`
	}](ctx, cl.c, "diag.files", nil)
	return r.Files, err
}

// DiagTail 是一个日志文件的尾部。
type DiagTail struct {
	Text      string `json:"text"`      // 最后 DiagTailBytes 字节，按 UTF-8 解，坏字节换成替代符
	Size      int64  `json:"size"`      // 整个文件多大
	Truncated bool   `json:"truncated"` // 前面还有没取的
}

// ReadDiag 读 path 的尾部；redact 为真时给 token、密码这类值打码（helper/redact）。只许读 DiagFiles 列的那几处，别处出错（diag.tail）。
func (cl *Client) ReadDiag(ctx context.Context, path string, redact bool) (DiagTail, error) {
	return get[DiagTail](ctx, cl.c, "diag.tail", params{"path": path, "redact": redact})
}
