package agystore

import (
	"bufio"
	"bytes"
	"cmp"
	"context"
	"encoding/json"
	"io"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// Summary 是 agy 会话的概要信息，对应 Zeta 列表里的一项。
type Summary struct {
	ID       string
	File     string
	Cwd      string
	Title    string
	Preview  string
	Updated  int64
	Created  int64
	Replied  int64
	Archived bool
	Pinned   bool
}

// ZetaMeta 是 Zeta 在 brain/<id>/.zeta_meta.json 里记下的补充元数据（当主机没装 sqlite3 或会话刚建时兜底 cwd 与标题）。
type ZetaMeta struct {
	Cwd    string `json:"cwd,omitempty"`
	Title  string `json:"title,omitempty"`
	Parent string `json:"parent,omitempty"`
}

// WriteZetaMeta 在会话的 brain 目录存在时记下工作目录和初始标题。
func WriteZetaMeta(agyDir, id string, meta ZetaMeta) {
	if !IsSessionID(id) {
		return
	}
	brain := filepath.Join(agyDir, "brain", id)
	if st, err := os.Stat(brain); err != nil || !st.IsDir() {
		return
	}
	path := filepath.Join(brain, ".zeta_meta.json")
	cur := ReadZetaMeta(agyDir, id)
	if meta.Cwd != "" {
		cur.Cwd = meta.Cwd
	}
	if meta.Title != "" {
		cur.Title = meta.Title
	}
	if meta.Parent != "" {
		cur.Parent = meta.Parent
	}
	b, err := json.Marshal(cur)
	if err != nil {
		return
	}
	_ = os.WriteFile(path, b, 0o644)
}

// ReadZetaMeta 读取 brain/<id>/.zeta_meta.json；不存在时返回零值。
func ReadZetaMeta(agyDir, id string) ZetaMeta {
	var m ZetaMeta
	if data, err := os.ReadFile(filepath.Join(agyDir, "brain", id, ".zeta_meta.json")); err == nil {
		_ = json.Unmarshal(data, &m)
	}
	return m
}

type sqliteBool bool

func (b *sqliteBool) UnmarshalJSON(data []byte) error {
	s := strings.TrimSpace(string(data))
	switch strings.ToLower(strings.Trim(s, `"`)) {
	case "1", "true":
		*b = true
	default:
		*b = false
	}
	return nil
}

type dbSummaryRow struct {
	ID                string     `json:"conversation_id"`
	Title             string     `json:"title"`
	Preview           string     `json:"preview"`
	StepCount         int        `json:"step_count"`
	LastModifiedTime  string     `json:"last_modified_time"`
	WorkspaceURIs     string     `json:"workspace_uris"`
	Status            string     `json:"status"`
	ParentID          string     `json:"parent_conversation_id"`
	NotFullyIdle      sqliteBool `json:"not_fully_idle"`
	LastUserInputTime string     `json:"last_user_input_time"`
}

type dbCacheEntry struct {
	dbSize, dbMtime   int64
	walSize, walMtime int64
	rows              map[string]dbSummaryRow
}

var dbCache struct {
	mu      sync.Mutex
	entries map[string]dbCacheEntry
}

var (
	sqliteBinOnce sync.Once
	sqliteBinPath string
)

func sqlite3Path() string {
	sqliteBinOnce.Do(func() {
		if p, err := exec.LookPath("sqlite3"); err == nil {
			sqliteBinPath = p
		}
	})
	return sqliteBinPath
}

func readDBRows(agyDir string) map[string]dbSummaryRow {
	dbPath := filepath.Join(agyDir, "conversation_summaries.db")
	dbSize, dbMtime, ok := statSig(dbPath)
	if !ok {
		return nil
	}
	walSize, walMtime, _ := statSig(dbPath + "-wal")
	dbCache.mu.Lock()
	if dbCache.entries == nil {
		dbCache.entries = map[string]dbCacheEntry{}
	}
	if c, hit := dbCache.entries[dbPath]; hit && c.dbSize == dbSize && c.dbMtime == dbMtime && c.walSize == walSize && c.walMtime == walMtime {
		dbCache.mu.Unlock()
		return c.rows
	}
	dbCache.mu.Unlock()

	rows := querySQLiteSummaries(dbPath)
	dbCache.mu.Lock()
	dbCache.entries[dbPath] = dbCacheEntry{dbSize: dbSize, dbMtime: dbMtime, walSize: walSize, walMtime: walMtime, rows: rows}
	dbCache.mu.Unlock()
	return rows
}

func invalidateDBCache(agyDir string) {
	dbCache.mu.Lock()
	delete(dbCache.entries, filepath.Join(agyDir, "conversation_summaries.db"))
	dbCache.mu.Unlock()
}

func statSig(path string) (int64, int64, bool) {
	fi, err := os.Stat(path)
	if err != nil {
		return 0, 0, false
	}
	return fi.Size(), fi.ModTime().UnixNano(), true
}

func querySQLiteSummaries(dbPath string) map[string]dbSummaryRow {
	sqliteBin := sqlite3Path()
	if sqliteBin == "" {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	const query = "SELECT conversation_id, COALESCE(title,'') AS title, COALESCE(preview,'') AS preview, " +
		"COALESCE(step_count,0) AS step_count, COALESCE(last_modified_time,'') AS last_modified_time, " +
		"COALESCE(workspace_uris,'') AS workspace_uris, COALESCE(status,'') AS status, " +
		"COALESCE(parent_conversation_id,'') AS parent_conversation_id, " +
		"COALESCE(not_fully_idle,0) AS not_fully_idle, COALESCE(last_user_input_time,'') AS last_user_input_time " +
		"FROM conversation_summaries;"
	out, err := exec.CommandContext(ctx, sqliteBin, "-readonly", "-json", dbPath, query).Output()
	if err != nil {
		return nil
	}
	out = bytes.TrimSpace(out)
	if len(out) == 0 {
		return map[string]dbSummaryRow{}
	}
	var list []dbSummaryRow
	if json.Unmarshal(out, &list) != nil {
		return nil
	}
	byID := make(map[string]dbSummaryRow, len(list))
	for _, r := range list {
		if IsSessionID(r.ID) {
			byID[r.ID] = r
		}
	}
	return byID
}

// SessionIDs 返回所有顶层、非空 agy 会话的 id 集合。
func SessionIDs(agyDir string) map[string]bool {
	ids := map[string]bool{}
	rows := readDBRows(agyDir)
	for _, id := range candidateIDs(agyDir, rows) {
		ids[id] = true
	}
	return ids
}

func candidateIDs(agyDir string, rows map[string]dbSummaryRow) []string {
	seen := map[string]bool{}
	var ids []string
	for id, r := range rows {
		if r.ParentID == "" && r.StepCount > 0 && fileExists(TranscriptPath(agyDir, id)) {
			seen[id] = true
			ids = append(ids, id)
		}
	}
	entries, _ := os.ReadDir(filepath.Join(agyDir, "brain"))
	for _, e := range entries {
		id := e.Name()
		if !seen[id] && topLevelBrainCandidate(agyDir, id, rows) {
			seen[id] = true
			ids = append(ids, id)
		}
	}
	return ids
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func topLevelBrainCandidate(agyDir, id string, rows map[string]dbSummaryRow) bool {
	if !IsSessionID(id) {
		return false
	}
	if r, inDB := rows[id]; inDB && (r.ParentID != "" || r.StepCount <= 0) {
		return false
	}
	if ReadZetaMeta(agyDir, id).Parent != "" {
		return false
	}
	return fileExists(TranscriptPath(agyDir, id))
}

// ListedTranscriptFiles 返回按修改时间从新到旧排好序的顶层 agy transcript.jsonl 路径。
func ListedTranscriptFiles(agyDir string) []string {
	rows := readDBRows(agyDir)
	ids := candidateIDs(agyDir, rows)
	type timed struct {
		path string
		ms   int64
	}
	list := make([]timed, 0, len(ids))
	for _, id := range ids {
		p := TranscriptPath(agyDir, id)
		fi, err := os.Stat(p)
		if err != nil {
			continue
		}
		ms := fi.ModTime().UnixMilli()
		if r, ok := rows[id]; ok {
			if dbMs := parseISOMillis(r.LastModifiedTime); dbMs > ms {
				ms = dbMs
			}
		}
		list = append(list, timed{p, ms})
	}
	slices.SortStableFunc(list, func(a, b timed) int { return cmp.Compare(b.ms, a.ms) })
	paths := make([]string, len(list))
	for i, it := range list {
		paths[i] = it.path
	}
	return paths
}

// HasSessionData 判断 agyDir 下是否存在该会话的本地数据（brain、annotations 或 conversations）。
func HasSessionData(agyDir, id string) bool {
	if !IsSessionID(id) {
		return false
	}
	if lstatExists(filepath.Join(agyDir, "brain", id)) || lstatExists(AnnotationPath(agyDir, id)) {
		return true
	}
	base := filepath.Join(agyDir, "conversations", id)
	return lstatExists(base+".db") || lstatExists(base+".pb")
}

func lstatExists(path string) bool {
	_, err := os.Lstat(path)
	return err == nil
}

// ReadTitle 仅从标题源（annotations/*.pbtxt、conversation_summaries.db、.zeta_meta.json）读取 agy 会话标题，不读 transcript.jsonl。
func ReadTitle(agyDir, id string) string {
	if !IsSessionID(id) {
		return ""
	}
	if title := ReadAnnotation(agyDir, id).Title; title != "" {
		return title
	}
	if row, ok := readDBRows(agyDir)[id]; ok && row.Title != "" {
		return row.Title
	}
	return ReadZetaMeta(agyDir, id).Title
}

// Summarize 读取一份 agy transcript.jsonl 的概要；一句用户输入都没有时 ok 为假。
func Summarize(agyDir, path string) (Summary, bool, error) {
	id, ok := SessionIDFromTranscript(agyDir, path)
	if !ok {
		return Summary{}, false, nil
	}
	fi, err := os.Stat(path)
	if err != nil {
		return Summary{}, false, err
	}
	head, tail, err := readHeadAndTail(path, 64*1024)
	if err != nil {
		return Summary{}, false, err
	}
	preview, created, headCwd, headReplied := scanHead(head)
	rows := readDBRows(agyDir)
	row, hasRow := rows[id]
	if hasRow && row.ParentID != "" {
		return Summary{}, false, nil
	}
	preview = cmp.Or(preview, strings.TrimSpace(row.Preview))
	if preview == "" {
		return Summary{}, false, nil
	}
	var tailReplied int64
	if len(tail) > 0 {
		tailReplied = scanTailReply(tail)
	}
	replied := cmp.Or(tailReplied, headReplied)
	updated := fi.ModTime().UnixMilli()
	ann := ReadAnnotation(agyDir, id)
	zmeta := ReadZetaMeta(agyDir, id)
	if hasRow {
		if dbUpd := parseISOMillis(row.LastModifiedTime); dbUpd > updated {
			updated = dbUpd
		}
		if created == 0 {
			created = parseISOMillis(row.LastUserInputTime)
		}
	}
	created = cmp.Or(created, updated)
	title := cmp.Or(ann.Title, row.Title, zmeta.Title)
	cwd := cmp.Or(firstWorkspacePath(row.WorkspaceURIs), zmeta.Cwd, headCwd)
	return Summary{
		ID:       id,
		File:     path,
		Cwd:      cwd,
		Title:    title,
		Preview:  truncateRunes(preview, 200),
		Updated:  updated,
		Created:  created,
		Replied:  replied,
		Archived: ann.Archived,
		Pinned:   ann.Pinned,
	}, true, nil
}

func readHeadAndTail(path string, limit int64) (head, tail []byte, err error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, nil, err
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return nil, nil, err
	}
	size := fi.Size()
	headLen := min(size, limit)
	head = make([]byte, headLen)
	n, err := io.ReadFull(f, head)
	if err != nil && err != io.EOF && err != io.ErrUnexpectedEOF {
		return nil, nil, err
	}
	head = head[:n]
	if size <= limit {
		return head, nil, nil
	}
	start := size - limit
	tail = make([]byte, limit)
	tn, err := f.ReadAt(tail, start)
	if err != nil && err != io.EOF {
		return nil, nil, err
	}
	tail = tail[:tn]
	if idx := bytes.IndexByte(tail, '\n'); idx >= 0 {
		tail = tail[idx+1:]
	}
	return head, tail, nil
}

type headState struct {
	first   string
	preview string
	created int64
	cwd     string
	replied int64
}

func scanHead(data []byte) (preview string, created int64, cwd string, replied int64) {
	var hs headState
	for _, line := range bytes.Split(data, []byte{'\n'}) {
		line = bytes.TrimSpace(line)
		if len(line) == 0 || (hs.created != 0 && !headLineRelevant(line, hs.preview == "")) {
			continue
		}
		var st rawStep
		if json.Unmarshal(line, &st) == nil {
			hs.observe(st)
		}
	}
	return cmp.Or(hs.preview, hs.first), hs.created, hs.cwd, hs.replied
}

func headLineRelevant(line []byte, needPreview bool) bool {
	if bytes.Contains(line, []byte(`"PLANNER_RESPONSE"`)) {
		return true
	}
	return needPreview && bytes.Contains(line, []byte(`"USER_INPUT"`))
}

func (hs *headState) observe(st rawStep) {
	if hs.created == 0 {
		hs.created = parseISOMillis(st.CreatedAt)
	}
	if st.Type == "USER_INPUT" && hs.preview == "" {
		hs.observeUserInput(st.Content)
	}
	if st.Type == "PLANNER_RESPONSE" && len(st.ToolCalls) == 0 && strings.TrimSpace(st.Content) != "" {
		if ms := parseISOMillis(st.CreatedAt); ms > 0 {
			hs.replied = ms
		}
	}
}

func (hs *headState) observeUserInput(content string) {
	if hs.cwd == "" {
		hs.cwd = ExtractWorkspaceFromContent(content)
	}
	txt := ExtractUserRequest(content)
	if txt == "" {
		return
	}
	if hs.first == "" {
		hs.first = txt
	}
	if !strings.HasPrefix(txt, "/") {
		hs.preview = txt
	}
}

func scanTailReply(data []byte) int64 {
	var replied int64
	for _, line := range bytes.Split(data, []byte{'\n'}) {
		line = bytes.TrimSpace(line)
		if len(line) == 0 || !bytes.Contains(line, []byte(`"PLANNER_RESPONSE"`)) {
			continue
		}
		var st rawStep
		if json.Unmarshal(line, &st) == nil && st.Type == "PLANNER_RESPONSE" && len(st.ToolCalls) == 0 && strings.TrimSpace(st.Content) != "" {
			if ms := parseISOMillis(st.CreatedAt); ms > 0 {
				replied = ms
			}
		}
	}
	return replied
}

func firstWorkspacePath(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return ""
	}
	var list []string
	if strings.HasPrefix(raw, "[") && json.Unmarshal([]byte(raw), &list) == nil && len(list) > 0 {
		return cleanWorkspaceItem(list[0])
	}
	first, _, _ := strings.Cut(raw, ",")
	return cleanWorkspaceItem(first)
}

func cleanWorkspaceItem(s string) string {
	s = strings.TrimSpace(strings.Trim(s, `"`))
	if strings.HasPrefix(s, "file://") {
		if u, err := url.Parse(s); err == nil && u.Path != "" {
			return u.Path
		}
		return strings.TrimPrefix(s, "file://")
	}
	return s
}

func truncateRunes(s string, max int) string {
	if utf8.RuneCountInString(s) <= max {
		return s
	}
	n := 0
	for i := range s {
		if n == max {
			return s[:i]
		}
		n++
	}
	return s
}

// Rename 给 agy 会话改名：写入 annotations/<id>.pbtxt，并在有 sqlite3 时同步更新 conversation_summaries.db。
func Rename(agyDir, id, cleanTitle string) error {
	if err := SetAnnotationTitle(agyDir, id, cleanTitle); err != nil {
		return err
	}
	WriteZetaMeta(agyDir, id, ZetaMeta{Title: cleanTitle})
	updateSQLiteTitle(agyDir, id, cleanTitle)
	invalidateDBCache(agyDir)
	return nil
}

func execSQLite(dbPath, sql string) {
	st, err := os.Stat(dbPath)
	if err != nil || st.IsDir() {
		return
	}
	sqliteBin := sqlite3Path()
	if sqliteBin == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, sqliteBin, dbPath)
	cmd.Stdin = strings.NewReader(".bail off\n" + sql + "\n")
	_ = cmd.Run()
}

// SessionRunStatus 按 conversation_summaries.db 里的 status 与 not_fully_idle 返回 agy 会话的忙闲（StatusBusy 或 StatusIdle）。
func SessionRunStatus(agyDir, id string) string {
	if row, ok := readDBRows(agyDir)[id]; ok {
		if row.Status == "CASCADE_RUN_STATUS_RUNNING" || bool(row.NotFullyIdle) {
			return sessionproto.StatusBusy
		}
	}
	return sessionproto.StatusIdle
}

func updateSQLiteTitle(agyDir, id, title string) {
	escaped := strings.ReplaceAll(title, "'", "''")
	idEsc := strings.ReplaceAll(id, "'", "''")
	execSQLite(filepath.Join(agyDir, "conversation_summaries.db"),
		"UPDATE conversation_summaries SET title = '"+escaped+"' WHERE conversation_id = '"+idEsc+"';")
}

// Delete 删除一个 agy 会话的本地记录（brain/<id>、conversations/<id>.db*、annotations/<id>.pbtxt、presence/<id>.lock 及摘要行）。
func Delete(agyDir, id string) (hadBrain bool, err error) {
	if !IsSessionID(id) {
		return false, nil
	}
	brainDir := filepath.Join(agyDir, "brain", id)
	if st, statErr := os.Lstat(brainDir); statErr == nil && st.IsDir() {
		hadBrain = true
		_ = os.RemoveAll(brainDir)
	}
	_ = os.Remove(AnnotationPath(agyDir, id))
	_ = os.Remove(filepath.Join(agyDir, "presence", id+".lock"))
	convBase := filepath.Join(agyDir, "conversations", id)
	for _, suffix := range []string{".db", ".db-wal", ".db-shm", ".pb", ".lock"} {
		p := convBase + suffix
		if st, statErr := os.Lstat(p); statErr == nil && st.IsDir() {
			_ = os.RemoveAll(p)
		} else {
			_ = os.Remove(p)
		}
	}
	deleteSQLiteSummary(agyDir, id)
	invalidateDBCache(agyDir)
	return hadBrain, nil
}

func deleteSQLiteSummary(agyDir, id string) {
	idEsc := strings.ReplaceAll(id, "'", "''")
	dbPath := filepath.Join(agyDir, "conversation_summaries.db")
	sql := "DELETE FROM conversation_summaries WHERE conversation_id = '" + idEsc + "';\n" +
		"DELETE FROM conversations_fts WHERE conversation_id = '" + idEsc + "';\n" +
		"DELETE FROM conversation_parents WHERE conversation_id = '" + idEsc + "';"
	execSQLite(dbPath, sql)
}

// SearchTranscript 在一份 agy transcript.jsonl 里按小写关键词 needle 搜索第一处用户或模型发言命中。
func SearchTranscript(path, needle string) (role, text string, ok bool) {
	f, err := os.Open(path)
	if err != nil {
		return "", "", false
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 64*1024), 4*1024*1024)
	for sc.Scan() {
		if r, txt, hit := matchTranscriptLine(sc.Bytes(), needle); hit {
			return r, txt, true
		}
	}
	return "", "", false
}

func matchTranscriptLine(line []byte, needle string) (role, text string, ok bool) {
	if !bytes.Contains(line, []byte(`"USER_INPUT"`)) && !bytes.Contains(line, []byte(`"PLANNER_RESPONSE"`)) {
		return "", "", false
	}
	var st rawStep
	if json.Unmarshal(line, &st) != nil {
		return "", "", false
	}
	switch st.Type {
	case "USER_INPUT":
		u := ExtractUserRequest(st.Content)
		if strings.Contains(strings.ToLower(u), needle) {
			return "user", u, true
		}
	case "PLANNER_RESPONSE":
		c := strings.TrimSpace(st.Content)
		if c != "" && strings.Contains(strings.ToLower(c), needle) {
			return "assistant", c, true
		}
	}
	return "", "", false
}
