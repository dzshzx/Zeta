package chat

import (
	"context"
	"reflect"
	"testing"

	"github.com/dzshzx/Zeta/helper/tasks"
)

func TestTaskOutputWatchAndPushes(t *testing.T) {
	link, client, conv, notified := opened(t)
	ctx := context.Background()
	// 没看着的任务的推送不要
	*notified = 0
	client.Handle(line(`{"type":"conv.task_output","conv":"w1","task":"t1","text":"早到的"}`))
	if _, ok := conv.TaskOutput("t1"); ok || *notified != 0 {
		t.Fatal("没看着的输出也收下了")
	}
	if err := conv.WatchTaskOutput(ctx, "t1", true); err != nil {
		t.Fatal(err)
	}
	if _, ok := conv.TaskOutput("t1"); ok {
		t.Fatal("还没推来就有输出")
	}
	client.Handle(line(`{"type":"conv.task_output","conv":"w1","task":"t1","text":"ok\n","total_bytes":9000,"truncated":true}`))
	if out, ok := conv.TaskOutput("t1"); !ok || out != (tasks.Output{Text: "ok\n", TotalBytes: 9000, Truncated: true}) || *notified != 1 {
		t.Fatalf("推来的输出 %+v %v，通知 %d 次", out, ok, *notified)
	}
	client.Handle(line(`{"type":"conv.task_output","conv":"w1","task":"t1","text":"ok\nmore\n","total_bytes":9005,"truncated":true}`))
	if out, _ := conv.TaskOutput("t1"); out.Text != "ok\nmore\n" {
		t.Fatalf("变了的输出 %+v", out)
	}

	// 断线重连后重新看上
	client.Disconnected()
	if err := client.Reconnected(ctx); err != nil {
		t.Fatal(err)
	}
	if err := conv.WatchTaskOutput(ctx, "t1", false); err != nil {
		t.Fatal(err)
	}
	var got []call
	for _, c := range link.took() {
		if c.method != "conv.open" {
			got = append(got, c)
		}
	}
	want := []call{
		{"conv.task_output", `{"conv":"w1","task":"t1","watch":true}`},
		{"conv.task_output", `{"conv":"w1","task":"t1","watch":true}`},
		{"conv.task_output", `{"conv":"w1","task":"t1","watch":false}`},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("请求：\n%v\n要的是\n%v", got, want)
	}
	if _, ok := conv.TaskOutput("t1"); ok {
		t.Fatal("不看了还留着输出")
	}
}
