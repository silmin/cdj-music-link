package model

import (
	"fmt"
	"math"
	"strings"
)

// Log wording follows scripts/emu-watch.sh.

// bpm formats like jq's `.*10|round/10` plus a forced ".0".
func bpm(v *float64) string {
	if v == nil {
		return "?"
	}
	return fmt.Sprintf("%.1f", math.Round(*v*10)/10)
}

func playerKey(p Player) string {
	air := "null"
	if p.IsOnAir != nil {
		air = fmt.Sprint(*p.IsOnAir)
	}
	return fmt.Sprintf("%s,%t,%t,%s", p.PlayState, p.IsPlaying, p.IsTempoMaster, air)
}

func fmtPlayer(p Player) string {
	var b strings.Builder
	fmt.Fprintf(&b, "player %d  %s", p.Number, p.PlayState)
	if p.IsPlaying {
		fmt.Fprintf(&b, "  %s BPM", bpm(p.Tempo))
	}
	if p.IsTempoMaster {
		b.WriteString("  master")
	}
	if p.IsOnAir != nil && *p.IsOnAir {
		b.WriteString("  on-air")
	}
	return b.String()
}

func artistOr(t *Track) string {
	if t.Artist == nil {
		return "(no artist)"
	}
	return *t.Artist
}

func fmtTrack(e *SseTrack) string {
	head := fmt.Sprintf("player %d  ", e.Player)
	t := e.Track
	switch {
	case t == nil:
		return head + "track unloaded"
	case t.Title == nil:
		return head + fmt.Sprintf("track loaded  id=%d from device %d (%s, %s)", t.ID, t.SourcePlayer, t.Slot, t.Type)
	}
	var b strings.Builder
	fmt.Fprintf(&b, "%smetadata  \"%s\" — %s", head, *t.Title, artistOr(t))
	if t.Key != nil {
		fmt.Fprintf(&b, "  key %s", *t.Key)
	}
	if t.StartingBpm != nil {
		fmt.Fprintf(&b, "  %d BPM", int(math.Round(*t.StartingBpm)))
	}
	if t.DurationSec != nil {
		fmt.Fprintf(&b, "  %d:%02d", *t.DurationSec/60, *t.DurationSec%60)
	}
	return b.String()
}

func fmtSnapshot(s *SseSnapshot) []string {
	out := []string{"snapshot", fmt.Sprintf("    online: %t", s.Online)}
	if len(s.Devices) == 0 {
		out = append(out, "    devices: (none)")
	} else {
		ds := make([]string, len(s.Devices))
		for i, d := range s.Devices {
			ds[i] = fmt.Sprintf("%d %s %s", d.Number, d.Name, d.Address)
		}
		out = append(out, "    devices: "+strings.Join(ds, ", "))
	}
	for _, p := range s.Players {
		tr := "(none)"
		if p.Track != nil {
			if p.Track.Title != nil {
				tr = *p.Track.Title
			} else {
				tr = fmt.Sprintf("(id %d, no metadata)", p.Track.ID)
			}
		}
		out = append(out, fmt.Sprintf("    player %d: %s  track: %s", p.Number, p.PlayState, tr))
	}
	return out
}

func fmtMaster(m MasterInfo) string {
	if m.Player == nil {
		return "tempo master → none"
	}
	return fmt.Sprintf("tempo master → player %d (%s BPM)", *m.Player, bpm(m.Tempo))
}

// fmtBar formats a downbeat; ok is false for other beats.
func fmtBar(b *SseBeat) (string, bool) {
	if b.BeatWithinBar == nil || *b.BeatWithinBar != 1 || b.BeatNumber == nil {
		return "", false
	}
	var ms int64
	if b.Position != nil {
		ms = b.Position.Ms
	}
	t := int64(math.Round(float64(ms) / 100))
	return fmt.Sprintf("player %d  bar  beat %d  %s BPM  pos %d.%ds", b.Player, *b.BeatNumber, bpm(b.Tempo), t/10, t%10), true
}

func nowPlayingKey(p *Player) string {
	if p == nil {
		return "none"
	}
	id, title := "", ""
	if p.Track != nil {
		id = fmt.Sprint(p.Track.ID)
		if p.Track.Title != nil {
			title = *p.Track.Title
		}
	}
	return fmt.Sprintf("%d|%s|%s", p.Number, id, title)
}

func fmtNowPlaying(p *Player) string {
	if p == nil {
		return "now playing → none"
	}
	head := fmt.Sprintf("now playing → player %d  ", p.Number)
	if p.Track != nil && p.Track.Title != nil {
		return head + fmt.Sprintf("\"%s\" — %s", *p.Track.Title, artistOr(p.Track))
	}
	id := "?"
	if p.Track != nil {
		id = fmt.Sprint(p.Track.ID)
	}
	return head + "id=" + id + " (metadata pending)"
}
