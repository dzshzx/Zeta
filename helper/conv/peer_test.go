package conv

import "testing"

// 历史里另一个会话发来的消息（zeta-helper history 给的 peer）进条目：用户消息带上发信会话的名字
func TestHistoryPeerItem(t *testing.T) {
	out := []byte(`{"type":"user","peer":"home-repo-e8","message":{"content":[{"type":"text","text":"README 已改正"}]}}` + "\n" +
		`{"type":"user","uuid":"u1","message":{"content":[{"type":"text","text":"a"}]}}` + "\n")
	items := readHistory(out, "/s.jsonl", "h", false).reducer.Items
	if len(items) != 2 || items[0].Peer != "home-repo-e8" || items[0].Text != "README 已改正" || items[1].Peer != "" {
		t.Fatalf("条目 %+v", items)
	}
}
