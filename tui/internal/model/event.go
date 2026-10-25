package model

import (
	"encoding/json"
	"fmt"
	"time"
)

// EventKind identifies which payload field of Event is set.
type EventKind int

const (
	EventInvalid EventKind = iota // undecodable or unknown event; Name and Err are set
	EventSnapshot
	EventBeat
	EventPlayer
	EventTrack
	EventDevice
	EventMaster
	EventOnline
)

func (k EventKind) String() string {
	switch k {
	case EventSnapshot:
		return "snapshot"
	case EventBeat:
		return "beat"
	case EventPlayer:
		return "player"
	case EventTrack:
		return "track"
	case EventDevice:
		return "device"
	case EventMaster:
		return "master"
	case EventOnline:
		return "online"
	}
	return "invalid"
}

// Event is one SSE event. Exactly the payload matching Kind is non-nil.
type Event struct {
	Kind EventKind
	Name string    // SSE event name as received
	ID   string    // SSE id (may be empty)
	At   time.Time // local receive time; zero means "now" when applied
	Err  error     // EventInvalid only

	Snapshot *SseSnapshot
	Beat     *SseBeat
	Player   *Player
	Track    *SseTrack
	Device   *SseDevice
	Master   *MasterInfo
	Online   *SseOnline
}

// DecodeEvent decodes the data of an SSE event by its event name
// (the openapi x-sse-events map). Unknown names and bad JSON are errors.
func DecodeEvent(name string, data []byte) (Event, error) {
	e := Event{Name: name}
	var target any
	switch name {
	case "snapshot":
		e.Kind, e.Snapshot = EventSnapshot, new(SseSnapshot)
		target = e.Snapshot
	case "beat":
		e.Kind, e.Beat = EventBeat, new(SseBeat)
		target = e.Beat
	case "player":
		e.Kind, e.Player = EventPlayer, new(Player)
		target = e.Player
	case "track":
		e.Kind, e.Track = EventTrack, new(SseTrack)
		target = e.Track
	case "device":
		e.Kind, e.Device = EventDevice, new(SseDevice)
		target = e.Device
	case "master":
		e.Kind, e.Master = EventMaster, new(MasterInfo)
		target = e.Master
	case "online":
		e.Kind, e.Online = EventOnline, new(SseOnline)
		target = e.Online
	default:
		return Event{Kind: EventInvalid, Name: name}, fmt.Errorf("unknown event %q", name)
	}
	if err := json.Unmarshal(data, target); err != nil {
		return Event{Kind: EventInvalid, Name: name}, fmt.Errorf("decode %s: %w", name, err)
	}
	return e, nil
}
