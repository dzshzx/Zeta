package main

import (
	"errors"
	"slices"
	"strings"
	"testing"
	"time"
)

// noWait 让等待的循环不真的睡，publish 等冒烟测试的上限设成 wait（0 是查一次就到截止）。
func noWait(t *testing.T, wait time.Duration) {
	t.Helper()
	savedSleep, savedWait := sleep, smokeWait
	t.Cleanup(func() { sleep, smokeWait = savedSleep, savedWait })
	sleep, smokeWait = func(time.Duration) {}, wait
}

func TestWaitSmoke(t *testing.T) {
	status := "gh api repos/o/r/commits/c0ffee/status"
	noWait(t, time.Hour)
	fakeSequence(t, map[string][]string{status: {"", "pending", "success"}})
	if err := waitSmoke("o/r", "c0ffee"); err != nil {
		t.Errorf("还没有、在跑、通过：%v", err)
	}
	fakeSequence(t, map[string][]string{status: {"pending", "failure"}})
	if err := waitSmoke("o/r", "c0ffee"); err == nil || !strings.Contains(err.Error(), "没过") {
		t.Errorf("没过的不发布：%v", err)
	}
	noWait(t, 0)
	fakeCommands(t, map[string]string{status: ""})
	if err := waitSmoke("o/r", "c0ffee"); err == nil || !strings.Contains(err.Error(), "tools/release ship") {
		t.Errorf("一直没有结果（没经 ship）不发布：%v", err)
	}
	fakeCommands(t, map[string]string{status: "pending"})
	if err := waitSmoke("o/r", "c0ffee"); err == nil || !strings.Contains(err.Error(), "还没出结果") {
		t.Errorf("等到截止还在跑不发布：%v", err)
	}
}

func TestSmokeOnce(t *testing.T) {
	saved := runSmoke
	t.Cleanup(func() { runSmoke = saved })
	ran := 0
	statuses := func(calls []string) []string {
		var out []string
		for _, c := range calls {
			if _, rest, ok := strings.Cut(c, "repos/o/r/statuses/c0ffee -f state="); ok {
				out = append(out, strings.Fields(rest)[0])
			}
		}
		return out
	}

	runSmoke = func(string) error { ran++; return nil }
	calls := fakeCommands(t, map[string]string{"gh api repos/o/r/commits/c0ffee/status": "success"})
	if err := smokeOnce("/r", "o/r", "c0ffee"); err != nil || ran != 0 || len(statuses(*calls)) != 0 {
		t.Errorf("已经通过的不再跑：err %v，跑了 %d 次，记了 %v", err, ran, statuses(*calls))
	}
	calls = fakeCommands(t, map[string]string{"gh api repos/o/r/commits/c0ffee/status": "failure"})
	if err := smokeOnce("/r", "o/r", "c0ffee"); err != nil || ran != 1 || !slices.Equal(statuses(*calls), []string{"pending", "success"}) {
		t.Errorf("上次没过的重跑，通过后记成功：err %v，跑了 %d 次，记了 %v", err, ran, statuses(*calls))
	}
	runSmoke = func(string) error { return errors.New("exit status 1") }
	calls = fakeCommands(t, map[string]string{})
	if err := smokeOnce("/r", "o/r", "c0ffee"); err == nil || !slices.Equal(statuses(*calls), []string{"pending", "failure"}) {
		t.Errorf("没过的记失败并报错：err %v，记了 %v", err, statuses(*calls))
	}
}

func TestStartReleaseFindsRun(t *testing.T) {
	noWait(t, 0)
	fakeCommands(t, map[string]string{"gh workflow run": "https://github.com/o/r/actions/runs/45099284668"})
	if id, err := startRelease("o/r", "c0ffee", "patch", "self-hosted", false); err != nil || id != "45099284668" {
		t.Errorf("从 gh 打印的地址取：%q %v", id, err)
	}
	at := time.Now().UTC().Format(time.RFC3339)
	calls := fakeSequence(t, map[string][]string{"gh run list": {"", "7\t" + at}})
	if id, err := startRelease("o/r", "c0ffee", "patch", "self-hosted", false); err != nil || id != "7" {
		t.Errorf("没打印地址时等它出现在列表里：%q %v，calls %q", id, err, *calls)
	}
}

// 全量门禁默认不跑：不传 gate，用 release.yml 的默认（false）；-gate 才传 gate=true。
func TestStartReleaseGate(t *testing.T) {
	noWait(t, 0)
	for _, gate := range []bool{false, true} {
		calls := fakeCommands(t, map[string]string{"gh workflow run": "https://github.com/o/r/actions/runs/1"})
		if _, err := startRelease("o/r", "c0ffee", "patch", "self-hosted", gate); err != nil {
			t.Fatal(err)
		}
		run := (*calls)[0]
		if got := strings.Contains(run, "-f gate="); got != gate || (gate && !strings.Contains(run, "-f gate=true")) {
			t.Errorf("gate=%t 时触发的是 %q", gate, run)
		}
	}
}

func TestNewestRun(t *testing.T) {
	since := time.Date(2026, 11, 23, 2, 0, 0, 0, time.UTC)
	lines := "1\t2026-11-23T01:59:00Z\n3\t2026-11-23T02:01:00Z\n2\t2026-11-23T02:00:30Z\nbad line"
	if got := newestRun(lines, since); got != "3" {
		t.Errorf("挑触发之后最新的：%q", got)
	}
	if got := newestRun("1\t2026-11-23T01:59:00Z", since); got != "" {
		t.Errorf("触发之前的不算：%q", got)
	}
}

func TestShipTargetNeedsCleanCheckout(t *testing.T) {
	answers := map[string]string{
		"gh repo view":                      "o/r",
		"git -C /r rev-parse origin/master": "c0ffee",
		"git -C /r rev-parse HEAD":          "c0ffee",
	}
	fakeCommands(t, answers)
	if repo, sha, err := shipTarget("/r", true); err != nil || repo != "o/r" || sha != "c0ffee" {
		t.Errorf("在 origin/master 上、没有改动：%s %s %v", repo, sha, err)
	}
	answers["git -C /r status"] = " M app/build.gradle.kts"
	fakeCommands(t, answers)
	if _, _, err := shipTarget("/r", true); err == nil {
		t.Error("有改动的不发")
	}
	if _, _, err := shipTarget("/r", false); err != nil {
		t.Errorf("-preview 不看主 checkout：%v", err)
	}
	delete(answers, "git -C /r status")
	answers["git -C /r rev-parse HEAD"] = "decade"
	fakeCommands(t, answers)
	if _, _, err := shipTarget("/r", true); err == nil {
		t.Error("不在 origin/master 上的不发")
	}
}

func TestPublishWaitsForSmoke(t *testing.T) {
	noWait(t, 0)
	dir, _ := writeSample(t)
	t.Setenv("GITHUB_SHA", "c0ffee")
	t.Setenv("GITHUB_REPOSITORY", "o/r")
	wrote := func(calls []string) bool {
		return slices.ContainsFunc(calls, func(c string) bool {
			return strings.HasPrefix(c, "gh release create") || strings.HasPrefix(c, "gh api repos/o/r/git/tags")
		})
	}
	for _, state := range []string{"failure", "pending", ""} {
		calls := fakeCommands(t, map[string]string{"gh api repos/o/r/commits/c0ffee/status": state})
		if err := runPublish([]string{"-version", "0.7.1", "-assets", dir}); err == nil || wrote(*calls) {
			t.Errorf("冒烟测试 %q 时发布了：err %v，calls %q", state, err, *calls)
		}
	}
	calls := fakeCommands(t, map[string]string{
		"gh api repos/o/r/commits/c0ffee/status": "success",
		"gh api repos/o/r/git/tags -f":           "7a9",
	})
	if err := runPublish([]string{"-version", "0.7.1", "-assets", dir}); err != nil || !wrote(*calls) {
		t.Errorf("通过后照常发布：err %v，calls %q", err, *calls)
	}
}
