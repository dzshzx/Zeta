package prompt

// schema 是 elicitation 的 requested_schema：一层对象，各项是基本类型。
type schema struct {
	Properties object   `json:"properties"`
	Required   []scalar `json:"required"`
}

// property 是 schema 里的一项。多选的可选值在 Items 里。
type property struct {
	Type        string               `json:"type"`
	Title       string               `json:"title"`
	Description string               `json:"description"`
	Enum        []scalar             `json:"enum"`
	EnumNames   []scalar             `json:"enumNames"`
	OneOf       objects[constChoice] `json:"oneOf"`
	AnyOf       objects[constChoice] `json:"anyOf"`
	Items       *property            `json:"items"`
	Default     defaults             `json:"default"`
	Minimum     *float64             `json:"minimum"`
	Maximum     *float64             `json:"maximum"`
	MinItems    *float64             `json:"minItems"`
	MaxItems    *float64             `json:"maxItems"`
	MinLength   *int                 `json:"minLength"`
	MaxLength   *int                 `json:"maxLength"`
	Format      string               `json:"format"`
}

// constChoice 是 oneOf、anyOf 里的一项：const 是值，title 是给人看的名字。
type constChoice struct {
	Const scalar  `json:"const"`
	Title *string `json:"title"`
}

// fields 按 order（property_order）排出表单的各项；order 里没有一个对得上的键时按 properties 里的先后。
func (s *schema) fields(order []string) []Field {
	var keys []string
	for _, k := range order {
		if s.Properties.get(k) != nil {
			keys = append(keys, k)
		}
	}
	if len(keys) == 0 {
		for _, m := range s.Properties {
			keys = append(keys, m.key)
		}
	}
	required := map[string]bool{}
	for _, k := range texts(s.Required) {
		required[k] = true
	}
	var fields []Field
	for _, k := range keys {
		raw := s.Properties.get(k)
		var p property
		if isObject(raw) && decode(raw, &p) {
			fields = append(fields, p.field(k, required[k]))
		}
	}
	return fields
}

func (p *property) field(key string, required bool) Field {
	choices := p.choices()
	f := Field{
		Key:         key,
		Title:       key,
		Description: p.Description,
		Required:    required,
		Kind:        p.kind(choices),
		Choices:     choices,
		Default:     p.Default,
		Min:         firstSet(p.Minimum, p.MinItems),
		Max:         firstSet(p.Maximum, p.MaxItems),
		MinLength:   p.MinLength,
		MaxLength:   p.MaxLength,
		Format:      p.Format,
	}
	if notBlank(p.Title) {
		f.Title = p.Title
	}
	if f.Kind == FieldMulti {
		f.Choices = nil
		if p.Items != nil {
			f.Choices = p.Items.choices()
		}
	}
	return f
}

// kind 按 schema 的 type 和有没有可选值定种类：数组是多选，有可选值是单选。
func (p *property) kind(choices []Choice) FieldKind {
	switch {
	case p.Type == "array":
		return FieldMulti
	case len(choices) > 0:
		return FieldSingle
	case p.Type == "boolean":
		return FieldToggle
	case p.Type == "integer":
		return FieldInteger
	case p.Type == "number":
		return FieldNumber
	default:
		return FieldText
	}
}

// choices 认两种写法：enum（可带 enumNames 作名字），或 oneOf / anyOf 里的 const 加 title。有 enum 数组时只看它。
func (p *property) choices() []Choice {
	if p.Enum != nil {
		var out []Choice
		for i, v := range p.Enum {
			if !v.OK {
				continue
			}
			label := v.Text
			if i < len(p.EnumNames) && p.EnumNames[i].OK {
				label = p.EnumNames[i].Text
			}
			out = append(out, Choice{Value: v.Text, Label: label})
		}
		return out
	}
	list := p.OneOf
	if list == nil {
		list = p.AnyOf
	}
	var out []Choice
	for _, c := range list {
		if !c.Const.OK {
			continue
		}
		label := c.Const.Text
		if c.Title != nil {
			label = *c.Title
		}
		out = append(out, Choice{Value: c.Const.Text, Label: label})
	}
	return out
}

func firstSet(a, b *float64) *float64 {
	if a != nil {
		return a
	}
	return b
}
