package coreapi

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"testing"

	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// convCaller 记下发出的方法名和参数（JSON，键按字母排好），回 result。名字带 conv：同包里别的测试文件也有各自的假连接。
type convCaller struct {
	method, params string
	result         string
	err            error
}

func (f *convCaller) Call(_ context.Context, method string, p map[string]any) (json.RawMessage, error) {
	b, err := json.Marshal(p)
	if err != nil {
		return nil, err
	}
	f.method, f.params = method, string(b)
	if f.result == "" {
		return nil, f.err
	}
	return json.RawMessage(f.result), f.err
}

func TestConvRequests(t *testing.T) {
	ctx := context.Background()
	opus := "opus"
	run := []struct {
		name   string
		result string // 核心回的 result，空是没有
		do     func(v Conv) (any, error)
		method string
		params string
		want   any // 解出来的结果；只发不收的为 nil
	}{
		{"打开", "", func(v Conv) (any, error) {
			return nil, v.Open(ctx, OpenSpec{Session: "s1", Cwd: "/h/p", Options: Options{AutoTitle: true, CompactPercent: 80}})
		}, "conv.open", `{"conv":"w1","cwd":"/h/p","options":{"auto_title":true,"compact_percent":80},"session":"s1"}`, nil},
		{"按 key 重开", "", func(v Conv) (any, error) {
			return nil, v.Open(ctx, OpenSpec{Key: "c7", File: "/h/s.jsonl", Title: "修 bug", Options: Options{Env: map[string]string{"A": "1"}, SystemPrompt: "p", MCPConfig: "{}", Phone: true, Desk: true}})
		}, "conv.open", `{"conv":"w1","file":"/h/s.jsonl","key":"c7","options":{"env":{"A":"1"},"system_prompt":"p","mcp_config":"{}","phone":true,"desk":true},"title":"修 bug"}`, nil},
		{"放开", "", func(v Conv) (any, error) { return nil, v.Close(ctx) }, "conv.close", `{"conv":"w1"}`, nil},
		{"改设置", "", func(v Conv) (any, error) { return nil, v.SetOptions(ctx, Options{AutoTitle: true}) },
			"conv.options", `{"conv":"w1","options":{"auto_title":true}}`, nil},
		{"发消息", "", func(v Conv) (any, error) {
			return nil, v.Send(ctx, Outgoing{Text: "看图", Images: []Image{{MediaType: "image/png", Data: []byte("hi")}}, Files: []string{"/h/a.go"}})
		}, "conv.send", `{"conv":"w1","files":["/h/a.go"],"images":[{"media_type":"image/png","data":"aGk="}],"text":"看图"}`, nil},
		{"后台运行发消息", "", func(v Conv) (any, error) {
			return nil, v.Send(ctx, Outgoing{Text: "日报", Mode: "acceptEdits", Rules: json.RawMessage(`{"allow":["Bash"]}`)})
		}, "conv.send", `{"conv":"w1","mode":"acceptEdits","rules":{"allow":["Bash"]},"text":"日报"}`, nil},
		{"停止", "", func(v Conv) (any, error) { return nil, v.Interrupt(ctx) }, "conv.interrupt", `{"conv":"w1"}`, nil},
		{"插话", "", func(v Conv) (any, error) { return nil, v.Steer(ctx) }, "conv.steer", `{"conv":"w1"}`, nil},
		{"撤回", "", func(v Conv) (any, error) { return nil, v.Cancel(ctx, "q1", true) },
			"conv.cancel", `{"conv":"w1","recall":true,"uuid":"q1"}`, nil},
		{"取走撤回的", `{"uuid":"q1","text":"再看看","images":[{"media_type":"image/png","data":"aGk="}],"files":null}`,
			func(v Conv) (any, error) { return v.TakeRecalled(ctx) }, "conv.take_recalled", `{"conv":"w1"}`,
			&Message{UUID: "q1", Text: "再看看", Images: []Image{{MediaType: "image/png", Data: []byte("hi")}}}},
		{"没有撤回的", "null", func(v Conv) (any, error) { return v.TakeRecalled(ctx) }, "conv.take_recalled", `{"conv":"w1"}`, (*Message)(nil)},
		{"回应确认", "", func(v Conv) (any, error) {
			return nil, v.Answer(ctx, "r1", Reply{Kind: ReplyDeny, Feedback: "换个做法"})
		}, "conv.answer", `{"conv":"w1","reply":{"kind":"deny","feedback":"换个做法"},"request_id":"r1"}`, nil},
		{"回答提问", "", func(v Conv) (any, error) {
			return nil, v.Answer(ctx, "r2", Reply{Kind: ReplyAnswer, Answers: map[string]string{"用哪个？": "A"}, Notes: map[string]string{"用哪个？": "快"}})
		}, "conv.answer", `{"conv":"w1","reply":{"kind":"answer","answers":{"用哪个？":"A"},"notes":{"用哪个？":"快"}},"request_id":"r2"}`, nil},
		{"表单填错", `{"errors":{"port":"要填整数"}}`, func(v Conv) (any, error) {
			return v.Elicit(ctx, "e1", ElicitAccept, map[string][]string{"port": {"x"}})
		}, "conv.elicit", `{"action":"accept","conv":"w1","request_id":"e1","values":{"port":["x"]}}`, map[string]string{"port": "要填整数"}},
		{"表单拒绝", "", func(v Conv) (any, error) { return v.Elicit(ctx, "e1", ElicitDecline, nil) },
			"conv.elicit", `{"action":"decline","conv":"w1","request_id":"e1"}`, map[string]string(nil)},
		{"选模型", "", func(v Conv) (any, error) { return nil, v.SetModel(ctx, &opus) }, "conv.model", `{"conv":"w1","value":"opus"}`, nil},
		{"默认强度", "", func(v Conv) (any, error) { return nil, v.SetEffort(ctx, nil) }, "conv.effort", `{"conv":"w1","value":null}`, nil},
		{"默认模式", "", func(v Conv) (any, error) { return nil, v.SetMode(ctx, nil) }, "conv.mode", `{"conv":"w1","value":null}`, nil},
		{"预设", "", func(v Conv) (any, error) { return nil, v.Preset(ctx, "opus", "", "按日报的格式写") },
			"conv.preset", `{"conv":"w1","effort":"","hint":"按日报的格式写","model":"opus"}`, nil},
		{"压缩", "", func(v Conv) (any, error) { return nil, v.Compact(ctx) }, "conv.compact", `{"conv":"w1"}`, nil},
		{"起好进程", "", func(v Conv) (any, error) { return nil, v.Prepare(ctx) }, "conv.prepare", `{"conv":"w1"}`, nil},
		{"停后台任务", "", func(v Conv) (any, error) { return nil, v.StopTask(ctx, "t1") }, "conv.stop_task", `{"conv":"w1","task":"t1"}`, nil},
		{"读一次输出", `{"text":"ok\n","total_bytes":9000,"truncated":true}`, func(v Conv) (any, error) { return v.TaskOutput(ctx, "t1") },
			"conv.task_output", `{"conv":"w1","task":"t1"}`, &TaskOutput{Text: "ok\n", TotalBytes: 9000, Truncated: true}},
		{"没有进程时读输出", "null", func(v Conv) (any, error) { return v.TaskOutput(ctx, "t1") },
			"conv.task_output", `{"conv":"w1","task":"t1"}`, (*TaskOutput)(nil)},
		{"看着输出", "", func(v Conv) (any, error) { return nil, v.WatchTaskOutput(ctx, "t1", false) },
			"conv.task_output", `{"conv":"w1","task":"t1","watch":false}`, nil},
		{"结束进程", "", func(v Conv) (any, error) { return nil, v.End(ctx) }, "conv.end", `{"conv":"w1"}`, nil},
		{"别处占着", `{"status":"busy","waiting_for":null,"hands_over":false}`, func(v Conv) (any, error) { return v.Occupant(ctx) },
			"conv.occupant", `{"conv":"w1"}`, sessionproto.Occupancy{Status: convPtr("busy")}},
		{"接管", `{"handed":false,"status":"stuck"}`, func(v Conv) (any, error) { return v.Handoff(ctx) },
			"conv.handoff", `{"conv":"w1"}`, sessionproto.Handoff{Status: convPtr(sessionproto.StatusStuck)}},
		{"手机工具应答", "", func(v Conv) (any, error) { return nil, v.MCPReply(ctx, "m1", json.RawMessage(`{"content":[]}`)) },
			"conv.mcp_reply", `{"conv":"w1","request_id":"m1","response":{"content":[]}}`, nil},
		{"更早一页", "", func(v Conv) (any, error) { return nil, v.LoadEarlier(ctx, false) }, "conv.earlier", `{"conv":"w1"}`, nil},
		{"载完全部", "", func(v Conv) (any, error) { return nil, v.LoadEarlier(ctx, true) }, "conv.earlier", `{"conv":"w1","rest":true}`, nil},
		{"编辑", "", func(v Conv) (any, error) {
			return nil, v.Rewrite(ctx, "u1", Outgoing{Text: "改好的", Files: []string{"/h/a.txt"}})
		}, "conv.rewrite", `{"conv":"w1","files":["/h/a.txt"],"target":"u1","text":"改好的"}`, nil},
		{"重新生成", "", func(v Conv) (any, error) { return nil, v.Regenerate(ctx, "u1") },
			"conv.rewrite", `{"conv":"w1","resend":true,"target":"u1"}`, nil},
		{"删一轮", "", func(v Conv) (any, error) { return nil, v.Drop(ctx, "u1") }, "conv.drop", `{"conv":"w1","target":"u1"}`, nil},
		{"会改动的文件", `{"files":["/h/p/a.go"],"insertions":3,"deletions":1}`, func(v Conv) (any, error) { return v.CodeChanges(ctx, "u1") },
			"conv.code_changes", `{"conv":"w1","target":"u1"}`, &CodeChanges{Files: []string{"/h/p/a.go"}, Insertions: 3, Deletions: 1}},
		{"没有可还原的", "null", func(v Conv) (any, error) { return v.CodeChanges(ctx, "u1") },
			"conv.code_changes", `{"conv":"w1","target":"u1"}`, (*CodeChanges)(nil)},
		{"还原并删一轮", "", func(v Conv) (any, error) { return nil, v.RestoreCode(ctx, Restore{Target: "u1", Drop: true}) },
			"conv.restore_code", `{"conv":"w1","drop":true,"target":"u1"}`, nil},
		{"还原并重新生成", "", func(v Conv) (any, error) { return nil, v.RestoreCode(ctx, Restore{Target: "u1", Resend: true}) },
			"conv.restore_code", `{"conv":"w1","resend":true,"target":"u1"}`, nil},
		{"还原并编辑", "", func(v Conv) (any, error) {
			return nil, v.RestoreCode(ctx, Restore{Target: "u1", Then: &Outgoing{Text: "换个说法"}})
		}, "conv.restore_code", `{"conv":"w1","target":"u1","text":"换个说法"}`, nil},
		{"改名", "", func(v Conv) (any, error) { return nil, v.SetTitle(ctx, "新名字") }, "conv.title", `{"conv":"w1","title":"新名字"}`, nil},
		{"分支", "", func(v Conv) (any, error) { return nil, v.Fork(ctx, ForkSpec{As: "w2", Last: "b"}) },
			"conv.fork", `{"as":"w2","conv":"w1","last":"b"}`, nil},
		{"带设置分支", "", func(v Conv) (any, error) {
			return nil, v.Fork(ctx, ForkSpec{As: "w2", Last: "b", Options: &Options{Desk: true}})
		},
			"conv.fork", `{"as":"w2","conv":"w1","last":"b","options":{"desk":true}}`, nil},
		{"额度", `{"five_hour":{"utilization":12}}`, func(v Conv) (any, error) { return v.Quota(ctx) },
			"conv.quota", `{"conv":"w1"}`, json.RawMessage(`{"five_hour":{"utilization":12}}`)},
		{"看子代理", "", func(v Conv) (any, error) { return nil, v.WatchSubagent(ctx, "toolu_1", true) },
			"conv.subagent", `{"conv":"w1","tool_use_id":"toolu_1","watch":true}`, nil},
	}
	for _, r := range run {
		t.Run(r.name, func(t *testing.T) {
			f := &convCaller{result: r.result}
			v := New(f).Conv("w1")
			got, err := r.do(v)
			if err != nil {
				t.Fatal(err)
			}
			if f.method != r.method || f.params != r.params {
				t.Fatalf("发出 %s %s，要 %s %s", f.method, f.params, r.method, r.params)
			}
			if r.want != nil && !reflect.DeepEqual(got, r.want) {
				t.Fatalf("结果 %#v，要 %#v", got, r.want)
			}
		})
	}
}

func convPtr(s string) *string { return &s }

func TestConvActivityHasNoConv(t *testing.T) {
	f := &convCaller{}
	if err := New(f).WatchActivity(context.Background(), false); err != nil {
		t.Fatal(err)
	}
	if f.method != "conv.activity" || f.params != `{"watch":false}` {
		t.Fatalf("发出 %s %s", f.method, f.params)
	}
}

// 核心拒绝的原因原样交回；该有结果的没给时报 ErrNoResult。
func TestConvErrors(t *testing.T) {
	ctx := context.Background()
	f := &convCaller{err: errors.New("没有打开这个对话")}
	v := New(f).Conv("w1")
	if err := v.Compact(ctx); err == nil || err.Error() != "没有打开这个对话" {
		t.Fatalf("核心拒绝的原因没带回来：%v", err)
	}
	if _, err := v.Handoff(ctx); err == nil || err.Error() != "没有打开这个对话" {
		t.Fatalf("接管出错：%v", err)
	}
	f.err = nil
	if _, err := v.Occupant(ctx); !errors.Is(err, ErrNoResult) {
		t.Fatalf("没给结果：%v", err)
	}
}
