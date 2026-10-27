package ui

import (
	"context"
	"fmt"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/silmin/cdj-music-link/tui/internal/api"
	"github.com/silmin/cdj-music-link/tui/internal/model"
)

// Client is the part of api.Client the program uses.
type Client interface {
	Snapshot() (model.Snapshot, error)
	NowPlaying() (*model.Player, error)
	WaveformPreview(n int) (*model.WaveformPreview, error)
	Stream(ctx context.Context) (<-chan model.Event, error)
}

const (
	tickEvery      = 250 * time.Millisecond
	reconnectDelay = 2 * time.Second
	npDebounce     = 300 * time.Millisecond
	// quietLimit: no event at all for this long while a deck plays means the
	// stream is dead even though TCP has not noticed.
	quietLimit = 90 * time.Second
	// waveform fetch retry backoff after a non-404 failure: 5 s, 10 s, … up to 60 s
	waveRetryBase = 5 * time.Second
	waveRetryMax  = 60 * time.Second
)

type (
	tickMsg  time.Time
	eventMsg struct {
		gen int
		ev  model.Event
	}
	streamClosedMsg struct{ gen int }
	streamOpenedMsg struct {
		gen    int
		ch     <-chan model.Event
		cancel context.CancelFunc
	}
	streamErrMsg struct {
		gen int
		err error
	}
	reconnectMsg struct{ gen int }
	snapshotMsg  struct {
		gen  int
		snap model.Snapshot
		err  error
	}
	npDueMsg      struct{}
	nowPlayingMsg struct {
		p   *model.Player
		err error
	}
	waveformMsg struct {
		player  int
		key     string
		preview *model.WaveformPreview
		err     error
	}
)

type waveEntry struct {
	key      string // track identity; "" = no waveform wanted
	preview  *model.WaveformPreview
	inflight bool
	done     bool // preview set, 404, or no waveform wanted: nothing more to fetch
	attempts int
	retryAt  time.Time
}

// Model is the bubbletea model. Create it with New.
type Model struct {
	c     Client
	st    *model.State
	w, h  int
	now   time.Time
	blink bool
	wave  bool

	gen          int // stream attempt; messages of older attempts are dropped
	ch           <-chan model.Event
	cancel       context.CancelFunc
	streamUp     bool
	reconnecting bool
	lastEvent    time.Time
	lastErr      string

	npPending bool
	waves     map[int]waveEntry

	nowFn func() time.Time
}

// New returns a Model showing snap (the first Snapshot, fetched by the caller).
func New(c Client, snap model.Snapshot) *Model {
	m := &Model{c: c, st: model.NewState(), blink: true, wave: true, waves: map[int]waveEntry{}, nowFn: time.Now}
	m.now = m.nowFn()
	m.st.ReplaceSnapshot(snap)
	return m
}

// State exposes the state (for tests and the caller's initial log lines).
func (m *Model) State() *model.State { return m.st }

func (m *Model) Init() tea.Cmd {
	cmds := []tea.Cmd{tick(), m.openStream(), m.scheduleNowPlaying()}
	return tea.Batch(append(cmds, m.syncWaves()...)...)
}

func tick() tea.Cmd {
	return tea.Tick(tickEvery, func(t time.Time) tea.Msg { return tickMsg(t) })
}

func (m *Model) openStream() tea.Cmd {
	gen, c := m.gen, m.c
	return func() tea.Msg {
		ctx, cancel := context.WithCancel(context.Background())
		ch, err := c.Stream(ctx)
		if err != nil {
			cancel()
			return streamErrMsg{gen, err}
		}
		return streamOpenedMsg{gen, ch, cancel}
	}
}

func waitEvent(gen int, ch <-chan model.Event) tea.Cmd {
	return func() tea.Msg {
		ev, ok := <-ch
		if !ok {
			return streamClosedMsg{gen}
		}
		return eventMsg{gen, ev}
	}
}

// retry drops the current stream and schedules Snapshot + Stream in 2 s.
func (m *Model) retry(reason string) tea.Cmd {
	if m.cancel != nil {
		m.cancel()
		m.cancel = nil
	}
	m.ch, m.streamUp = nil, false
	m.gen++
	if reason != "" && reason != m.lastErr {
		m.st.AddLog(m.now, reason)
		m.lastErr = reason
	}
	m.reconnecting = true
	gen := m.gen
	return tea.Tick(reconnectDelay, func(time.Time) tea.Msg { return reconnectMsg{gen} })
}

// scheduleNowPlaying coalesces now-playing polls: one GET 300 ms after the
// first trigger, however many triggers arrive meanwhile.
func (m *Model) scheduleNowPlaying() tea.Cmd {
	if m.npPending {
		return nil
	}
	m.npPending = true
	return tea.Tick(npDebounce, func(time.Time) tea.Msg { return npDueMsg{} })
}

func waveKey(p model.Player) string {
	t := p.Track
	if t == nil || !t.WaveformAvailable {
		return ""
	}
	return fmt.Sprintf("%d/%s/%d", t.SourcePlayer, t.Slot, t.ID)
}

// syncWaves starts one waveform fetch per player whose track changed to one
// with a waveform, and retries earlier failures once their backoff elapsed.
// A 404 (no waveform for that track) is final.
func (m *Model) syncWaves() []tea.Cmd {
	var cmds []tea.Cmd
	for n := range m.waves {
		if _, ok := m.st.Players[n]; !ok {
			delete(m.waves, n)
		}
	}
	for n, p := range m.st.Players {
		key := waveKey(p)
		e, ok := m.waves[n]
		if !ok || e.key != key {
			e = waveEntry{key: key, done: key == ""}
		}
		if !e.done && !e.inflight && !m.now.Before(e.retryAt) {
			e.inflight = true
			n, c := n, m.c
			cmds = append(cmds, func() tea.Msg {
				w, err := c.WaveformPreview(n)
				return waveformMsg{n, key, w, err}
			})
		}
		m.waves[n] = e
	}
	return cmds
}

func (m *Model) applyWaveform(msg waveformMsg) {
	e, ok := m.waves[msg.player]
	if !ok || e.key != msg.key {
		return
	}
	e.inflight = false
	switch {
	case msg.err == nil:
		e.preview, e.done = msg.preview, true
	case api.IsNotFound(msg.err):
		e.done = true
	default:
		e.attempts++
		wait := waveRetryBase << (e.attempts - 1)
		if wait > waveRetryMax || wait <= 0 {
			wait = waveRetryMax
		}
		e.retryAt = m.now.Add(wait)
		if e.attempts == 1 {
			m.st.AddLog(m.now, fmt.Sprintf("waveform %d: %v (retrying)", msg.player, msg.err))
		}
	}
	m.waves[msg.player] = e
}

func (m *Model) anyPlaying() bool {
	for _, p := range m.st.Players {
		if p.IsPlaying {
			return true
		}
	}
	return false
}

func (m *Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.w, m.h = msg.Width, msg.Height
		return m, nil

	case tea.KeyMsg:
		switch msg.String() {
		case "q", "ctrl+c":
			if m.cancel != nil {
				m.cancel()
			}
			return m, tea.Quit
		case "b":
			m.blink = !m.blink
		case "w":
			m.wave = !m.wave
		}
		return m, nil

	case tickMsg:
		m.now = time.Time(msg)
		if m.streamUp && m.anyPlaying() && m.now.Sub(m.lastEvent) >= quietLimit {
			m.st.AddLog(m.now, fmt.Sprintf("link: no events for %ds while playing", int(quietLimit.Seconds())))
			return m, tea.Batch(tick(), m.retry("link: stream lost, reconnecting…"))
		}
		return m, tea.Batch(append([]tea.Cmd{tick()}, m.syncWaves()...)...)

	case streamOpenedMsg:
		if msg.gen != m.gen {
			msg.cancel()
			return m, nil
		}
		m.now = m.nowFn()
		m.ch, m.cancel, m.streamUp, m.lastEvent, m.lastErr = msg.ch, msg.cancel, true, m.now, ""
		if m.reconnecting {
			m.st.AddLog(m.now, "link: reconnected")
			m.reconnecting = false
		}
		return m, waitEvent(m.gen, m.ch)

	case streamErrMsg:
		if msg.gen != m.gen {
			return m, nil
		}
		m.now = m.nowFn()
		return m, m.retry("link: stream: " + msg.err.Error())

	case streamClosedMsg:
		if msg.gen != m.gen {
			return m, nil
		}
		m.now = m.nowFn()
		return m, m.retry("link: stream lost, reconnecting…")

	case eventMsg:
		if msg.gen != m.gen {
			return m, nil
		}
		m.now = m.nowFn()
		m.lastEvent = m.now
		m.st.Apply(msg.ev)
		cmds := []tea.Cmd{waitEvent(m.gen, m.ch)}
		switch msg.ev.Kind {
		case model.EventPlayer, model.EventTrack, model.EventMaster, model.EventSnapshot:
			cmds = append(cmds, m.scheduleNowPlaying())
		}
		switch msg.ev.Kind {
		case model.EventPlayer, model.EventTrack, model.EventSnapshot, model.EventDevice:
			cmds = append(cmds, m.syncWaves()...)
		}
		return m, tea.Batch(cmds...)

	case reconnectMsg:
		if msg.gen != m.gen {
			return m, nil
		}
		gen, c := m.gen, m.c
		return m, func() tea.Msg {
			s, err := c.Snapshot()
			return snapshotMsg{gen, s, err}
		}

	case snapshotMsg:
		if msg.gen != m.gen {
			return m, nil
		}
		m.now = m.nowFn()
		if msg.err != nil {
			return m, m.retry("link: snapshot: " + msg.err.Error())
		}
		m.st.ReplaceSnapshot(msg.snap)
		cmds := append([]tea.Cmd{m.openStream(), m.scheduleNowPlaying()}, m.syncWaves()...)
		return m, tea.Batch(cmds...)

	case npDueMsg:
		m.npPending = false
		c := m.c
		return m, func() tea.Msg {
			p, err := c.NowPlaying()
			return nowPlayingMsg{p, err}
		}

	case nowPlayingMsg:
		if msg.err == nil {
			m.now = m.nowFn()
			m.st.SetNowPlaying(msg.p, m.now)
		}
		return m, nil

	case waveformMsg:
		m.now = m.nowFn()
		m.applyWaveform(msg)
		return m, nil
	}
	return m, nil
}

func (m *Model) View() string {
	waves := make(map[int]*model.WaveformPreview, len(m.waves))
	for n, e := range m.waves {
		if e.preview != nil {
			waves[n] = e.preview
		}
	}
	return Render(m.st, Options{
		Width: m.w, Height: m.h, Now: m.now,
		Blink: m.blink, ShowWave: m.wave, StreamUp: m.streamUp, Waves: waves,
	})
}
