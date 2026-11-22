package chatview

import "github.com/egoist/mygo/ui"

// 聊天列表用到的图标：24×24 的线条图，颜色跟着文字（ui.Icon 按 currentColor 画）。
var (
	iconChevron   = icon(`<path d="M6 9l6 6 6-6"/>`)
	iconThinking  = icon(`<path d="M9 18h6M10 21h4"/><path d="M12 3a6 6 0 0 0-3.8 10.6c.6.5.8 1.2.8 1.9V16h6v-.5c0-.7.2-1.4.8-1.9A6 6 0 0 0 12 3z"/>`)
	iconTerminal  = icon(`<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 9l3 3-3 3M13 15h4"/>`)
	iconFile      = icon(`<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>`)
	iconFileAdd   = icon(`<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5M12 11v6M9 14h6"/>`)
	iconEdit      = icon(`<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M13.5 6.5l4 4"/>`)
	iconSearch    = icon(`<circle cx="11" cy="11" r="6"/><path d="M20 20l-4.5-4.5"/>`)
	iconGlobe     = icon(`<circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3c2.4 2.5 3.5 5.5 3.5 9s-1.1 6.5-3.5 9c-2.4-2.5-3.5-5.5-3.5-9s1.1-6.5 3.5-9z"/>`)
	iconAgent     = icon(`<circle cx="6" cy="12" r="2"/><circle cx="18" cy="6" r="2"/><circle cx="18" cy="18" r="2"/><path d="M8 12h4M12 12V7a1 1 0 0 1 1-1h3M12 12v5a1 1 0 0 0 1 1h3"/>`)
	iconChecklist = icon(`<path d="M4 6l1.5 1.5L8 5M4 12l1.5 1.5L8 11M4 18l1.5 1.5L8 17M11 6h9M11 12h9M11 18h9"/>`)
	iconQuestion  = icon(`<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6V14M12 17h.01"/>`)
	iconSparkle   = icon(`<path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"/>`)
	iconPlug      = icon(`<path d="M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0zM12 17v4"/>`)
	iconWrench    = icon(`<path d="M15 4a5 5 0 0 0-4.6 6.9L4 17.3V20h2.7l6.4-6.4A5 5 0 0 0 20 9l-3 3-3-3 3-3a5 5 0 0 0-2-2z"/>`)
	iconDone      = icon(`<circle cx="12" cy="12" r="9"/><path d="M8 12.5l2.5 2.5L16 9.5"/>`)
	iconFolder    = icon(`<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>`)
	iconCopy      = icon(`<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>`)
	iconForward   = icon(`<path d="M5 12h14M13 6l6 6-6 6"/>`)
	iconImage     = icon(`<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="9.5" r="1.5"/><path d="M3 16l5-5 4 4 3-3 6 6"/>`)
	iconPhone     = icon(`<rect x="7" y="3" width="10" height="18" rx="2"/><path d="M11 18h2"/>`)
)

func icon(body string) *ui.SVG {
	return ui.MustParseSVG([]byte(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" ` +
		`stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">` + body + `</svg>`))
}
