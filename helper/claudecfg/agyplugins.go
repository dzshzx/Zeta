package claudecfg

import (
	"bytes"
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"strings"
)

// AgyPlugin 是 `agy plugin list --json` 返回的一条插件摘要。
type AgyPlugin struct {
	Name        string `json:"name"`
	Version     string `json:"version,omitempty"`
	Description string `json:"description,omitempty"`
	Enabled     bool   `json:"enabled"`
	Source      string `json:"source,omitempty"`
}

// runAgyPluginCmd 可在单元测试中替换，避免依赖宿主真实 `agy` 命令。
var runAgyPluginCmd = defaultRunAgyPluginCmd

func defaultRunAgyPluginCmd(ctx context.Context, home string, args ...string) ([]byte, error) {
	bin := cmp.Or(strings.TrimSpace(os.Getenv("ZETA_AGY_BIN")), "agy")
	cmd := exec.CommandContext(ctx, bin, append([]string{"plugin"}, args...)...)
	cmd.Env = append(os.Environ(), "HOME="+home, "BROWSER=true")
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	if err != nil {
		msg := strings.TrimSpace(stderr.String())
		if msg == "" {
			msg = strings.TrimSpace(stdout.String())
		}
		if msg != "" {
			return nil, rawError(msg)
		}
		return nil, err
	}
	return stdout.Bytes(), nil
}

// ListPlugins 调用 `agy plugin list --json` 获取已安装的插件清单。
func (s *Store) ListPlugins(ctx context.Context) ([]AgyPlugin, error) {
	out, err := runAgyPluginCmd(ctx, s.Home(), "list", "--json")
	if err != nil {
		return nil, err
	}
	return parsePluginList(out)
}

// SetPluginEnabled 调用 `agy plugin enable|disable <name>` 启停插件并返回最新清单。
func (s *Store) SetPluginEnabled(ctx context.Context, name string, enabled bool) ([]AgyPlugin, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return nil, errors.New("插件名不能为空")
	}
	verb := "disable"
	if enabled {
		verb = "enable"
	}
	if _, err := runAgyPluginCmd(ctx, s.Home(), verb, name); err != nil {
		return nil, err
	}
	return s.ListPlugins(ctx)
}

// InstallPlugin 调用 `agy plugin install <target>` 安装插件并返回最新清单。
func (s *Store) InstallPlugin(ctx context.Context, target string) ([]AgyPlugin, error) {
	target = strings.TrimSpace(target)
	if target == "" {
		return nil, errors.New("插件安装目标不能为空")
	}
	if _, err := runAgyPluginCmd(ctx, s.Home(), "install", target); err != nil {
		return nil, err
	}
	return s.ListPlugins(ctx)
}

// UninstallPlugin 调用 `agy plugin uninstall <name>` 卸载插件并返回最新清单。
func (s *Store) UninstallPlugin(ctx context.Context, name string) ([]AgyPlugin, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return nil, errors.New("插件名不能为空")
	}
	if _, err := runAgyPluginCmd(ctx, s.Home(), "uninstall", name); err != nil {
		return nil, err
	}
	return s.ListPlugins(ctx)
}

func parsePluginList(raw []byte) ([]AgyPlugin, error) {
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 {
		return []AgyPlugin{}, nil
	}
	var direct []AgyPlugin
	if json.Unmarshal(trimmed, &direct) == nil {
		if direct == nil {
			return []AgyPlugin{}, nil
		}
		return direct, nil
	}
	var wrapped struct {
		Plugins []AgyPlugin `json:"plugins"`
	}
	if err := json.Unmarshal(trimmed, &wrapped); err != nil {
		return nil, err
	}
	if wrapped.Plugins == nil {
		return []AgyPlugin{}, nil
	}
	return wrapped.Plugins, nil
}
