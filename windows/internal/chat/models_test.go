package chat

import (
	"slices"
	"testing"
)

// reportedModels 是 Claude Code 2.1.329 的 initialize 实际报的模型清单（顺序照原样，同手机版 ModelMenuTest 的 ReportedModels）。
func reportedModels() []ModelOption {
	out := []ModelOption{
		{Value: "default", DisplayName: "Default (recommended)", ResolvedModel: "claude-opus-5-5"},
		{Value: "opus", ResolvedModel: "claude-opus-5-5"},
		{Value: "fable", ResolvedModel: "claude-fable-5-2"},
		{Value: "sonnet", ResolvedModel: "claude-sonnet-5-5"},
		{Value: "haiku", ResolvedModel: "claude-haiku-5-5"},
	}
	for _, id := range []string{"claude-fable-5-1", "claude-haiku-4-5-20251001", "claude-sonnet-5", "claude-opus-5", "claude-fable-5",
		"claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6", "claude-sonnet-4-6"} {
		out = append(out, ModelOption{Value: id, ResolvedModel: id})
	}
	return out
}

func values(list []ModelOption) []string {
	var out []string
	for _, m := range list {
		out = append(out, m.Value)
	}
	return out
}

func TestSplitModelsAliasesOnTopOlderByFamilyNewestFirst(t *testing.T) {
	latest, older := SplitModels(reportedModels())
	if want := []string{"default", "opus", "fable", "sonnet", "haiku"}; !slices.Equal(values(latest), want) {
		t.Errorf("上层：%q，要 %q", values(latest), want)
	}
	want := []string{"claude-opus-5", "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6", "claude-fable-5-1", "claude-fable-5",
		"claude-sonnet-5", "claude-sonnet-4-6", "claude-haiku-4-5-20251001"}
	if !slices.Equal(values(older), want) {
		t.Errorf("更多模型：%q，要 %q", values(older), want)
	}
}

// 没有别名的系列，最新的完整 ID 放上层；和别名指向同一个模型的完整 ID 不重复列；没报完整 ID 的别名按名字认系列。
func TestSplitModelsSeriesWithoutAliasAndDuplicates(t *testing.T) {
	full := func(id string) ModelOption { return ModelOption{Value: id, ResolvedModel: id} }
	latest, older := SplitModels([]ModelOption{
		{Value: "default", ResolvedModel: "claude-opus-5-5"},
		{Value: "opus"},
		full("claude-opus-5-5"),
		full("claude-opus-5"),
		full("claude-lyric-1"),
		full("claude-lyric-1-2"),
		full("claude-opus-5-5[1m]"),
	})
	if want := []string{"default", "opus", "claude-lyric-1-2", "claude-opus-5-5[1m]"}; !slices.Equal(values(latest), want) {
		t.Errorf("上层：%q，要 %q", values(latest), want)
	}
	if want := []string{"claude-opus-5", "claude-lyric-1"}; !slices.Equal(values(older), want) {
		t.Errorf("更多模型：%q，要 %q", values(older), want)
	}
}

// 只有几个别名时全在上层，没有「更多模型」。
func TestSplitModelsAliasesOnly(t *testing.T) {
	aliases := []ModelOption{{Value: "opus"}, {Value: "sonnet"}, {Value: "haiku"}, {Value: "fable"}}
	latest, older := SplitModels(aliases)
	if !slices.Equal(latest, aliases) || len(older) != 0 {
		t.Errorf("%q %q", values(latest), values(older))
	}
}

// 排在最前面的旧版本完整 ID 照样收进「更多模型」（不因为它在第一个就当成最新的）。
func TestSplitModelsOlderFirstInList(t *testing.T) {
	latest, older := SplitModels([]ModelOption{
		{Value: "claude-opus-4-6", ResolvedModel: "claude-opus-4-6"},
		{Value: "opus", ResolvedModel: "claude-opus-5-5"},
	})
	if !slices.Equal(values(latest), []string{"opus"}) || !slices.Equal(values(older), []string{"claude-opus-4-6"}) {
		t.Fatalf("%q %q", values(latest), values(older))
	}
}

func TestReadableModel(t *testing.T) {
	for id, want := range map[string]string{"claude-opus-5-5": "Opus 5.5", "claude-haiku-4-5-20251001": "Haiku 4.5", "claude-opus-5-5[1m]": "Opus 5.5 (1M)", "gpt": "gpt"} {
		if got := ReadableModel(id); got != want {
			t.Errorf("%s：%s，要 %s", id, got, want)
		}
	}
}
