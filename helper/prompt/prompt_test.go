package prompt

import (
	"encoding/json"
	"reflect"
	"testing"
)

// 请求和应答的样子照 Claude Code 2.1.330 实测，用例和 Kotlin 版的 PromptsTest 一一对应，按值相同。

const bashLine = `{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"touch a"},
	"decision_reason":"需要写文件","agent_id":"a7","tool_use_id":"t1",
	"permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"touch a"}],"behavior":"allow","destination":"localSettings"},
	{"type":"addDirectories","directories":["/w/proj"],"destination":"session"},{"type":"setMode","mode":"acceptEdits","destination":"session"}]}}`

const bashSuggestions = `[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"touch a"}],"behavior":"allow","destination":"localSettings"},
	{"type":"addDirectories","directories":["/w/proj"],"destination":"session"},{"type":"setMode","mode":"acceptEdits","destination":"session"}]`

const planLine = `{"type":"control_request","request_id":"p","request":{"subtype":"can_use_tool","tool_name":"ExitPlanMode",
	"input":{"plan":"# 计划","planFilePath":"/h/.claude/plans/x.md"}}}`

const askLine = `{"type":"control_request","request_id":"q","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","input":{"questions":[
	{"question":"用哪个库？","header":"库","multiSelect":false,"options":[{"label":"A","description":"a","preview":"import a"},{"label":"B","description":"b"}]},
	{"question":"要哪些？","header":"功能","multiSelect":true,"options":[{"label":"X","description":""},{"label":"Y","description":""}]}]}}}`

const askQuestions = `[
	{"question":"用哪个库？","header":"库","multiSelect":false,"options":[{"label":"A","description":"a","preview":"import a"},{"label":"B","description":"b"}]},
	{"question":"要哪些？","header":"功能","multiSelect":true,"options":[{"label":"X","description":""},{"label":"Y","description":""}]}]`

const formLine = `{"type":"control_request","request_id":"e","request":{"subtype":"elicitation","mcp_server_name":"eli","message":"请填写","mode":"form",
	"property_order":["count","name","prod","region","tags"],"requested_schema":{"type":"object","required":["name"],"properties":{
	"count":{"type":"integer","title":"实例数","minimum":1,"maximum":5,"default":2},
	"name":{"type":"string","title":"名字","minLength":2},
	"prod":{"type":"boolean","title":"生产环境","default":false},
	"region":{"type":"string","title":"地区","enum":["cn","us"],"enumNames":["中国","美国"]},
	"tags":{"type":"array","title":"标签","items":{"anyOf":[{"const":"a","title":"甲"},{"const":"b","title":"乙"}]}}}}}}`

const urlLine = `{"type":"control_request","request_id":"u","request":{"subtype":"elicitation","mcp_server_name":"eli","message":"去登录",
	"mode":"url","url":"https://example.com/a","elicitation_id":"x"}}`

// sameJSON 按值比较两段 JSON（对象里键的先后不算）。
func sameJSON(t *testing.T, what string, got []byte, want string) {
	t.Helper()
	var g, w any
	if err := json.Unmarshal(got, &g); err != nil {
		t.Fatalf("%s：结果不是 JSON：%v\n%s", what, err, got)
	}
	if err := json.Unmarshal([]byte(want), &w); err != nil {
		t.Fatalf("%s：期望不是 JSON：%v", what, err)
	}
	if !reflect.DeepEqual(g, w) {
		t.Errorf("%s\n得到 %s\n期望 %s", what, got, want)
	}
}

func mustParse(t *testing.T, line string) *Prompt {
	t.Helper()
	p := Parse([]byte(line))
	if p == nil {
		t.Fatalf("没读出请求：%s", line)
	}
	return p
}

func marshal(t *testing.T, v any) []byte {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestParse(t *testing.T) {
	cases := []struct {
		name string
		line string
		want string // 推给客户端的 JSON；null 是不用回应
	}{
		{"工具确认", bashLine, `{"kind":"permission","request_id":"r1","tool_name":"Bash","input":{"command":"touch a"},
			"suggestions":` + bashSuggestions + `,
			"suggestion_lines":["允许 Bash(touch a)","允许访问 /w/proj（只在这个对话里）","这个对话里自动接受改动"],
			"reason":"需要写文件","agent_id":"a7","tool_use_id":"t1","notice":"Claude 想用 Bash"}`},
		{"TUI 不给不再问时这里也不给", `{"type":"control_request","request_id":"r2","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{},
			"suppress_always_allow_rule":true,"permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash"}],"behavior":"allow"}]}}`,
			`{"kind":"permission","request_id":"r2","tool_name":"Bash","input":{},"notice":"Claude 想用 Bash"}`},
		{"手机工具的 MCP 消息不是要用户回应的", `{"type":"control_request","request_id":"m","request":{"subtype":"mcp_message"}}`, `null`},
		{"不是 control_request", `{"type":"assistant","request_id":"m","request":{"subtype":"can_use_tool"}}`, `null`},
		{"不是 JSON", `{"type":`, `null`},
		{"计划审批", planLine, `{"kind":"permission","request_id":"p","tool_name":"ExitPlanMode",
			"input":{"plan":"# 计划","planFilePath":"/h/.claude/plans/x.md"},"is_plan":true,"notice":"Claude 交来了计划，等你审批"}`},
		{"提问", askLine, `{"kind":"permission","request_id":"q","tool_name":"AskUserQuestion","input":{"questions":` + askQuestions + `},
			"questions":[
				{"question":"用哪个库？","header":"库","options":[{"label":"A","description":"a","preview":"import a"},{"label":"B","description":"b"}],"takes_notes":true},
				{"question":"要哪些？","header":"功能","options":[{"label":"X","description":""},{"label":"Y","description":""}],"multi_select":true}],
			"notice":"Claude 有问题要问你","asks_input":true}`},
		{"多选题有预览也不收备注", `{"type":"control_request","request_id":"q2","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion",
			"input":{"questions":[{"question":"哪些","header":"h","multiSelect":true,"options":[{"label":"X","description":"","preview":"x"}]},"不是对象"]}}}`,
			`{"kind":"permission","request_id":"q2","tool_name":"AskUserQuestion","input":{"questions":[{"question":"哪些","header":"h","multiSelect":true,"options":[{"label":"X","description":"","preview":"x"}]},"不是对象"]},
			"questions":[{"question":"哪些","header":"h","options":[{"label":"X","description":"","preview":"x"}],"multi_select":true}],
			"notice":"Claude 有问题要问你","asks_input":true}`},
		{"没有参数时是空对象，没有建议时不给不再问", `{"type":"control_request","request_id":"x","request":{"subtype":"can_use_tool","tool_name":"Read"}}`,
			`{"kind":"permission","request_id":"x","tool_name":"Read","input":{},"notice":"Claude 想用 Read"}`},
		{"decision_reason 写成对象", `{"type":"control_request","request_id":"o","request":{"subtype":"can_use_tool","tool_name":"Edit","input":{},
			"decision_reason":{"type":"rule","reason":"规则要求确认"}}}`,
			`{"kind":"permission","request_id":"o","tool_name":"Edit","input":{},"reason":"规则要求确认","notice":"Claude 想用 Edit"}`},
		{"decision_reason 只有空白时当作没有", `{"type":"control_request","request_id":"b","request":{"subtype":"can_use_tool","tool_name":"Edit","input":{},
			"decision_reason":"  "}}`,
			`{"kind":"permission","request_id":"b","tool_name":"Edit","input":{},"notice":"Claude 想用 Edit"}`},
		{"字段类型不对只丢那一项", `{"type":"control_request","request_id":"w","request":{"subtype":"can_use_tool","tool_name":"Bash","input":[1],
			"agent_id":5,"tool_use_id":"t9"}}`,
			`{"kind":"permission","request_id":"w","tool_name":"Bash","input":{},"tool_use_id":"t9","notice":"Claude 想用 Bash"}`},
		{"MCP 表单", formLine, `{"kind":"elicitation","request_id":"e","server":"eli","message":"请填写","fields":[
			{"key":"count","title":"实例数","kind":"integer","default":["2"],"min":1,"max":5},
			{"key":"name","title":"名字","required":true,"kind":"text","min_length":2},
			{"key":"prod","title":"生产环境","kind":"toggle","default":["false"]},
			{"key":"region","title":"地区","kind":"single","choices":[{"value":"cn","label":"中国"},{"value":"us","label":"美国"}]},
			{"key":"tags","title":"标签","kind":"multi","choices":[{"value":"a","label":"甲"},{"value":"b","label":"乙"}]}],
			"notice":"MCP 服务器 eli 需要你填写信息","asks_input":true}`},
		{"MCP 网址", urlLine, `{"kind":"elicitation","request_id":"u","server":"eli","message":"去登录","url":"https://example.com/a",
			"notice":"MCP 服务器 eli 需要你去网页上操作","asks_input":true}`},
		{"没有 property_order 时按 properties 里的先后，display_name 优先", `{"type":"control_request","request_id":"o2","request":{"subtype":"elicitation",
			"mcp_server_name":"eli","display_name":"表单","requested_schema":{"properties":{
			"z":{"type":"number","description":"说明","minItems":0,"format":"email"},"a":{"oneOf":[{"const":1},{"const":"y","title":""}]},"skip":3}}}}`,
			`{"kind":"elicitation","request_id":"o2","server":"表单","fields":[
			{"key":"z","title":"z","description":"说明","kind":"number","min":0,"format":"email"},
			{"key":"a","title":"a","kind":"single","choices":[{"value":"1","label":"1"},{"value":"y","label":""}]}],
			"notice":"MCP 服务器 表单 需要你填写信息","asks_input":true}`},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			sameJSON(t, c.name, marshal(t, Parse([]byte(c.line))), c.want)
		})
	}
}

func TestDescribeSuggestions(t *testing.T) {
	cases := []struct {
		name string
		list string
		want []string
	}{
		{"三种都认", bashSuggestions, []string{"允许 Bash(touch a)", "允许访问 /w/proj（只在这个对话里）", "这个对话里自动接受改动"}},
		{"规则的去向和行为", `[{"type":"addRules","rules":[{"toolName":"Read"},{"ruleContent":"没有工具名的跳过"},{"toolName":"Bash","ruleContent":"ls"}],
			"behavior":"deny","destination":"userSettings"},{"type":"addRules","rules":[{"toolName":"Edit"}],"behavior":"ask","destination":"projectSettings"}]`,
			[]string{"一律拒绝 Read（所有项目）", "一律拒绝 Bash(ls)（所有项目）", "每次都问 Edit（这个项目，进 Git）"}},
		{"模式与认不出的类型", `[{"type":"setMode","mode":"plan"},{"type":"setMode","mode":"dontAsk"},{"type":"replaceRules"},"不是对象"]`,
			[]string{"这个对话切到计划模式", "这个对话切到 dontAsk 模式", "replaceRules"}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			var list []json.RawMessage
			if err := json.Unmarshal([]byte(c.list), &list); err != nil {
				t.Fatal(err)
			}
			if got := describeSuggestions(list); !reflect.DeepEqual(got, c.want) {
				t.Errorf("得到 %q，期望 %q", got, c.want)
			}
		})
	}
}

func TestRespond(t *testing.T) {
	clarify := RejectWithFeedback + "The user wants to clarify these questions.\n" +
		"    This means they may have additional information, context or questions for you.\n" +
		"    Take their response into account and then reformulate the questions if appropriate.\n" +
		"    Start by asking them what they would like to clarify.\n" +
		"    Questions asked:\n" +
		"- \"用哪个库？\"\n  Answer: B\n  User notes: 拿不准\n- \"要哪些？\"\n  (No answer provided)"
	cases := []struct {
		name  string
		line  string
		reply string
		want  string
		stops bool
	}{
		{"允许这一次", bashLine, `{"kind":"allow"}`, `{"behavior":"allow","updatedInput":{"command":"touch a"}}`, false},
		{"不再问：原样交回 Claude Code 的建议", bashLine, `{"kind":"allow_always"}`,
			`{"behavior":"allow","updatedInput":{"command":"touch a"},"updatedPermissions":` + bashSuggestions + `}`, false},
		{"没有建议时不再问也不带", planLine, `{"kind":"allow_always"}`,
			`{"behavior":"allow","updatedInput":{"plan":"# 计划","planFilePath":"/h/.claude/plans/x.md"}}`, false},
		{"不写理由：带 interrupt，这一轮停下", bashLine, `{"kind":"deny","feedback":"  "}`,
			`{"behavior":"deny","message":"User rejected tool use","interrupt":true}`, true},
		{"不带 feedback 也是停下", bashLine, `{"kind":"deny"}`,
			`{"behavior":"deny","message":"User rejected tool use","interrupt":true}`, true},
		{"写了理由：TUI 的那句开头加上用户的话，不打断", bashLine, `{"kind":"deny","feedback":" 用 ls "}`,
			`{"behavior":"deny","message":` + string(marshalString(RejectWithFeedback+"用 ls")) + `}`, false},
		{"批准计划并设好这个对话的模式", planLine, `{"kind":"approve_plan","mode":"default"}`,
			`{"behavior":"allow","updatedInput":{"plan":"# 计划","planFilePath":"/h/.claude/plans/x.md"},
			"updatedPermissions":[{"type":"setMode","mode":"default","destination":"session"}]}`, false},
		{"回答带上预览和备注，多选题的备注不交", askLine,
			`{"kind":"answer","answers":{"用哪个库？":"A","要哪些？":"X, 自己写的"},"notes":{"用哪个库？":" 要新版 ","要哪些？":"多选题的备注不交"}}`,
			`{"behavior":"allow","updatedInput":{"questions":` + askQuestions + `,
			"answers":{"用哪个库？":"A","要哪些？":"X, 自己写的"},
			"annotations":{"用哪个库？":{"preview":"import a","notes":"要新版"}}}}`, false},
		{"选的那项没有预览时只带备注", askLine, `{"kind":"answer","answers":{"用哪个库？":"B"},"notes":{"用哪个库？":"先看看"}}`,
			`{"behavior":"allow","updatedInput":{"questions":` + askQuestions + `,"answers":{"用哪个库？":"B"},
			"annotations":{"用哪个库？":{"notes":"先看看"}}}}`, false},
		{"选的那项没有预览、没写备注时没有 annotations", askLine, `{"kind":"answer","answers":{"用哪个库？":"B"}}`,
			`{"behavior":"allow","updatedInput":{"questions":` + askQuestions + `,"answers":{"用哪个库？":"B"}}}`, false},
		{"想聊聊：拒绝并交给模型 TUI 的说明，不打断", askLine,
			`{"kind":"clarify","answers":{"用哪个库？":"B"},"notes":{"用哪个库？":"拿不准"}}`,
			`{"behavior":"deny","message":` + string(marshalString(clarify)) + `}`, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			var reply Reply
			if err := json.Unmarshal([]byte(c.reply), &reply); err != nil {
				t.Fatal(err)
			}
			resp, err := mustParse(t, c.line).Respond(reply)
			if err != nil {
				t.Fatal(err)
			}
			sameJSON(t, c.name, marshal(t, resp), c.want)
			if reply.StopsTurn() != c.stops {
				t.Errorf("StopsTurn() = %v，期望 %v", reply.StopsTurn(), c.stops)
			}
		})
	}
}

func marshalString(s string) []byte {
	b, _ := json.Marshal(s)
	return b
}

func TestRespondRejects(t *testing.T) {
	cases := []struct {
		name  string
		line  string
		reply Reply
	}{
		{"认不出的回应", bashLine, Reply{Kind: "maybe"}},
		{"批准计划没带模式", planLine, Reply{Kind: ReplyApprovePlan}},
		{"MCP 表单不能当工具确认回应", formLine, Reply{Kind: ReplyAllow}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if _, err := mustParse(t, c.line).Respond(c.reply); err == nil {
				t.Error("应该出错")
			}
		})
	}
}

// 回答时 answers 换掉参数里原有的那一项、留在原位，其余各项的先后照旧。
func TestAnswerKeepsInputOrder(t *testing.T) {
	p := mustParse(t, `{"type":"control_request","request_id":"q","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion",
		"input":{"z":1,"answers":{"旧":"x"},"questions":[],"a":2}}}`)
	resp, err := p.Respond(Reply{Kind: ReplyAnswer, Answers: map[string]string{"新": "y"}})
	if err != nil {
		t.Fatal(err)
	}
	want := `{"z":1,"answers":{"新":"y"},"questions":[],"a":2}`
	if string(resp.UpdatedInput) != want {
		t.Errorf("得到 %s，期望 %s", resp.UpdatedInput, want)
	}
}

func TestContent(t *testing.T) {
	fields := mustParse(t, formLine).Fields
	cases := []struct {
		name     string
		values   map[string][]string
		want     string // 空的是有错误、不交
		problems map[string]string
	}{
		{"按类型交回去", map[string][]string{"count": {"3"}, "name": {" svc "}, "prod": {"true"}, "region": {"cn"}, "tags": {"a", "b"}},
			`{"count":3,"name":"svc","prod":true,"region":"cn","tags":["a","b"]}`, map[string]string{}},
		{"超出范围、太短", map[string][]string{"count": {"9"}, "name": {"x"}}, "",
			map[string]string{"count": "不能大于 5", "name": "至少 2 个字"}},
		{"必填的没填", map[string][]string{}, "", map[string]string{"name": "必填"}},
		{"整数不收小数", map[string][]string{"name": {"ok"}, "count": {"1.5"}}, "", map[string]string{"count": "要填整数"}},
		{"没填的可选项不交，开关没动就是关", map[string][]string{"name": {"ok"}}, `{"name":"ok","prod":false}`, map[string]string{}},
		{"小于下限", map[string][]string{"name": {"ok"}, "count": {"0"}}, "", map[string]string{"count": "不能小于 1"}},
		{"整数可以写成 2.0", map[string][]string{"name": {"ok"}, "count": {"2.0"}}, `{"count":2,"name":"ok","prod":false}`, map[string]string{}},
		{"不是数字", map[string][]string{"name": {"ok"}, "count": {"NaN"}}, "", map[string]string{"count": "要填整数"}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			content, problems := Content(fields, c.values)
			if !reflect.DeepEqual(problems, c.problems) {
				t.Errorf("错误 %v，期望 %v", problems, c.problems)
			}
			if c.want == "" {
				if content != nil {
					t.Errorf("有错误时不该交：%s", content)
				}
				return
			}
			sameJSON(t, c.name, content, c.want)
		})
	}
}

func TestContentChecks(t *testing.T) {
	ptr := func(f float64) *float64 { return &f }
	length := 3
	cases := []struct {
		name  string
		field Field
		raw   []string
		want  string
		error string
	}{
		{"数字照原值交", Field{Key: "n", Kind: FieldNumber}, []string{"1.5"}, `1.5`, ""},
		{"数字不收别的字", Field{Key: "n", Kind: FieldNumber}, []string{"abc"}, ``, "要填数字"},
		{"数字的下限带小数", Field{Key: "n", Kind: FieldNumber, Min: ptr(0.5)}, []string{"0.25"}, `0.25`, "不能小于 0.5"},
		{"多选至少几项", Field{Key: "m", Kind: FieldMulti, Min: ptr(2)}, []string{"a"}, `["a"]`, "至少选 2 项"},
		{"多选最多几项", Field{Key: "m", Kind: FieldMulti, Max: ptr(1)}, []string{"a", "b"}, `["a","b"]`, "最多选 1 项"},
		{"必填的多选没选", Field{Key: "m", Kind: FieldMulti, Required: true}, nil, ``, "必填"},
		{"字数按字算", Field{Key: "t", Kind: FieldText, MaxLength: &length}, []string{"一二三四"}, `"一二三四"`, "最多 3 个字"},
		{"三个汉字不超三个字", Field{Key: "t", Kind: FieldText, MaxLength: &length}, []string{"一二三"}, `"一二三"`, ""},
		{"单选交选中的值", Field{Key: "s", Kind: FieldSingle}, []string{" cn "}, `"cn"`, ""},
		{"开关只认 true", Field{Key: "b", Kind: FieldToggle}, []string{"True"}, `false`, ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			value, problem := c.field.value(c.raw)
			if problem != c.error {
				t.Errorf("错误 %q，期望 %q", problem, c.error)
			}
			if string(value) != c.want {
				t.Errorf("值 %s，期望 %s", value, c.want)
			}
		})
	}
}

func TestElicit(t *testing.T) {
	form := mustParse(t, formLine)
	url := mustParse(t, urlLine)
	cases := []struct {
		name     string
		prompt   *Prompt
		action   ElicitAction
		values   map[string][]string
		want     string // 空的是不应答
		problems map[string]string
	}{
		{"拒绝", form, ElicitDecline, nil, `{"action":"decline"}`, nil},
		{"取消", form, ElicitCancel, nil, `{"action":"cancel"}`, nil},
		{"提交表单", form, ElicitAccept, map[string][]string{"name": {"svc"}}, `{"action":"accept","content":{"name":"svc","prod":false}}`, nil},
		{"表单没填对时不应答", form, ElicitAccept, nil, "", map[string]string{"name": "必填"}},
		{"网址模式接受不带内容", url, ElicitAccept, nil, `{"action":"accept"}`, nil},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			resp, problems, err := c.prompt.Elicit(c.action, c.values)
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(problems, c.problems) {
				t.Errorf("错误 %v，期望 %v", problems, c.problems)
			}
			if c.want == "" {
				if resp.Action != "" {
					t.Errorf("有错误时不该应答：%+v", resp)
				}
				return
			}
			sameJSON(t, c.name, marshal(t, resp), c.want)
		})
	}
	if _, _, err := form.Elicit("later", nil); err == nil {
		t.Error("认不出的回应应该出错")
	}
	if _, _, err := mustParse(t, bashLine).Elicit(ElicitAccept, nil); err == nil {
		t.Error("工具确认不能当 MCP 表单回应")
	}
}

// 要用户回答还是只确认：提问和 MCP 表单是回答。
func TestAsksInput(t *testing.T) {
	for line, want := range map[string]bool{bashLine: false, planLine: false, askLine: true, formLine: true, urlLine: true} {
		p := mustParse(t, line)
		if p.AsksInput != want {
			t.Errorf("%s 的 AsksInput = %v，期望 %v", p.RequestID, p.AsksInput, want)
		}
	}
}

func TestRejectWithFeedbackText(t *testing.T) {
	want := "The user doesn't want to proceed with this tool use. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). To tell you how to proceed, the user said:\n"
	if RejectWithFeedback != want {
		t.Errorf("交给模型的话和 TUI 的不一样：%q", RejectWithFeedback)
	}
}
