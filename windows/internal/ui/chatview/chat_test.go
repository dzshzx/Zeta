package chatview

import (
	"errors"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/egoist/mygo/ui"
)

// helper 的各份用例都画得出来，用户消息和回答的字都在屏上。
func TestRendersHelperFixtures(t *testing.T) {
	for name, list := range helperFixtures(t) {
		t.Run(name, func(t *testing.T) {
			ch := New()
			ch.SetItems(list)
			tt := newTester(ch, 900, 3000, false)
			settleImages(ch, tt)
			for _, it := range list {
				if (it.Kind == items.User || it.Kind == items.Answer || it.Kind == items.Notice) && it.Text != "" &&
					!tt.HasText(firstLine(it.Text, 1000)) {
					t.Errorf("%s 的 %q 不在屏上：%q", it.Kind, it.Text, tt.Texts())
				}
			}
		})
	}
}

// 工作组默认折叠成一行摘要，点开是逐步的时间线；点一步看它的参数和结果。
func TestWorkGroupExpands(t *testing.T) {
	ch := New()
	ch.SetItems(demoItems(t))
	tt := newTester(ch, 900, 3000, false)
	settleImages(ch, tt)
	if !tt.HasText("已完成 5 个步骤") {
		t.Fatalf("没有工作组的摘要：%q", tt.Texts())
	}
	if tt.HasText("跑配置包的测试") {
		t.Error("折叠着就显示了各步")
	}
	// 折叠着也露出改了哪些文件
	for _, name := range []string{"config.go", "env.go"} {
		if !tt.HasText(name) {
			t.Errorf("折叠时没露出 %s 的改动", name)
		}
	}
	mustClick(t, tt, "已完成 5 个步骤")
	for _, title := range []string{"思考已完成 · 用时 4 秒", "读取 config.go", "跑配置包的测试", "编辑 config.go", "写入 env.go", "读取 screenshot.png"} {
		if !tt.HasText(title) {
			t.Errorf("展开后没有 %q：%q", title, tt.Texts())
		}
	}
	if !tt.HasText("失败") || !tt.HasText("已完成") {
		t.Error("工具调用没有结果状态")
	}
	head, _ := tt.Find("已完成 5 个步骤")
	first, _ := tt.Find("思考已完成 · 用时 4 秒")
	last, _ := tt.Find("读取 screenshot.png")
	if !(head.Y < first.Y && first.Y < last.Y) {
		t.Errorf("时间线的顺序不对：摘要 %v，第一步 %v，最后一步 %v", head, first, last)
	}
	if tt.HasText("config_test.go:12: port = 0, want 8080") {
		t.Error("没点开就显示了结果")
	}
	mustClick(t, tt, "跑配置包的测试")
	if !tt.HasText("go test ./config/...") || !tt.HasText("config_test.go:12: port = 0, want 8080") {
		t.Errorf("点开一步没看到命令和结果：%q", tt.Texts())
	}
	// 思考点开是 Markdown
	mustClick(t, tt, "思考已完成 · 用时 4 秒")
	if !tt.HasText("config.go") || !tt.HasText("现在怎么读配置") {
		t.Errorf("点开思考没看到内容：%q", tt.Texts())
	}
	// 再点摘要收起
	mustClick(t, tt, "已完成 5 个步骤")
	if tt.HasText("跑配置包的测试") {
		t.Error("收起后各步还在")
	}
	if !*ch.open["step:t2"] {
		t.Error("收起整组后，里面那一步的展开状态丢了")
	}
}

// 在跑的一组：标题说正在处理哪一步；发起子代理的一步点了交给调用方，不在原地展开。
func TestRunningGroupAndSubagent(t *testing.T) {
	ch := New()
	var opened []string
	ch.OpenSubagent = func(id string) { opened = append(opened, id) }
	ch.SetItems(demoItems(t))
	tt := newTester(ch, 900, 3000, false)
	settleImages(ch, tt)
	title := "正在处理第 2 步 · 子代理：检查其他读取配置的地方"
	if !tt.HasText(title) {
		t.Fatalf("在跑的一组没有说明在做什么：%q", tt.Texts())
	}
	mustClick(t, tt, title)
	mustClick(t, tt, "子代理：检查其他读取配置的地方")
	if !slices.Equal(opened, []string{"t7"}) {
		t.Errorf("点子代理那一步：%q", opened)
	}
	if tt.HasText("找出所有直接读 config 文件的地方") {
		t.Error("子代理那一步在原地展开了")
	}
}

// 改文件的一步：时间线里标题带增删行数，对比默认收着；点标题展开，再点收起。
func TestEditStepShowsDiff(t *testing.T) {
	ch := New()
	ch.SetItems(demoItems(t))
	tt := newTester(ch, 900, 3000, false)
	settleImages(ch, tt)
	mustClick(t, tt, "已完成 5 个步骤")
	if !tt.HasText("编辑 config.go") || !tt.HasText("+5") || !tt.HasText("−1") {
		t.Fatalf("改文件那一步没有增删行数：%q", tt.Texts())
	}
	const line = "\treturn c.overrideFromEnv(), nil"
	if _, ok := tt.Access(line); ok {
		t.Error("对比默认该收着")
	}
	mustClick(t, tt, "编辑 config.go")
	added, ok := tt.Access(line)
	if !ok || added.Role != ui.RoleInsertion {
		t.Errorf("展开后对比里没有加上的行：%+v %v", added, ok)
	}
	mustClick(t, tt, "编辑 config.go")
	if *ch.open["edit:t3"] {
		t.Error("再点标题没收起")
	}
}

// 工作组折叠时露出的改动卡片默认只有文件名那一行，点它展开对比、再点收起。
func TestCollapsedChangeCardFolds(t *testing.T) {
	ch := New()
	ch.SetItems(manyEdits(t, 1))
	tt := newTester(ch, 900, 3000, false)
	tt.Frame()
	if !tt.HasText("file0.go") {
		t.Fatalf("卡片没有文件名那一行：%q", tt.Texts())
	}
	diffShown := func() bool {
		for _, a := range tt.AccessAll() {
			if a.Role == ui.RoleInsertion {
				return true
			}
		}
		return false
	}
	if diffShown() {
		t.Error("卡片默认该收成一行")
	}
	mustClick(t, tt, "file0.go")
	if !diffShown() {
		t.Errorf("点了文件名没展开：%q", tt.Texts())
	}
	mustClick(t, tt, "file0.go")
	if diffShown() {
		t.Error("再点没收起")
	}
}

// 折叠时最多露出三处改动，多的点一下展开整组。
func TestCollapsedChangesLimit(t *testing.T) {
	ch := New()
	ch.SetItems(manyEdits(t, 5))
	tt := newTester(ch, 900, 3000, false)
	tt.Frame()
	for k, want := range []bool{true, true, true, false, false} {
		name := "file" + string(rune('0'+k)) + ".go"
		if tt.HasText(name) != want {
			t.Errorf("%s 露出：%v，应为 %v", name, !want, want)
		}
	}
	mustClick(t, tt, "还有 2 处改动，展开查看")
	if !*ch.open["work:w"] || !tt.HasText("编辑 file4.go") {
		t.Errorf("点「还有」没展开整组：%q", tt.Texts())
	}
}

// 长改动先只露前几行，点了展开全部。
func TestLongChangeExpands(t *testing.T) {
	var content strings.Builder
	for k := range 30 {
		content.WriteString("line " + string(rune('A'+k%26)) + "\n")
	}
	list := decodeItems(t, `[{"kind":"work","id":"w","steps":[{"kind":"tool","id":"t","name":"Write","status":"success",
		"input":{"file_path":"/work/big.txt","content":`+jsonString(content.String())+`}}]}]`)
	ch := New()
	ch.SetItems(list)
	tt := newTester(ch, 900, 3000, false)
	tt.Frame()
	v := ch.changes["t"]
	if v == nil || v.total != 30 {
		t.Fatalf("对比：%+v", v)
	}
	// 第 12 行是 line L，第 13 行是 line M
	shows := func(line string) bool {
		a, ok := tt.Access(line)
		return ok && a.Role == ui.RoleInsertion
	}
	if shows("line A") {
		t.Error("卡片默认该收成一行")
	}
	mustClick(t, tt, "big.txt")
	if !shows("line L") || shows("line M") {
		t.Errorf("预览该只露前 %d 行", previewLines)
	}
	mustClick(t, tt, "展开全部改动 · 共 30 行")
	if !tt.HasText("收起") || !shows("line M") {
		t.Errorf("展开后：%q", tt.Texts())
	}
	mustClick(t, tt, "收起")
	if shows("line M") {
		t.Error("收起后还是全部")
	}
}

// 回答显现完以后有复制键，点了复制原文。
func TestCopyAnswer(t *testing.T) {
	ch := New()
	ch.SetItems(demoItems(t))
	tt := newTester(ch, 900, 3000, false)
	settleImages(ch, tt)
	mustClick(t, tt, "复制")
	if tt.Clipboard() != demoAnswer {
		t.Errorf("复制到的是 %q", tt.Clipboard())
	}
}

// 流式的回答：还没有字时是三个点，字到了逐段显示，送完有复制键；增量替换越界时报错、不改列表。
func TestStreamingSplice(t *testing.T) {
	ch := New()
	ch.SetItems([]items.Item{{Kind: items.User, ID: "u", Text: "你好"}, {Kind: items.Answer, ID: "a", Streaming: true}})
	tt := newTester(ch, 700, 600, false)
	if _, ok := tt.Access("正在回答"); !ok {
		t.Error("还没有字的回答没有显示在打字")
	}
	if err := ch.Splice(1, 1, []items.Item{{Kind: items.Answer, ID: "a", Text: "你好，有什么", Streaming: true}}); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	tt.Advance(time.Second) // 逐字显现完
	if !tt.HasText("你好，有什么") || tt.HasText("复制") {
		t.Errorf("流式中：%q", tt.Texts())
	}
	if err := ch.Splice(1, 1, []items.Item{{Kind: items.Answer, ID: "a", Text: "你好，有什么可以帮你？"}}); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	tt.Advance(time.Second)
	if !tt.HasText("你好，有什么可以帮你？") {
		t.Errorf("送完后：%q", tt.Texts())
	}
	if _, ok := tt.Access("复制"); !ok {
		t.Error("送完的回答没有复制键")
	}
	if err := ch.Splice(1, 5, nil); err == nil || len(ch.Items()) != 2 {
		t.Errorf("越界的替换：%v，列表 %d 项", err, len(ch.Items()))
	}
}

// 换掉条目时，改过的那一步按新内容重算对比；没换的留着。
func TestSpliceRecomputesChanges(t *testing.T) {
	ch := New()
	ch.SetItems(manyEdits(t, 2))
	tt := newTester(ch, 900, 1200, false)
	tt.Frame()
	kept := ch.changes["e0"]
	if kept == nil || ch.changes["e1"] == nil {
		t.Fatal("没算对比")
	}
	next := manyEdits(t, 2)[1]
	next.Steps[1].Input["new_string"] = "a := 2\nb := 3"
	if err := ch.Splice(1, 1, []items.Item{next}); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if ch.changes["e1"] == nil || ch.changes["e1"].added != 2 {
		t.Errorf("换掉后没重算：%+v", ch.changes["e1"])
	}
	if ch.changes["e0"] == kept {
		t.Error("同一组里换掉的步骤也该重算")
	}
}

// 在底部时跟着新内容走；往上翻以后来了新内容不拽回底部；ScrollToEnd 回到底部。
func TestFollowsEnd(t *testing.T) {
	ch := New()
	ch.SetItems(longConversation("a", 10))
	tt := newTester(ch, 700, 600, false)
	tt.Frame()
	if !ch.AtEnd() || !tt.HasText("第 a9 个回答。") {
		t.Fatalf("打开时不在底部：%q", tt.Texts())
	}
	add := longConversation("b", 1)
	if err := ch.Splice(len(ch.Items()), 0, add); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if !ch.AtEnd() || !tt.HasText("第 b0 个回答。") {
		t.Errorf("新内容来了没跟上：%q", tt.Texts())
	}
	tt.Scroll(350, 300, 0, -400)
	if ch.AtEnd() {
		t.Fatal("往上翻了还在底部")
	}
	if err := ch.Splice(len(ch.Items()), 0, longConversation("c", 1)); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if ch.AtEnd() || tt.HasText("第 c0 个回答。") {
		t.Error("往上翻着时被拽回了底部")
	}
	ch.ScrollToEnd()
	tt.Frame()
	if !ch.AtEnd() || !tt.HasText("第 c0 个回答。") {
		t.Errorf("ScrollToEnd 没回到底部：%q", tt.Texts())
	}
}

// 往上翻到顶附近时载入更早的一页，只载一次；接上以后正看着的那条留在原处。
func TestLoadsEarlierOnScrollUp(t *testing.T) {
	ch := New()
	ch.SetItems(longConversation("n", 12))
	ch.Earlier = Earlier{Skipped: 40}
	loads := 0
	ch.LoadEarlier = func() {
		loads++
		ch.Earlier.Loading = true
	}
	tt := newTester(ch, 700, 600, false)
	tt.Frame()
	if loads != 0 {
		t.Fatal("没往上翻就载入了")
	}
	for range 40 {
		if loads > 0 {
			break
		}
		tt.Scroll(350, 300, 0, -300)
	}
	if loads != 1 {
		t.Fatalf("往上翻到顶载入了 %d 次", loads)
	}
	// 载入中接着往上翻到最顶上，不再载入
	for range 5 {
		tt.Scroll(350, 300, 0, -300)
	}
	if loads != 1 {
		t.Fatalf("载入中又载入了：%d 次", loads)
	}
	if !tt.HasText("正在载入更早的记录") {
		t.Errorf("载入中没有转圈：%q", tt.Texts())
	}
	anchor := "第 n0 个问题"
	before, ok := tt.Find(anchor)
	if !ok {
		t.Fatalf("顶上看不到第一条：%q", tt.Texts())
	}
	bar, _ := tt.Find("正在载入更早的记录")
	// 接上更早的一页：插在前面，正看着的那条留在原处，只让出「更早的记录」那一条的位置（它跟着最早的一条走了）
	if err := ch.Splice(0, 0, longConversation("m", 5)); err != nil {
		t.Fatal(err)
	}
	ch.Earlier = Earlier{Skipped: 30}
	tt.Frame()
	after, ok := tt.Find(anchor)
	if shift := before.Y - after.Y; !ok || shift < 0 || shift > before.Y-bar.Y {
		t.Errorf("接上一页后正看着的那条从 %v 跑到了 %v（找到 %v，那一条在 %v）", before, after, ok, bar)
	}
	if tt.HasText("第 m4 个回答。") && !tt.HasText(anchor) {
		t.Error("接上的一页把正看着的推走了")
	}
	tt.Frame()
	if loads != 1 {
		t.Errorf("接上一页后没再翻就又载入了：%d 次", loads)
	}
}

// 内容不满一屏、翻不动时点那一行载入；没读到时说明原因，能重试的点了重试。
func TestEarlierRowClick(t *testing.T) {
	ch := New()
	ch.SetItems(longConversation("x", 1))
	ch.Earlier = Earlier{Skipped: 7}
	loads := 0
	ch.LoadEarlier = func() { loads++ }
	tt := newTester(ch, 700, 900, false)
	mustClick(t, tt, "载入更早的 7 条记录")
	if loads != 1 {
		t.Fatalf("点了载入 %d 次", loads)
	}
	ch.Earlier = Earlier{Skipped: 7, Failed: "连接断开了", Retry: true}
	tt.Frame()
	mustClick(t, tt, "连接断开了，点按重试")
	if loads != 2 {
		t.Errorf("点重试后载入 %d 次", loads)
	}
	ch.Earlier = Earlier{Skipped: 7, Failed: "会话记录不见了"}
	tt.Frame()
	if err := tt.Click("会话记录不见了"); err != nil {
		t.Fatal(err)
	}
	if loads != 2 {
		t.Error("不能重试的也重试了")
	}
	ch.Earlier = Earlier{}
	tt.Frame()
	if tt.HasText("更早") {
		t.Error("没有更早的记录了还显示那一行")
	}
}

// 实时收到的图直接画；会话记录里的经调用方给的函数取，同一张只取一次；取不到时说明。
func TestImages(t *testing.T) {
	shot := testPNG(t, 64, 48)
	var mu sync.Mutex
	var asked []string
	ch := New()
	ch.LoadImage = func(file, ref string) ([]byte, error) {
		mu.Lock()
		asked = append(asked, file+"#"+ref)
		mu.Unlock()
		if ref == "bad" {
			return nil, errors.New("记录里没有这张图")
		}
		return shot, nil
	}
	ch.SetItems(decodeItems(t, `[
		{"kind":"user","id":"u","text":"看图","images":[{"kind":"session","file":"/s.jsonl","ref":"1.0"},{"kind":"session","file":"/s.jsonl","ref":"bad"}]},
		{"kind":"user","id":"u2","text":"再看","images":[{"kind":"session","file":"/s.jsonl","ref":"1.0"}]}
	]`))
	tt := newTester(ch, 700, 900, false)
	settleImages(ch, tt)
	slices.Sort(asked)
	if !slices.Equal(asked, []string{"/s.jsonl#1.0", "/s.jsonl#bad"}) {
		t.Errorf("取图：%q", asked)
	}
	images := 0
	for _, a := range tt.AccessAll() {
		if a.Role == ui.RoleImage && a.Name == "图片" {
			images++
		}
	}
	if images != 2 {
		t.Errorf("画出 %d 张图，应为 2", images)
	}
	if _, ok := tt.Access("图片无法显示"); !ok {
		t.Error("取不到的图没有说明")
	}
}

// 没给取图的函数时，会话记录里的图显示为取不到，不出错。
func TestImageWithoutLoader(t *testing.T) {
	ch := New()
	ch.SetItems(decodeItems(t, `[{"kind":"work","id":"w","steps":[{"kind":"tool","id":"r","name":"Read","status":"success",
		"input":{"file_path":"/a.png"},"result":"","images":[{"kind":"session","file":"/s.jsonl","ref":"2.0"},{"kind":"inline","key":"k","data":"不是base64"}]}]}]`))
	tt := newTester(ch, 700, 900, false)
	mustClick(t, tt, "已完成 1 个步骤")
	mustClick(t, tt, "读取 a.png")
	settleImages(ch, tt)
	n := 0
	for _, s := range tt.Texts() {
		if s == "图片无法显示" {
			n++
		}
	}
	if n != 2 {
		t.Errorf("取不到的图说明了 %d 张：%q", n, tt.Texts())
	}
}

// 提示：出错的和普通的都显示；用户消息带的附件显示名字。
func TestNoticesAndFiles(t *testing.T) {
	ch := New()
	ch.SetItems(demoItems(t))
	tt := newTester(ch, 900, 3000, false)
	settleImages(ch, tt)
	for _, s := range []string{"已停止", "API Error: 529 服务过载，请稍后再试", "config.go", "docs"} {
		if !tt.HasText(s) {
			t.Errorf("没有 %q", s)
		}
	}
	bubble, _ := tt.Find("帮我把配置读取改成支持环境变量覆盖，顺便看看这张截图里的报错。")
	answer, _ := tt.Find("截图里的报错是端口没读到，改完以后测试通过。")
	if bubble.X+bubble.W < answer.X+answer.W-40 {
		t.Errorf("用户消息没靠右：气泡 %v，回答 %v", bubble, answer)
	}
}

// 回答里的链接交给调用方打开；Markdown 里的图经调用方给的函数取，相对路径按工作目录解析。
func TestAnswerLinksAndImages(t *testing.T) {
	shot := testPNG(t, 120, 80)
	var opened []string
	asked := make(chan string, 4)
	ch := New()
	ch.Cwd = "/w"
	ch.OpenLink = func(url string) { opened = append(opened, url) }
	ch.LoadMarkdownImage = func(url string) ([]byte, error) {
		asked <- url
		return shot, nil
	}
	ch.SetItems([]items.Item{{Kind: items.Answer, ID: "a", Text: "看 [文档](https://example.com/doc)。\n\n![架构图](diagram.png)"}})
	tt := newTester(ch, 700, 600, false)
	mustClick(t, tt, "文档")
	if !slices.Equal(opened, []string{"https://example.com/doc"}) {
		t.Errorf("点链接：%q", opened)
	}
	select {
	case url := <-asked:
		if url != "/w/diagram.png" {
			t.Errorf("取图：%q", url)
		}
	case <-time.After(10 * time.Second):
		t.Error("没有取 Markdown 里的图")
	}
}

// 用户消息的气泡最宽是聊天区内容宽度的 bubbleWidthPercent：窗口宽时跟着宽（不再固定 316），窄时不伸出去；
// 带的几张缩略图排不下时折行，也不伸出去。
func TestBubbleFollowsWidth(t *testing.T) {
	text := strings.Repeat("把这几张图里的数据整理成一张表格，", 8)
	for _, width := range []int{1400, 700, 300} {
		ch := New()
		ch.SetItems(decodeItems(t, `[
			{"kind":"user","id":"u","text":`+jsonString(text)+`,"images":[{"kind":"inline","key":"1","data":""},{"kind":"inline","key":"2","data":""},{"kind":"inline","key":"3","data":""},{"kind":"inline","key":"4","data":""}]},
			{"kind":"answer","id":"a","text":"好的。"}
		]`))
		tt := newTester(ch, width, 1200, false)
		settleImages(ch, tt)
		bubble, ok := tt.Find(text)
		if !ok {
			t.Fatalf("%d 宽：没有气泡", width)
		}
		column := min(float32(width)-2*24, contentWidth) // 列表左右各留 24（View）
		if bubble.W > column*bubbleWidthPercent/100+0.5 || bubble.X+bubble.W > float32(width) {
			t.Errorf("%d 宽：气泡 %v 超出内容宽度 %v 的 %d%%", width, bubble, column, bubbleWidthPercent)
		}
		if width >= 700 && bubble.W < column*bubbleWidthPercent/100-40 {
			t.Errorf("%d 宽：气泡 %v 没跟着窗口变宽（内容宽度 %v）", width, bubble, column)
		}
		for _, a := range tt.AccessAll() {
			if a.Name == "图片无法显示" && a.Bounds.X < 0 {
				t.Errorf("%d 宽：缩略图伸出窗口 %v", width, a.Bounds)
			}
		}
	}
}

// 中间说明（带文字的思考）不折进工作组：工作组收着时也直接看得到，排在它前后的位置；没有复制键。
func TestThoughtShowsInline(t *testing.T) {
	list := decodeItems(t, `[
		{"kind":"user","id":"u1","text":"看看构建"},
		{"kind":"thought","id":"m1:0","text":"先看一下构建脚本在哪"},
		{"kind":"work","id":"w:t1","steps":[{"kind":"tool","id":"t1","name":"Bash","status":"success","input":{"command":"ls"},"result":"build.sh"}]},
		{"kind":"thought","id":"m2:0","text":"找到了，接下来跑一遍"},
		{"kind":"answer","id":"m3:0","text":"构建通过了"}]`)
	ch := New()
	ch.SetItems(list)
	tt := newTester(ch, 900, 2000, false)
	tt.Frame()
	tt.Frame()
	for _, s := range []string{"先看一下构建脚本在哪", "找到了，接下来跑一遍", "构建通过了"} {
		if !tt.HasText(s) {
			t.Errorf("没有直接显示 %q：%q", s, tt.Texts())
		}
	}
	n := 0
	for _, a := range tt.AccessAll() {
		if a.Name == "复制" && a.Role == ui.RoleButton {
			n++
		}
	}
	if n != 1 {
		t.Errorf("复制键 %d 个，只该在回答下面有一个", n)
	}
}
