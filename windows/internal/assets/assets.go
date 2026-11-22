// Package assets 是桌面版带到主机上的东西：zeta-helper、claude-switch 的 linux arm64、amd64 两份，托管脚本 zeta_host.sh，
// 以及关于页列的开源许可全文 licenses/。都由 windows/scripts/assets.sh 放进 gen/（不进 Git）；没跑过它时这个包编不过，报 gen 找不到。
package assets

import "embed"

//go:embed gen
var files embed.FS

// Read 读一样东西，name 是 gen 下的路径，比如 "zeta-helper/linux-amd64"、"zeta_host.sh"。
func Read(name string) ([]byte, error) { return files.ReadFile("gen/" + name) }
