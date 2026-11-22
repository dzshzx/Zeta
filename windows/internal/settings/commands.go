package settings

import (
	"bytes"
	"encoding/json"
)

// Commands 是设备 backend 上 Claude Code 上次报的斜杠命令（原样的 JSON 数组，chat.ParseCommands 读）：还没起过进程时输入栏提示它。
// 没存过时为空。
func (s *Settings) Commands(backend string) json.RawMessage {
	return s.commands()[backend]
}

// SetCommands 记下设备 backend 上 Claude Code 报的斜杠命令（同 Kotlin 版的 Settings.commands，各台一份）；和存着的一样时不写盘。
func (s *Settings) SetCommands(backend string, list json.RawMessage) error {
	all := s.commands()
	if all == nil {
		all = map[string]json.RawMessage{}
	}
	if bytes.Equal(all[backend], list) {
		return nil
	}
	all[backend] = list
	data, err := marshal(all)
	if err != nil {
		return err
	}
	return s.store.SetString(keyCommands, string(data))
}

func (s *Settings) commands() map[string]json.RawMessage {
	text, _ := s.store.String(keyCommands)
	var all map[string]json.RawMessage
	if json.Unmarshal([]byte(text), &all) != nil {
		return nil
	}
	return all
}
