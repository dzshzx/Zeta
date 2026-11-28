package claudecfg

import (
	"cmp"
	"context"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/helper/locales"
)

var agyMcpMu sync.Mutex

func (s *Store) scanAgyMcp(cwd string) ([]mcpEntry, error) {
	cwd = cmp.Or(cwd, s.Home())
	userBytes, size, _ := readHead(agyMcpConfigPath(s.Home()), 4<<20)
	if int64(len(userBytes)) < size {
		return nil, locales.Err("cfgFileTooBig", "mib", 4)
	}
	found := appendAgyMcpFromBytes(nil, userBytes, "user")

	disabled := s.agyDisabledPlugins()
	userPlugins, _ := filepath.Glob(agyConfigDir(s.Home()) + "/plugins/*/mcp_config.json")
	slices.Sort(userPlugins)
	for _, p := range userPlugins {
		if disabled[filepath.Base(filepath.Dir(p))] {
			continue
		}
		if data, _, ok := readHead(p, 2<<20); ok {
			found = appendAgyMcpFromBytes(found, data, "user")
		}
	}

	wsRoot := agyWorkspaceRoot(cwd, s.Home())
	pluginConfigs, _ := filepath.Glob(wsRoot + "/.agents/plugins/*/mcp_config.json")
	slices.Sort(pluginConfigs)
	for _, p := range pluginConfigs {
		if data, _, ok := readHead(p, 2<<20); ok {
			found = appendAgyMcpFromBytes(found, data, "project")
		}
	}
	return found, nil
}

func appendAgyMcpFromBytes(found []mcpEntry, data []byte, scope string) []mcpEntry {
	o, err := parseObject(data)
	if err != nil || o == nil {
		return found
	}
	servers := o.obj("mcpServers")
	if servers == nil {
		return found
	}
	for _, name := range servers.keys {
		cfg := servers.obj(name)
		if cfg == nil {
			continue
		}
		normalizeAgyMcpEntry(cfg)
		if !slices.ContainsFunc(found, func(e mcpEntry) bool { return e.scope == scope && e.name == name }) {
			found = append(found, mcpEntry{name: name, scope: scope, config: cfg})
		}
	}
	return found
}

func normalizeAgyMcpEntry(cfg *object) {
	if u, _ := cfg.str("url"); u == "" {
		if su, ok := cfg.str("serverUrl"); ok && su != "" {
			cfg.set("url", su)
		}
	}
}

func (s *Store) updateAgyMcpFile(edit func(servers *object) error) error {
	_, err := updateJSONFile(agyMcpConfigPath(s.Home()), &agyMcpMu, func(root *object) error {
		servers, err := childObject(root, "mcpServers", errInvalidSettings)
		if err != nil {
			return err
		}
		return edit(servers)
	})
	return err
}

func (s *Store) addAgyJSON(scope, name string, cfg *object) error {
	if scope != "user" {
		return locales.Err("cfgMcpNotEditable")
	}
	clean := cloneObject(cfg)
	clean.del("type")
	if su, ok := clean.str("serverUrl"); ok && su != "" {
		if u, _ := clean.str("url"); u == su {
			clean.del("url")
		}
	}
	return s.updateAgyMcpFile(func(servers *object) error {
		servers.set(name, clean)
		return nil
	})
}

func (s *Store) removeAgyMcp(scope, name string) error {
	if scope != "user" {
		return locales.Err("cfgMcpNotEditable")
	}
	return s.updateAgyMcpFile(func(servers *object) error {
		if _, ok := servers.get(name); !ok {
			return locales.Err("cfgMcpNotFound")
		}
		servers.del(name)
		return nil
	})
}

func (s *Store) setAgyMcpToken(scope, name, cwd, token string) error {
	if scope != "user" {
		return locales.Err("cfgMcpNotEditable")
	}
	orig, err := s.entry(scope, name, cwd)
	if err != nil {
		return err
	}
	if orig.typ() == "stdio" {
		return locales.Err("cfgMcpStdioNoToken")
	}
	updated := withToken(orig.config, strings.TrimSpace(token))
	return s.addAgyJSON("user", name, updated)
}

func (s *Store) agyMcpStatus(ctx context.Context, cwd string) (McpStatus, error) {
	cwd = cmp.Or(cwd, s.Home())
	entries, err := s.scanAgyMcp(cwd)
	if err != nil {
		return McpStatus{}, err
	}
	st := McpStatus{Servers: map[string]McpState{}}
	if len(entries) == 0 {
		return st, nil
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	const script = `cd -- "$1" 2>/dev/null || cd; PATH="$PATH:$HOME/.local/bin"; exec "${ZETA_AGY_BIN:-agy}" mcp list`
	text, code, err := run(ctx, s.loginCommand(ctx, script, []string{cwd}, nil), nil)
	if err != nil || code != 0 {
		for _, e := range entries {
			st.Servers[McpStatusKey(e.scope, e.name)] = McpState{State: "unknown"}
		}
		return st, nil
	}
	return parseAgyMcpList(text, entries), nil
}

func parseAgyMcpList(output string, entries []mcpEntry) McpStatus {
	st := McpStatus{Servers: map[string]McpState{}}
	byName := map[string]string{}
	for _, line := range strings.Split(output, "\n") {
		fields := strings.Fields(line)
		if len(fields) < 3 || strings.EqualFold(fields[0], "NAME") {
			continue
		}
		status := strings.ToLower(fields[2])
		switch {
		case status == "enabled" || status == "connected" || status == "ok":
			byName[fields[0]] = "connected"
		case status == "disabled":
			byName[fields[0]] = "disabled"
		case strings.Contains(status, "auth"):
			byName[fields[0]] = "auth"
		case strings.Contains(status, "fail") || strings.Contains(status, "error"):
			byName[fields[0]] = "failed"
		default:
			byName[fields[0]] = "unknown"
		}
	}
	for _, e := range entries {
		key := McpStatusKey(e.scope, e.name)
		state := byName[e.name]
		if state == "" {
			state = "unknown"
		}
		st.Servers[key] = McpState{State: state}
	}
	return st
}
