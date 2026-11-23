package main

import (
	"slices"
	"strings"
	"testing"
)

func TestTagCommit(t *testing.T) {
	const sha, other = "c0ffee", "decade"
	refs := "gh api repos/o/r/git/matching-refs/tags/v0.7.1"
	cases := []struct {
		name    string
		answers map[string]string
		created bool
		err     bool
	}{
		{"an absent tag is created", map[string]string{refs: "refs/tags/v0.7.10\tcommit\t" + other, "gh api repos/o/r/git/tags -f": "7a9"}, true, false},
		{"a tag on the commit is kept", map[string]string{refs: "refs/tags/v0.7.1\tcommit\t" + sha}, false, false},
		{"an annotated tag is followed to its commit", map[string]string{refs: "refs/tags/v0.7.1\ttag\t7a9", "gh api repos/o/r/git/tags/7a9": sha}, false, false},
		{"a tag on another commit fails", map[string]string{refs: "refs/tags/v0.7.1\tcommit\t" + other}, false, true},
	}
	for _, c := range cases {
		calls := fakeCommands(t, c.answers)
		err := tagCommit("o/r", "v0.7.1", sha)
		created := slices.Contains(*calls, "gh api repos/o/r/git/refs -f ref=refs/tags/v0.7.1 -f sha=7a9 --silent")
		if (err != nil) != c.err || created != c.created {
			t.Errorf("%s: err %v, created %t, calls %q", c.name, err, created, *calls)
		}
	}
}

func TestDeleteDrafts(t *testing.T) {
	calls := fakeCommands(t, map[string]string{"gh api repos/o/r/releases?": "11\tv0.7.1\n12\tv0.7.2\n13\tv0.7.1"})
	if err := deleteDrafts("o/r", "v0.7.1"); err != nil {
		t.Fatal(err)
	}
	var deleted []string
	for _, call := range *calls {
		if id, ok := strings.CutPrefix(call, "gh api -X DELETE repos/o/r/releases/"); ok {
			deleted = append(deleted, id)
		}
	}
	if strings.Join(deleted, ",") != "11,13" {
		t.Errorf("only drafts of the tag are deleted: %v", deleted)
	}
}

func TestPublishOrder(t *testing.T) {
	calls := fakeCommands(t, map[string]string{
		"gh api repos/o/r/releases?":                     "11\tv0.7.1",
		"gh api repos/o/r/git/matching-refs/tags/v0.7.1": "",
		"gh api repos/o/r/git/tags -f":                   "7a9",
	})
	if err := publishRelease("o/r", "v0.7.1", "c0ffee", []string{"/a/Zeta-v0.7.1.apk", "/a/update.json"}, "body"); err != nil {
		t.Fatal(err)
	}
	steps := []string{
		"gh api -X DELETE repos/o/r/releases/11",
		"gh release create v0.7.1 /a/Zeta-v0.7.1.apk /a/update.json --repo o/r --draft --target c0ffee --title Zeta 0.7.1 --notes body",
		"gh api repos/o/r/git/refs -f ref=refs/tags/v0.7.1 -f sha=7a9 --silent",
		"gh release edit v0.7.1 --repo o/r --verify-tag --draft=false --latest",
	}
	at := -1
	for _, step := range steps {
		i := slices.Index(*calls, step)
		if i <= at {
			t.Fatalf("%q missing or out of order in %q", step, *calls)
		}
		at = i
	}
}

func TestRunPublishChecksTheSourceCommit(t *testing.T) {
	dir, _ := writeSample(t)
	t.Setenv("GITHUB_SHA", "decade")
	t.Setenv("GITHUB_REPOSITORY", "o/r")
	calls := fakeCommands(t, nil)
	if err := runPublish([]string{"-version", "0.7.1", "-assets", dir}); err == nil || !strings.Contains(err.Error(), "不是这次的 decade") {
		t.Errorf("assets built from another commit are not published: %v", err)
	}
	if len(*calls) != 0 {
		t.Errorf("nothing reaches GitHub: %q", *calls)
	}
}

// 发版不等任何 commit status（R8 冒烟测试不在发版流程里）：资产对得上就建草稿、打 tag、发布。
func TestRunPublishDoesNotWaitForStatuses(t *testing.T) {
	dir, _ := writeSample(t)
	t.Setenv("GITHUB_SHA", "c0ffee")
	t.Setenv("GITHUB_REPOSITORY", "o/r")
	calls := fakeCommands(t, map[string]string{"gh api repos/o/r/git/tags -f": "7a9"})
	if err := runPublish([]string{"-version", "0.7.1", "-assets", dir}); err != nil {
		t.Fatal(err)
	}
	if slices.ContainsFunc(*calls, func(c string) bool { return strings.Contains(c, "/status") }) {
		t.Errorf("publish read a commit status: %q", *calls)
	}
	if !slices.ContainsFunc(*calls, func(c string) bool { return strings.HasPrefix(c, "gh release edit v0.7.1") }) {
		t.Errorf("publish did not publish: %q", *calls)
	}
}
