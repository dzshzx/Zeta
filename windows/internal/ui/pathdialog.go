package ui

import (
	"strings"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// pathAttach 是附件菜单「输入路径」的对话框（同 AttachPathDialogs.kt 的 PathAttachDialog）：填设备上的一个路径，
// 停下来一会儿就查它在不在、是文件还是文件夹，查到能读的才能引用（@路径，文件夹是 @路径/）。
type pathAttach struct {
	open    bool
	input   string
	checked string // 查过、正在查的输入
	seq     int    // 第几次查：输入又变了时，旧的结果不要
	result  pathResult
}

// pathResult 是一次查的结果。
type pathResult struct {
	checking bool
	path     string // 归一化后的路径；能引用时不为空
	folder   bool
	problem  string // 不能引用的原因
}

// pathCheckDelay 是输入停下来多久才查（同 Kotlin 版的 350 毫秒）；测试里是 0。
var pathCheckDelay = 350 * time.Millisecond

func (a *App) openPathDialog() { a.path = pathAttach{open: true} }

// pathDialog 画「输入路径」的对话框。
func (a *App) pathDialog(c *ui.Context, p *chatPane) {
	d := &a.path
	if !d.open {
		return
	}
	if d.input != d.checked {
		a.checkPath(p, d)
	}
	k := widget.Of(c)
	name := a.hub.Name(p.src.Backend)
	r := d.result
	widget.Dialog(c, &d.open, func() {
		ui.Column(c).FillWidth().Padding(widget.DialogPadding, widget.DialogPadding, 4, widget.DialogPadding).Gap(6).
			Children(func() {
				ui.Text(c, locales.PathDialogTitle(name)).FontSize(widget.SizeHeadlineXS).FontWeight(600).TextColor(k.Text)
				ui.Text(c, locales.PathDialogHint()).FontSize(widget.SizeBodyS).LineHeight(1.45).
					TextColor(k.Secondary)
				// 查到的结果跟在输入框末尾，边框连着它画
				field := ui.Row(c).FillWidth().AlignItems(ui.Center).Gap(8).Padding(0, 10, 0, 0).Radius(8).Border(1, k.Divider)
				field.Children(func() {
					in := widget.LineInput(c.Key("path"), &d.input, 1).Placeholder(strings.TrimRight(a.chatCwd(p), "/")+"/…").Label(locales.PathDialogPath()).
						AutoFocus().BorderWidth(0, 0, 0, 0).Grow(1).OnSubmit(func() { a.attachPath(p) })
					if in.Focused() {
						field.BorderColor(k.Primary)
					}
					if label, color := pathKindLabel(k, r, d.input); label != "" {
						ui.Text(c, label).FontSize(widget.SizeBodyS).TextColor(color).Shrink(0)
					}
				})
				if r.problem != "" {
					ui.Text(c, r.problem).FontSize(widget.SizeBodyS).TextColor(k.Error).FillWidth().Padding(4, 0, 0, 0)
				}
			})
		widget.ButtonBar(c, func() {
			if widget.DialogButton(c, locales.CommonCancel(), widget.Plain).Clicked() {
				d.open = false
			}
			if widget.DialogButton(c, locales.PathDialogAttach(), widget.Recommend).Disabled(r.path == "").Clicked() {
				a.attachPath(p)
			}
		})
	})
}

// pathKindLabel 是跟在输入框后面的查询结果：文件、文件夹、正在查…、不可用；还没输入时没有。
func pathKindLabel(k *widget.Colors, r pathResult, input string) (string, ui.Color) {
	switch {
	case strings.TrimSpace(input) == "":
		return "", k.Text
	case r.checking:
		return locales.PathDialogChecking(), k.Secondary
	case r.path != "" && r.folder:
		return locales.PathDialogFolder(), k.PrimaryText
	case r.path != "":
		return locales.PathDialogFile(), k.PrimaryText
	case r.problem != "":
		return locales.PathDialogUnavailable(), k.Error
	}
	return "", k.Text
}

// checkPath 输入变了：停一会儿再查，查完时输入还是那个才用结果。
func (a *App) checkPath(p *chatPane, d *pathAttach) {
	d.checked = d.input
	d.seq++
	d.result = pathResult{}
	input, seq := d.input, d.seq
	if strings.TrimSpace(input) == "" {
		return
	}
	x, _ := a.hub.Host(p.src.Backend)
	path, ok := hostlink.NormalizePath(input, x.Home)
	if !ok {
		d.result.problem = locales.PathDialogNotAbsolute()
		if t := strings.TrimSpace(input); strings.HasPrefix(t, "/") || strings.HasPrefix(t, "~") {
			d.result.problem = locales.PathDialogBadChars()
		}
		return
	}
	d.result.checking = true
	check := a.hub.PathChecker(p.src.Backend)
	a.async(func() func() {
		time.Sleep(pathCheckDelay)
		r := resolvePath(check, path)
		return func() {
			if d.seq == seq {
				d.result = r
			}
		}
	})
}

// resolvePath 查 path，换成能不能引用和原因。
func resolvePath(check func(string) (hostlink.PathKind, error), path string) pathResult {
	kind, err := check(path)
	switch {
	case err != nil:
		return pathResult{problem: locales.PathDialogCheckError(err.Error())}
	case kind == hostlink.PathFolder:
		return pathResult{path: path, folder: true}
	case kind == hostlink.PathFile:
		return pathResult{path: path}
	case kind == hostlink.PathMissing:
		return pathResult{problem: locales.PathDialogMissing(path)}
	case kind == hostlink.PathDenied:
		return pathResult{problem: locales.PathDialogDenied(path)}
	}
	return pathResult{problem: locales.PathDialogCheckFailed()}
}

// attachPath 引用查好的路径，关上对话框；还没查好、不能引用时不动。
func (a *App) attachPath(p *chatPane) {
	d := &a.path
	r := d.result
	if r.path == "" || d.input != d.checked {
		return
	}
	entry := hostlink.Reference(r.path, r.folder)
	p.att.entryFile(entry)
	d.open = false
}

// chatCwd 是对话的工作目录：核心报的，还没报时是会话记下的。
func (a *App) chatCwd(p *chatPane) string {
	if p.state.Cwd != "" {
		return p.state.Cwd
	}
	return p.src.Session.Cwd
}
