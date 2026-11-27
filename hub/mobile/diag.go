package mobile

import (
	"context"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/hub/appversion"
	"github.com/dzshzx/Zeta/hub/assets"
	"github.com/dzshzx/Zeta/hub/diagbundle"
	"github.com/dzshzx/Zeta/hub/locales"
	"github.com/dzshzx/Zeta/hub/runlog"
)

// 设置里的「诊断」和「关于」：Zeta 自己的运行日志（hub/runlog）、导出诊断包（hub/diagbundle）、版本、源码和开源许可。
// 各台设备上 Claude Code 那边的日志是核心的 diag.files、diag.tail，界面经 core 转发去读。

// LogView 是方法 diaglog 的结果：按类别、轻重筛过的运行日志（新的在前），和能筛的类别。
type LogView struct {
	Entries []LogEntry  `json:"entries"`
	Topics  []LogChoice `json:"topics"`
	Levels  []LogChoice `json:"levels"`
	// Available 为假是这次没开运行日志（测试里），界面不列这一组。
	Available bool `json:"available"`
}

// LogEntry 是一条记录：时刻（毫秒）、轻重（0 信息、1 警告、2 出错）和它的名字、类别的名字、消息。
type LogEntry struct {
	At        int64  `json:"at"`
	Level     int    `json:"level"`
	LevelName string `json:"level_name"`
	Topic     string `json:"topic"`
	Message   string `json:"message"`
}

// LogChoice 是筛选里的一项：交回 diaglog 的值和给人看的名字。
type LogChoice struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// About 是关于页：版本、这个版本源码的 tag 或提交和链接、开源许可。
type About struct {
	Version  string    `json:"version"`
	Code     int       `json:"code"`
	Ref      string    `json:"ref"`
	URL      string    `json:"url"`
	Licenses []License `json:"licenses"`
	// Problem 是许可清单读不出的原因。
	Problem string `json:"problem"`
}

// License 是一项开源许可：用在哪（ios：App 链接的；host：带到设备上的 zeta-helper、claude-switch 链接的）、模块
// （"Go" 是 Go 标准库与运行时）、版本，File 交给 license 取全文。
type License struct {
	Where   string `json:"where"`
	Module  string `json:"module"`
	Version string `json:"version"`
	File    string `json:"file"`
}

type (
	logParams struct {
		Topic string `json:"topic"` // 空是全部
		Level int    `json:"level"` // 至少这么重
	}
	exportParams struct {
		Header string            `json:"header"`
		Env    []diagbundle.Line `json:"env"`
	}
	licenseParams struct {
		File string `json:"file"`
	}
)

func init() {
	reading("diaglog", func(c *Core, p logParams) (any, error) { return c.logView(p), nil })
	onLoop("diaglog.clear", func(c *Core, _ none) (any, error) {
		if c.log == nil {
			return ok, nil
		}
		return ok, c.log.Clear()
	})
	blocking("diag.export", func(c *Core, p exportParams) (any, error) { return c.export(p) })
	reading("about", func(*Core, none) (any, error) { return about(), nil })
	reading("license", func(_ *Core, p licenseParams) (any, error) {
		text, err := licenseText(p.File)
		return map[string]string{"text": text}, err
	})
}

func (c *Core) logView(p logParams) LogView {
	v := LogView{Entries: []LogEntry{}, Topics: []LogChoice{}, Available: c.log != nil}
	for _, t := range runlog.Topics {
		v.Topics = append(v.Topics, LogChoice{ID: string(t), Name: t.Name()})
	}
	for _, l := range []runlog.Level{runlog.Info, runlog.Warn, runlog.Error} {
		v.Levels = append(v.Levels, LogChoice{ID: l.Label(), Name: l.Name()})
	}
	if c.log == nil {
		return v
	}
	entries := c.log.Entries()
	for _, e := range slices.Backward(entries) {
		if !e.Matches(runlog.Topic(p.Topic), runlog.Level(p.Level)) {
			continue
		}
		v.Entries = append(v.Entries, LogEntry{At: e.At.UnixMilli(), Level: int(e.Level), LevelName: e.Level.Name(),
			Topic: e.Topic.Name(), Message: e.Message})
	}
	return v
}

// export 打诊断包，写进临时目录，答文件的路径（界面用分享面板交出去）。各台设备在核心 goroutine 上取，打包在调用方的线程上。
func (c *Core) export(p exportParams) (any, error) {
	var devices []diagbundle.Device
	c.Do(func() {
		for _, x := range c.hub.Hosts() {
			d := diagbundle.Device{ID: x.ID, Name: x.Name()}
			if r := c.hub.Remote(x.ID); r != nil {
				d.Core = r.Core
			}
			devices = append(devices, d)
		}
	})
	now := time.Now()
	dir := ""
	if c.dir != "" {
		dir = filepath.Join(c.dir, "diag")
	}
	data, err := diagbundle.Bundle(context.Background(), p.Header, devices, p.Env, dir, now)
	if err != nil {
		return nil, err
	}
	out := filepath.Join(os.TempDir(), "zeta-diag-"+now.Format("20060102-150405")+".zip")
	if err := os.WriteFile(out, data, 0o600); err != nil {
		return nil, err
	}
	return map[string]string{"path": out}, nil
}

func about() About {
	v := appversion.Current()
	a := About{Version: v.Name, Code: v.Code, Ref: appversion.SourceRef(v.Name), URL: appversion.SourceURL(v.Name),
		Licenses: []License{}}
	list, err := licenses()
	if err != nil {
		a.Problem = locales.ErrorText(err)
	}
	a.Licenses = append(a.Licenses, list...)
	return a
}

// licenses 读 assets 里 index.tsv（hub/scripts/assets.sh 写的）中 iOS 版要列的两组：ios 和 host。
func licenses() ([]License, error) {
	data, err := assets.Read("licenses/index.tsv")
	if err != nil {
		return nil, err
	}
	var out []License
	for line := range strings.SplitSeq(strings.TrimSpace(string(data)), "\n") {
		f := strings.Split(line, "\t")
		if len(f) != 4 {
			return nil, locales.Err(locales.MobileLicensesUnreadable)
		}
		if f[0] == "ios" || f[0] == "host" {
			out = append(out, License{Where: f[0], Module: f[1], Version: f[2], File: f[3]})
		}
	}
	return out, nil
}

// licenseText 是许可全文：file 是 Zeta（Zeta 自己的）或清单里列的一项，别的不读。
func licenseText(file string) (string, error) {
	if file != "Zeta" {
		list, err := licenses()
		if err != nil {
			return "", err
		}
		if !slices.ContainsFunc(list, func(l License) bool { return l.File == file }) {
			return "", locales.Err(locales.MobileLicensesUnreadable)
		}
	}
	data, err := assets.Read("licenses/" + file)
	if err != nil {
		return "", err
	}
	return string(data), nil
}
