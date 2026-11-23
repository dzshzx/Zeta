package coreapi

import (
	"context"
	"encoding/json"
	"testing"
)

func TestFilesMethods(t *testing.T) {
	ctx := context.Background()
	busy, days := "npm", json.Number("30")
	runSessionsCases(t, []sessionsCase{
		{"scan", func(cl *Client) (any, error) { return cl.ScanStorage(ctx, []string{"s1"}) },
			"storage.scan", `{"keep":["s1"]}`,
			`{"kinds":[{"key":"npm","bytes":100,"files":3,"freeable":80,"items":2,"busy":"npm","parts":[{"name":"cache","bytes":80,"items":2}]},` +
				`{"key":"pip","bytes":0,"files":0,"freeable":0,"items":0,"busy":null,"parts":[],"error":true}],` +
				`"projects":[{"dir":"-home-u","bytes":50,"files":4,"sessions":2,"cwd":"/home/u"}],"cleanupPeriodDays":30}`,
			StorageReport{
				Kinds: []StorageKind{
					{Key: "npm", Bytes: 100, Files: 3, Freeable: 80, Items: 2, Busy: &busy, Parts: []StoragePart{{Name: "cache", Bytes: 80, Items: 2}}},
					{Key: "pip", Parts: []StoragePart{}, Error: true},
				},
				Projects:          []StorageProject{{Dir: "-home-u", Bytes: 50, Files: 4, Sessions: 2, Cwd: "/home/u"}},
				CleanupPeriodDays: &days,
			}},
		{"scan without keep", func(cl *Client) (any, error) { return cl.ScanStorage(ctx, nil) },
			"storage.scan", `{"keep":null}`, `{"kinds":[],"projects":[],"cleanupPeriodDays":null}`,
			StorageReport{Kinds: []StorageKind{}, Projects: []StorageProject{}}},
		{"sessions", func(cl *Client) (any, error) { return cl.StorageSessions(ctx, "-home-u", []string{"s1"}) },
			"storage.sessions", `{"keep":["s1"],"project":"-home-u"}`,
			`{"sessions":[{"id":"s1","file":"/p/s1.jsonl","bytes":40,"updated":1700000000000,"live":true}],"rest":10}`,
			ProjectUsage{Sessions: []SessionUsage{{ID: "s1", File: "/p/s1.jsonl", Bytes: 40, Updated: 1700000000000, Live: true}}, Rest: 10}},
		{"clean", func(cl *Client) (any, error) { return cl.CleanStorage(ctx, "npm", nil) },
			"storage.clean", `{"keep":null,"kind":"npm"}`, `{"key":"npm","freed":80,"removed":2,"failed":0,"busy":null}`,
			Cleaned{Key: "npm", Freed: 80, Removed: 2}},
		{"ls", func(cl *Client) (any, error) { return cl.ListDir(ctx, "/home/u") },
			"files.ls", `{"path":"/home/u"}`,
			`{"path":"/home/u","total":3,"truncated":false,"entries":[{"name":"a","dir":true,"kind":"dir","size":0,"mtime":1},` +
				`{"name":"b","dir":false,"kind":"link","size":0,"mtime":2,"target":"/x","broken":true}]}`,
			Listing{Path: "/home/u", Total: 3, Entries: []DirEntry{{Name: "a", Dir: true, Kind: "dir", Mtime: 1},
				{Name: "b", Kind: "link", Mtime: 2, Target: "/x", Broken: true}}}},
		{"read", func(cl *Client) (any, error) { return cl.ReadFile(ctx, "/home/u/a.txt", 1024) },
			"files.read", `{"bytes":1024,"path":"/home/u/a.txt"}`, `{"size":2048,"truncated":true,"text":"你好"}`,
			FilePreview{Size: 2048, Truncated: true, Text: "你好"}},
		{"read default size", func(cl *Client) (any, error) { return cl.ReadFile(ctx, "/bin/ls", 0) },
			"files.read", `{"path":"/bin/ls"}`, `{"size":9000,"binary":true}`,
			FilePreview{Size: 9000, Binary: true}},
		{"image", func(cl *Client) (any, error) { return cl.FileImage(ctx, "~/a.png") },
			"images.file", `{"path":"~/a.png"}`, `{"media_type":"image/png","data":"iVBO"}`,
			Image{MediaType: "image/png", Data: []byte{0x89, 0x50, 0x4e}}},
	})
}
