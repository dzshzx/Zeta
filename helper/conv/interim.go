package conv

// 临时接线：helper/prompt、helper/usage、helper/tasks 合进来后整份换掉。

import (
	"encoding/json"
	"strconv"
)

func taskOutputView(r json.RawMessage) any { return r }

func formatTokens(n int64) string { return strconv.FormatInt(n, 10) }

type noUsage struct{}

func (noUsage) processStarted(bool, int)                  {}
func (noUsage) observe([]byte) bool                       { return false }
func (noUsage) prependHistory([][]byte)                   {}
func (noUsage) contextUsageAnswered(json.RawMessage) bool { return false }
func (noUsage) settingsAnswered(json.RawMessage) bool     { return false }
func (noUsage) view() any                                 { return nil }

type noTasks struct{}

func (noTasks) observe([]byte, int64) bool                { return false }
func (noTasks) stopping(string)                           {}
func (noTasks) stopAnswered(string, json.RawMessage) bool { return false }
func (noTasks) processEnded(int64) bool                   { return false }
func (noTasks) view() any                                 { return []any{} }

func (Deps) newUsage() usageTracker { return noUsage{} }
func (Deps) newTasks() taskBoard    { return noTasks{} }
