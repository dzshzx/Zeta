package chat

import (
	"context"
	"testing"
)

func TestHandoffRequests(t *testing.T) {
	link, _, conv, _ := opened(t)
	ctx := context.Background()

	link.results["conv.occupant"] = `{"status":"waiting","waiting_for":"Bash","hands_over":true}`
	o, err := conv.Occupant(ctx)
	if err != nil || o != (Occupant{Status: OccupantWaiting, WaitingFor: "Bash", HandsOver: true}) {
		t.Fatalf("occupant %+v %v", o, err)
	}
	link.results["conv.occupant"] = `{"status":null,"waiting_for":null,"hands_over":false}`
	if o, err := conv.Occupant(ctx); err != nil || o.Status != "" {
		t.Fatalf("free occupant %+v %v", o, err)
	}

	link.results["conv.handoff"] = `{"handed":false,"status":"stuck"}`
	h, err := conv.Handoff(ctx)
	if err != nil || h.Handed || h.Status != HandoffStuck {
		t.Fatalf("handoff %+v %v", h, err)
	}

	if err := conv.Retitle(ctx, "新名字"); err != nil {
		t.Fatal(err)
	}
	want := []call{
		{"conv.occupant", `{"conv":"w1"}`}, {"conv.occupant", `{"conv":"w1"}`},
		{"conv.handoff", `{"conv":"w1"}`}, {"conv.title", `{"conv":"w1","title":"新名字"}`},
	}
	got := link.took()
	if len(got) != len(want) {
		t.Fatalf("发出 %v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("第 %d 个发出 %v，要 %v", i, got[i], want[i])
		}
	}
}
