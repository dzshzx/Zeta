package conv

import (
	"errors"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/helper/agystore"
	"github.com/dzshzx/Zeta/helper/transcript"
)

func (c *Conversation) canEditAgyLaunch() error {
	if !c.isAgy() {
		return errors.New("仅 Antigravity 对话支持该启动项")
	}
	if c.proc != nil || c.session != "" || len(c.r.Items) > 0 {
		return errors.New("对话已经开始，不能再修改启动项")
	}
	return nil
}

func (c *Conversation) chooseAgent(name string) error {
	if err := c.canEditAgyLaunch(); err != nil {
		return err
	}
	c.agent = strings.TrimSpace(name)
	c.changed()
	return nil
}

func (c *Conversation) chooseExtraDirs(dirs []string) error {
	if err := c.canEditAgyLaunch(); err != nil {
		return err
	}
	var clean []string
	for _, raw := range dirs {
		d := strings.TrimSpace(raw)
		if d != "" && !slices.Contains(clean, d) {
			clean = append(clean, d)
		}
	}
	c.extraDirs = clean
	c.changed()
	return nil
}

func (c *Conversation) chooseSandbox(on bool) error {
	if err := c.canEditAgyLaunch(); err != nil {
		return err
	}
	c.sandbox = on
	c.changed()
	return nil
}

func (c *Conversation) listArtifacts() []agystore.ArtifactInfo {
	if !c.isAgy() || c.session == "" || c.deps.Artifacts == nil {
		return []agystore.ArtifactInfo{}
	}
	list := c.deps.Artifacts(c.session)
	if list == nil {
		return []agystore.ArtifactInfo{}
	}
	return list
}

func (c *Conversation) readArtifact(name string) (agystore.ArtifactContent, error) {
	if !c.isAgy() || c.session == "" || c.deps.ReadArtifact == nil {
		return agystore.ArtifactContent{}, errors.New("当前对话没有可读取的产物")
	}
	return c.deps.ReadArtifact(c.session, name)
}

func swarmOf(items []*transcript.Item) *agystore.SwarmState {
	var st *agystore.SwarmState
	for _, it := range items {
		if it == nil || it.Kind != transcript.KindWork {
			continue
		}
		for _, step := range it.Steps {
			if step == nil || step.Kind != transcript.StepTool || step.Name != "swarm" {
				continue
			}
			st = agystore.ApplySwarmCall(st, step.Input, step.Result)
		}
	}
	return agystore.CloneSwarm(st)
}
