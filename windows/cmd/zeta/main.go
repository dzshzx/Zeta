// Command zeta 是 Zeta 的 Windows 桌面版：经 SSH 用远程主机上的 Claude Code（见 docs/desktop.md）。
package main

import (
	"log"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

func main() {
	mygo.App.WhenReady(func() {
		mygo.NewWindow(mygo.WindowOptions{Title: "Zeta", Width: 1280, Height: 860, Content: ui.View(view)})
	})
	if err := mygo.App.Run(); err != nil {
		log.Fatal(err)
	}
}

func view(c *ui.Context) {
	ui.Column(c).Fill().Center().Children(func() {
		ui.Text(c, "Zeta").FontSize(28).Bold()
	})
}
