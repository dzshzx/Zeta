package ui

import (
	"strings"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
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

// pathMissing 是查不到路径时说明的开头。
const pathMissing = "没有这个路径："

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
	k := coui.Of(c)
	name := a.hub.Name(p.src.Backend)
	r := d.result
	coui.Dialog(c, &d.open, func() {
		ui.Column(c).FillWidth().Padding(coui.DialogPadding, coui.DialogPadding, 4, coui.DialogPadding).Gap(6).
			Children(func() {
				ui.Text(c, "引用「"+name+"」上的路径").FontSize(coui.SizeHeadlineXS).FontWeight(600).TextColor(k.Text)
				ui.Text(c, "这台设备上一个文件或文件夹的绝对路径，Claude 会直接读到它。").FontSize(coui.SizeBodyS).LineHeight(1.45).
					TextColor(k.Secondary)
				// 查到的结果跟在输入框末尾，边框连着它画
				field := ui.Row(c).FillWidth().AlignItems(ui.Center).Gap(8).Padding(0, 10, 0, 0).Radius(8).Border(1, k.Divider)
				field.Children(func() {
					in := coui.LineInput(c.Key("path"), &d.input, 1).Placeholder(strings.TrimRight(a.chatCwd(p), "/")+"/…").Label("路径").
						AutoFocus().BorderWidth(0, 0, 0, 0).Grow(1).OnSubmit(func() { a.attachPath(p) })
					if in.Focused() {
						field.BorderColor(k.Primary)
					}
					if label, color := pathKindLabel(k, r, d.input); label != "" {
						ui.Text(c, label).FontSize(coui.SizeBodyS).TextColor(color).Shrink(0)
					}
				})
				if r.problem != "" {
					ui.Text(c, r.problem).FontSize(coui.SizeBodyS).TextColor(k.Error).FillWidth().Padding(4, 0, 0, 0)
				}
			})
		coui.ButtonBar(c, func() {
			if coui.DialogButton(c, "取消", coui.Plain).Clicked() {
				d.open = false
			}
			if coui.DialogButton(c, "引用", coui.Recommend).Disabled(r.path == "").Clicked() {
				a.attachPath(p)
			}
		})
	})
}

// pathKindLabel 是跟在输入框后面的查询结果：文件、文件夹、正在查…、不可用；还没输入时没有。
func pathKindLabel(k *coui.Colors, r pathResult, input string) (string, ui.Color) {
	switch {
	case strings.TrimSpace(input) == "":
		return "", k.Text
	case r.checking:
		return "正在查…", k.Secondary
	case r.path != "" && r.folder:
		return "文件夹", k.PrimaryText
	case r.path != "":
		return "文件", k.PrimaryText
	case r.problem != "":
		return "不可用", k.Error
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
		d.result.problem = "要输入以 / 开头的绝对路径"
		if t := strings.TrimSpace(input); strings.HasPrefix(t, "/") || strings.HasPrefix(t, "~") {
			d.result.problem = "路径里不能有双引号或控制字符"
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
		return pathResult{problem: "查不了：" + err.Error()}
	case kind == hostlink.PathFolder:
		return pathResult{path: path, folder: true}
	case kind == hostlink.PathFile:
		return pathResult{path: path}
	case kind == hostlink.PathMissing:
		return pathResult{problem: pathMissing + path}
	case kind == hostlink.PathDenied:
		return pathResult{problem: "没有权限读取 " + path}
	}
	return pathResult{problem: "查不了这个路径"}
}

// attachPath 引用查好的路径，关上对话框；还没查好、不能引用时不动。
func (a *App) attachPath(p *chatPane) {
	d := &a.path
	r := d.result
	if r.path == "" || d.input != d.checked {
		return
	}
	entry := hostlink.Reference(r.path, r.folder)
	if !containsEntry(p.att.files, entry) {
		p.att.files = append(p.att.files, p.att.newEntryFile(entry, ""))
	}
	d.open = false
}

// chatCwd 是对话的工作目录：核心报的，还没报时是会话记下的。
func (a *App) chatCwd(p *chatPane) string {
	if p.state.Cwd != "" {
		return p.state.Cwd
	}
	return p.src.Session.Cwd
}
