package claudecfg

import (
	"context"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

type agySettingsState struct {
	data          *coreapi.AgySettings
	working       bool
	planner       string
	executor      string
	proxyHTTP     string
	proxyHTTPS    string
	proxyNo       string
	proxyUser     string
	proxyPass     string
	customModels  string
	gcpProject    string
	gcpLocation   string
	workspacesRaw string
}

var (
	agyToolPermissions = []string{"", "request-review", "proceed-in-sandbox", "always-proceed", "strict"}
	agyArtifactPols    = []string{"", "asks-for-review", "agent-decides", "always-proceed"}
	agyQueuePolicies   = []string{"", "queue", "send-immediately"}
)

func (p *Page) openAgySettings() {
	p.agySet = agySettingsState{}
	p.reloadAgySettings()
}

func (p *Page) reloadAgySettings() {
	p.async(func(ctx context.Context) func() {
		d, err := p.opts.Core.AgySettings(ctx)
		return func() {
			if err != nil {
				p.failed(locales.ClaudecfgAgySettingsLoadFailed(), err)
				return
			}
			p.applyAgySettingsData(d)
		}
	})
}

func (p *Page) applyAgySettingsData(d coreapi.AgySettings) {
	s := &p.agySet
	s.data = &d
	s.planner = d.Orchestrator.Planner
	s.executor = d.Orchestrator.Executor
	s.proxyHTTP = d.SandboxProxy.HTTP
	s.proxyHTTPS = d.SandboxProxy.HTTPS
	s.proxyNo = d.SandboxProxy.NoProxy
	s.proxyUser = d.SandboxProxy.Username
	s.proxyPass = ""
	s.customModels = d.CustomModelsConfig
	s.gcpProject = d.GCP.Project
	s.gcpLocation = d.GCP.Location
	s.workspacesRaw = strings.Join(d.TrustedWorkspaces, "\n")
}

func (p *Page) patchAgySettings(patch coreapi.AgySettingsPatch) {
	if p.agySet.working {
		return
	}
	p.agySet.working = true
	p.async(func(ctx context.Context) func() {
		d, err := p.opts.Core.UpdateAgySettings(ctx, patch)
		return func() {
			p.agySet.working = false
			if err != nil {
				p.failed(locales.ClaudecfgChangeFailed(), err)
				return
			}
			p.applyAgySettingsData(d)
		}
	})
}

func (p *Page) agySettingsPage(c *ui.Context) {
	s := &p.agySet
	pageTitle(c, locales.ClaudecfgAgySettings(), p.back, func() {
		widget.SmallPrimaryButton(c, locales.CommonSave()).Disabled(s.data == nil || s.working).OnClick(p.saveAgySettingsFields)
	})
	body(c, "agy-settings", func() {
		if s.data == nil {
			widget.Group(c, "", "", func(l *widget.List) { loadingRow(l, locales.CommonLoading()) })
			return
		}
		p.agyPolicyGroup(c, *s.data)
		p.agyModelsAndGCPGroup(c)
		p.agyProxyGroup(c, *s.data)
		p.agyWorkspacesGroup(c)
	})
}

func (p *Page) agyPolicyGroup(c *ui.Context, d coreapi.AgySettings) {
	disabled := p.agySet.working
	widget.Group(c, locales.ClaudecfgAgyPolicyGroup(), "", func(l *widget.List) {
		item(l, locales.ClaudecfgAgyToolPerm(), "", func() {
			idx := max(0, slices.Index(agyToolPermissions, d.ToolPermission))
			labels := []string{locales.ComposerDefault(), "request-review", "proceed-in-sandbox", "always-proceed", "strict"}
			choice(c, "agy-tool-perm", locales.ClaudecfgAgyToolPerm(), labels, idx, disabled, func(i int) {
				v := agyToolPermissions[i]
				p.patchAgySettings(coreapi.AgySettingsPatch{ToolPermission: &v})
			})
		})
		item(l, locales.ClaudecfgAgySandbox(), "", func() {
			on := d.EnableTerminalSandbox
			if widget.Switch(c.Key("agy-sandbox"), &on).Disabled(disabled).Changed() {
				p.patchAgySettings(coreapi.AgySettingsPatch{EnableTerminalSandbox: &on})
			}
		})
		item(l, locales.ClaudecfgAgyNonWorkspace(), "", func() {
			on := d.AllowNonWorkspaceAccess
			if widget.Switch(c.Key("agy-non-ws"), &on).Disabled(disabled).Changed() {
				p.patchAgySettings(coreapi.AgySettingsPatch{AllowNonWorkspaceAccess: &on})
			}
		})
		item(l, locales.ClaudecfgAgyArtifactReview(), "", func() {
			idx := max(0, slices.Index(agyArtifactPols, d.ArtifactReviewPolicy))
			labels := []string{locales.ComposerDefault(), "asks-for-review", "agent-decides", "always-proceed"}
			choice(c, "agy-art-rev", locales.ClaudecfgAgyArtifactReview(), labels, idx, disabled, func(i int) {
				v := agyArtifactPols[i]
				p.patchAgySettings(coreapi.AgySettingsPatch{ArtifactReviewPolicy: &v})
			})
		})
		item(l, locales.ClaudecfgAgyUseG1Credits(), "", func() {
			on := d.UseG1Credits
			if widget.Switch(c.Key("agy-g1"), &on).Disabled(disabled).Changed() {
				p.patchAgySettings(coreapi.AgySettingsPatch{UseG1Credits: &on})
			}
		})
		item(l, locales.ClaudecfgAgyQueuedMessages(), "", func() {
			idx := max(0, slices.Index(agyQueuePolicies, d.QueuedMessages))
			labels := []string{locales.ComposerDefault(), "queue", "send-immediately"}
			choice(c, "agy-queued", locales.ClaudecfgAgyQueuedMessages(), labels, idx, disabled, func(i int) {
				v := agyQueuePolicies[i]
				p.patchAgySettings(coreapi.AgySettingsPatch{QueuedMessages: &v})
			})
		})
	})
}

func (p *Page) agyModelsAndGCPGroup(c *ui.Context) {
	s := &p.agySet
	widget.Group(c, locales.ClaudecfgAgyModelsGCPGroup(), "", func(l *widget.List) {
		field(l, "agy-planner", &s.planner, "Planner", "orchestrator.planner", s.working)
		field(l, "agy-executor", &s.executor, "Executor", "orchestrator.executor", s.working)
		field(l, "agy-custom-models", &s.customModels, locales.ClaudecfgAgyCustomModels(), "/path/to/models.json", s.working)
		field(l, "agy-gcp-project", &s.gcpProject, "GCP Project", "gcp.project", s.working)
		field(l, "agy-gcp-location", &s.gcpLocation, "GCP Location", "us-central1", s.working)
	})
}

func (p *Page) agyProxyGroup(c *ui.Context, d coreapi.AgySettings) {
	s := &p.agySet
	passPlaceholder := locales.ClaudecfgAgyProxyPassUnset()
	if d.SandboxProxy.HasPassword {
		passPlaceholder = locales.ClaudecfgAgyProxyPassSet()
	}
	widget.Group(c, locales.ClaudecfgAgyProxyGroup(), "", func(l *widget.List) {
		field(l, "agy-proxy-http", &s.proxyHTTP, "HTTP Proxy", "http://127.0.0.1:7890", s.working)
		field(l, "agy-proxy-https", &s.proxyHTTPS, "HTTPS Proxy", "http://127.0.0.1:7890", s.working)
		field(l, "agy-proxy-no", &s.proxyNo, "No Proxy", "localhost,127.0.0.1", s.working)
		field(l, "agy-proxy-user", &s.proxyUser, "Username", "username", s.working)
		field(l, "agy-proxy-pass", &s.proxyPass, "Password", passPlaceholder, s.working)
		if d.SandboxProxy.HasPassword {
			item(l, locales.ClaudecfgAgyProxyClearPass(), "", func() {
				widget.SmallButton(c, locales.CommonDelete()).Disabled(s.working).OnClick(func() {
					p.patchAgySettings(coreapi.AgySettingsPatch{
						SandboxProxy: &coreapi.AgySandboxProxyPatch{ClearPassword: true},
					})
				})
			})
		}
	})
}

func (p *Page) agyWorkspacesGroup(c *ui.Context) {
	s := &p.agySet
	widget.Group(c, locales.ClaudecfgAgyTrustedWorkspaces(), locales.ClaudecfgAgyTrustedWorkspacesFooter(), func(l *widget.List) {
		l.Custom(func() {
			area(c.Key("agy-workspaces"), &s.workspacesRaw, 3, 8).Disabled(s.working).FillWidth()
		})
	})
}

func (p *Page) saveAgySettingsFields() {
	s := &p.agySet
	var ws []string
	for line := range strings.SplitSeq(s.workspacesRaw, "\n") {
		if t := strings.TrimSpace(line); t != "" {
			ws = append(ws, t)
		}
	}
	proxy := &coreapi.AgySandboxProxyPatch{
		HTTP:     &s.proxyHTTP,
		HTTPS:    &s.proxyHTTPS,
		NoProxy:  &s.proxyNo,
		Username: &s.proxyUser,
	}
	if s.proxyPass != "" {
		pass := s.proxyPass
		proxy.Password = &pass
	}
	p.patchAgySettings(coreapi.AgySettingsPatch{
		Orchestrator:       &coreapi.AgyOrchestrator{Planner: s.planner, Executor: s.executor},
		SandboxProxy:       proxy,
		TrustedWorkspaces:  &ws,
		CustomModelsConfig: &s.customModels,
		GCP:                &coreapi.AgyGCP{Project: s.gcpProject, Location: s.gcpLocation},
	})
}
