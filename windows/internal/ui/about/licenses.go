package about

import (
	"errors"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/hub/assets"
	"github.com/dzshzx/Zeta/windows/internal/locales"
)

// License 是一项开源许可：程序里带着的一个模块，或 Go 标准库。全文由 hub/scripts/assets.sh 从模块目录里取，编进程序。
type License struct {
	Where   string // app：Windows 程序链接的；host：带到主机上的 zeta-helper、claude-switch 链接的
	Module  string // 模块路径，Go 标准库是 "Go"
	Version string
	file    string // assets 里 licenses/ 下的文件名
}

// Text 是许可证全文。
func (l License) Text() string { return readLicense(l.file) }

// zetaLicense 是 Zeta 自己的许可证（GPL-3.0）全文。
func zetaLicense() string { return readLicense("Zeta") }

func readLicense(file string) string {
	data, err := assets.Read("licenses/" + file)
	if err != nil {
		return locales.AboutLicenseTextFailed(err.Error())
	}
	return string(data)
}

// licenses 读 assets 里的 index.tsv：一行一项，用在哪、模块、版本、文件名，以制表符分隔。
func licenses() ([]License, error) {
	data, err := assets.Read("licenses/index.tsv")
	if err != nil {
		return nil, err
	}
	var out []License
	for i, line := range strings.Split(strings.TrimSpace(string(data)), "\n") {
		f := strings.Split(line, "\t")
		if len(f) != 4 || (f[0] != "app" && f[0] != "host") {
			return nil, errors.New(locales.AboutLicensesBadLine(i+1, strconv.Quote(line)))
		}
		out = append(out, License{Where: f[0], Module: f[1], Version: f[2], file: f[3]})
	}
	return out, nil
}

// Title 是列表里这一项的写法：模块和版本；Go 标准库写成「Go 标准库与运行时 1.27.4」。
func (l License) Title() string {
	if l.Module == "Go" {
		return locales.AboutLicenseGoStdlib(strings.TrimPrefix(l.Version, "go"))
	}
	return l.Module + " " + l.Version
}
