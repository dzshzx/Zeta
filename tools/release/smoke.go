package main

import (
	"fmt"
	"time"
)

// R8 冒烟测试的结果记成提交上的 commit status：ship 在本机跑测试、写结果，publish 只发它成功了的提交。
// 合入时的门禁不经 R8，R8 误删运行时才按类名找的类只有这个测试查得到（docs/release.md「发版」）。
const smokeContext = "zeta/r8-smoke"

var (
	sleep        = time.Sleep
	smokeWait    = 20 * time.Minute
	smokeRecheck = 15 * time.Second
)

// smokeState 是这个提交上冒烟测试的结果：success、failure、pending，还没有时为空。
func smokeState(repo, sha string) (string, error) {
	return gh("api", "repos/"+repo+"/commits/"+sha+"/status",
		"--jq", `.statuses[] | select(.context == "`+smokeContext+`") | .state`)
}

func setSmoke(repo, sha, state, description string) error {
	_, err := gh("api", "repos/"+repo+"/statuses/"+sha, "-f", "state="+state, "-f", "context="+smokeContext,
		"-f", "description="+description, "--silent")
	return err
}

// waitSmoke 等这个提交的冒烟测试通过：ship 和 CI 同时开跑，CI 跳过门禁时可能比本机的测试先到发布这一步。
// 失败了、或等了 smokeWait 还没有结果（不是经 ship 发的版）就报错，不发布。
func waitSmoke(repo, sha string) error {
	deadline := time.Now().Add(smokeWait)
	for {
		state, err := smokeState(repo, sha)
		if err != nil {
			return err
		}
		switch state {
		case "success":
			return nil
		case "failure", "error":
			return fmt.Errorf("这个提交的 R8 冒烟测试没过（commit status %s），不发布", smokeContext)
		}
		if time.Now().After(deadline) {
			if state == "" {
				return fmt.Errorf("这个提交上没有 R8 冒烟测试的结果（commit status %s）：发版用主 checkout 上的 tools/release ship，它在本机跑这个测试", smokeContext)
			}
			return fmt.Errorf("等了 %v，R8 冒烟测试还没出结果", smokeWait)
		}
		sleep(smokeRecheck)
	}
}
