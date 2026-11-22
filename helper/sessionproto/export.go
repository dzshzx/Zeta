package sessionproto

import (
	"fmt"
	"strings"
	"unicode"
)

// ExportPart 是一个会话的导出：sessions.export 带 sessions 时 result 的 parts 里的一项，按请求的顺序。
type ExportPart struct {
	Session  string `json:"session"`
	Title    string `json:"title"`
	Markdown string `json:"markdown"`
}

// CombineExports 把几个会话的导出按 parts 的先后合成一份（照 Kotlin 的 combineExports、exportName）：
// 只有一个时原样；几个时标题是「N个对话」，正文是各自去掉末尾空白、补一个换行，中间用分隔线隔开。
// 核心合成同一台主机上的几个、客户端合成几台设备的都用它，按用户选的先后。
func CombineExports(parts []ExportPart) (title, markdown string) {
	if len(parts) == 1 {
		return parts[0].Title, parts[0].Markdown
	}
	bodies := make([]string, len(parts))
	for i, p := range parts {
		bodies[i] = strings.TrimRightFunc(p.Markdown, unicode.IsSpace) + "\n"
	}
	return fmt.Sprintf("%d个对话", len(parts)), strings.Join(bodies, "\n---\n\n")
}
