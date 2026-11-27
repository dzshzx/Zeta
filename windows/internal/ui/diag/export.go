package diag

import (
	"context"

	"github.com/dzshzx/Zeta/hub/diagbundle"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// exportGroup 是「导出诊断包」一行：打包时写正在打包，存好后写存到哪，没成时写原因。
func (p *Page) exportGroup(c *ui.Context) {
	k := widget.Of(c)
	widget.Group(c, "", locales.DiagExportNote(), func(l *widget.List) {
		row := widget.Row{Title: locales.DiagExport()}
		switch {
		case p.exporting:
			row.Summary = locales.DiagExporting()
		case p.exportErr != nil:
			row.Summary, row.SummaryColor = locales.DiagExportFailed(p.exportErr.Error()), k.Error
		case p.saved != "":
			row.Summary = locales.DiagExportSaved(p.saved)
		}
		if !p.exporting {
			row.OnClick = p.export
		}
		l.Row(row)
	})
}

// export 在后台打包，再弹保存对话框存下。设备清单和环境信息在主线程上取。
func (p *Page) export() {
	if p.exporting {
		return
	}
	devices := p.o.Devices()
	var env []Line
	if p.o.Env != nil {
		env = p.o.Env()
	}
	now := p.o.Now()
	p.exporting, p.saved, p.exportErr = true, "", nil
	p.o.Run(func() {
		header := locales.DiagBundleHeader(now.Format("2006-01-02 15:04:05 -0700"))
		data, err := diagbundle.Bundle(context.Background(), header, devices, env, p.o.Dir, now)
		saved := ""
		if err == nil {
			saved, err = p.o.Save("zeta-diag-"+now.Format("20060102-150405")+".zip", data)
		}
		p.o.Post(func() { p.exporting, p.saved, p.exportErr = false, saved, err })
	})
}
