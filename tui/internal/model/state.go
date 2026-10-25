package model

import (
	"fmt"
	"time"
)

// MaxLog is the number of log lines State keeps; older lines are dropped.
const MaxLog = 500

// LastBeat is the most recent beat of one player, stamped with the local
// receive time so the UI can advance the position between beats.
type LastBeat struct {
	At            time.Time
	PositionMs    *int64
	BeatNumber    *int
	BeatWithinBar *int
	Tempo         *float64
}

// Snapshot is a full picture of the server, either from the GET endpoints
// (startup / after reconnect) or from the SSE snapshot event.
type Snapshot struct {
	Online       bool
	DeviceNumber *int
	Devices      []Device
	Players      []Player
	Master       MasterInfo
	NowPlaying   *Player
}

// State is the client-side view of the server. Not safe for concurrent use.
type State struct {
	Online       bool
	DeviceNumber *int
	Devices      []Device // server order (connect order)
	Players      map[int]Player
	Master       MasterInfo
	NowPlaying   *Player
	Beats        map[int]LastBeat
	Log          []string // "HH:MM:SS  text", at most MaxLog lines

	// LogBeats also logs downbeats (beatWithinBar == 1), like emu-watch --beats.
	LogBeats bool

	trackLine map[int]string // last logged track line per player (dedupe)
	npKey     string         // last logged now-playing key (dedupe)
}

// NewState returns an empty State.
func NewState() *State {
	return &State{
		Players:   map[int]Player{},
		Beats:     map[int]LastBeat{},
		trackLine: map[int]string{},
	}
}

// ReplaceSnapshot replaces everything with snap; nothing from before survives
// (players, beats, devices, master, now-playing). It does not log.
func (s *State) ReplaceSnapshot(snap Snapshot) {
	s.Online = snap.Online
	s.DeviceNumber = snap.DeviceNumber
	s.Devices = append([]Device(nil), snap.Devices...)
	s.Players = make(map[int]Player, len(snap.Players))
	for _, p := range snap.Players {
		s.Players[p.Number] = p
	}
	s.Master = snap.Master
	s.NowPlaying = nil
	if snap.NowPlaying != nil {
		np := *snap.NowPlaying
		s.NowPlaying = &np
	}
	s.Beats = map[int]LastBeat{}
	s.trackLine = map[int]string{}
}

// Apply applies one SSE event and appends the matching log line(s).
func (s *State) Apply(ev Event) {
	at := ev.At
	if at.IsZero() {
		at = time.Now()
	}
	switch ev.Kind {
	case EventSnapshot:
		s.applySnapshot(ev.Snapshot, at)
	case EventBeat:
		s.applyBeat(ev.Beat, at)
	case EventPlayer:
		s.applyPlayer(*ev.Player, at)
	case EventTrack:
		s.applyTrack(ev.Track, at)
	case EventDevice:
		s.applyDevice(ev.Device, at)
	case EventMaster:
		s.Master = *ev.Master
		s.AddLog(at, fmtMaster(s.Master))
	case EventOnline:
		s.Online = ev.Online.Online
		if s.Online {
			s.AddLog(at, "link online")
		} else {
			s.AddLog(at, "link offline")
		}
	default:
		s.AddLog(at, fmt.Sprintf("bad event %s: %v", ev.Name, ev.Err))
	}
}

// applySnapshot fully replaces state from the SSE snapshot. DeviceNumber is
// health-only, so it is kept. NowPlaying is not in the snapshot: it is
// refreshed from the new player of the same number, or cleared if that
// player is gone (the caller re-polls /v1/now-playing).
func (s *State) applySnapshot(snap *SseSnapshot, at time.Time) {
	var np *Player
	if s.NowPlaying != nil {
		for i := range snap.Players {
			if snap.Players[i].Number == s.NowPlaying.Number {
				np = &snap.Players[i]
				break
			}
		}
	}
	s.ReplaceSnapshot(Snapshot{
		Online:       snap.Online,
		DeviceNumber: s.DeviceNumber,
		Devices:      snap.Devices,
		Players:      snap.Players,
		Master:       snap.Master,
		NowPlaying:   np,
	})
	for _, l := range fmtSnapshot(snap) {
		s.AddLog(at, l)
	}
}

func (s *State) applyBeat(b *SseBeat, at time.Time) {
	lb := LastBeat{At: at, BeatNumber: b.BeatNumber, BeatWithinBar: b.BeatWithinBar, Tempo: b.Tempo}
	if b.Position != nil {
		ms := b.Position.Ms
		lb.PositionMs = &ms
	}
	s.Beats[b.Player] = lb
	if s.LogBeats {
		if l, ok := fmtBar(b); ok {
			s.AddLog(at, l)
		}
	}
}

func (s *State) applyPlayer(p Player, at time.Time) {
	prev, existed := s.Players[p.Number]
	s.Players[p.Number] = p
	if s.NowPlaying != nil && s.NowPlaying.Number == p.Number {
		np := p
		s.NowPlaying = &np
	}
	if !existed || playerKey(prev) != playerKey(p) {
		s.AddLog(at, fmtPlayer(p))
	}
}

func (s *State) applyTrack(t *SseTrack, at time.Time) {
	if p, ok := s.Players[t.Player]; ok {
		p.Track = t.Track
		s.Players[t.Player] = p
		if s.NowPlaying != nil && s.NowPlaying.Number == t.Player {
			np := p
			s.NowPlaying = &np
		}
	}
	l := fmtTrack(t)
	if s.trackLine[t.Player] == l {
		return
	}
	s.trackLine[t.Player] = l
	s.AddLog(at, l)
}

// applyDevice mirrors the server: a disconnect also drops that player.
func (s *State) applyDevice(d *SseDevice, at time.Time) {
	n := d.Device.Number
	idx := -1
	for i := range s.Devices {
		if s.Devices[i].Number == n {
			idx = i
			break
		}
	}
	switch d.Action {
	case "connected":
		if idx >= 0 {
			s.Devices[idx] = d.Device
		} else {
			s.Devices = append(s.Devices, d.Device)
		}
	case "disconnected":
		if idx >= 0 {
			s.Devices = append(s.Devices[:idx:idx], s.Devices[idx+1:]...)
		}
		delete(s.Players, n)
		delete(s.Beats, n)
		delete(s.trackLine, n)
		if s.NowPlaying != nil && s.NowPlaying.Number == n {
			s.NowPlaying = nil
		}
	}
	s.AddLog(at, fmt.Sprintf("device %d %s  %s %s", n, d.Action, d.Device.Name, d.Device.Address))
}

// SetNowPlaying stores the result of GET /v1/now-playing (nil = none) and logs
// "now playing → …" only when the player / track id / title changed.
func (s *State) SetNowPlaying(p *Player, at time.Time) {
	if p == nil {
		s.NowPlaying = nil
	} else {
		np := *p
		s.NowPlaying = &np
	}
	key := nowPlayingKey(p)
	if key == s.npKey {
		return
	}
	s.npKey = key
	s.AddLog(at, fmtNowPlaying(p))
}

// AddLog appends "HH:MM:SS  line", dropping the oldest lines beyond MaxLog.
func (s *State) AddLog(at time.Time, line string) {
	s.Log = append(s.Log, at.Format("15:04:05")+"  "+line)
	if over := len(s.Log) - MaxLog; over > 0 {
		s.Log = append(s.Log[:0:0], s.Log[over:]...)
	}
}
