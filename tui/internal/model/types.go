// Package model holds the cdj-music-link API JSON types (named after the
// openapi schemas) and the client-side State that SSE events are applied to.
package model

// Nullable fields in the contract are pointers; nil means JSON null.

// Health is GET /v1/health.
type Health struct {
	Online       bool   `json:"online"`
	DeviceNumber *int   `json:"deviceNumber"`
	DeviceCount  int    `json:"deviceCount"`
	ServerTime   int64  `json:"serverTime"`
	Version      string `json:"version"`
}

// Device is one DJ Link device. Kind is player, mixer or collection.
type Device struct {
	Number  int    `json:"number"`
	Name    string `json:"name"`
	Address string `json:"address"`
	Kind    string `json:"kind"`
}

// Color is a rekordbox track color.
type Color struct {
	Name string `json:"name"`
	Hex  string `json:"hex"`
}

// Position is a player's playback position.
type Position struct {
	Ms          int64  `json:"ms"`
	RemainingMs *int64 `json:"remainingMs"`
	UpdatedAt   int64  `json:"updatedAt"`
	Definitive  bool   `json:"definitive"`
}

// Track is the loaded track. Right after loading only ID, SourcePlayer, Slot
// and Type are set; metadata fills in later.
type Track struct {
	ID                int      `json:"id"`
	SourcePlayer      int      `json:"sourcePlayer"`
	Slot              string   `json:"slot"`
	Type              string   `json:"type"`
	Title             *string  `json:"title"`
	Artist            *string  `json:"artist"`
	Album             *string  `json:"album"`
	Key               *string  `json:"key"`
	Genre             *string  `json:"genre"`
	Label             *string  `json:"label"`
	Remixer           *string  `json:"remixer"`
	OriginalArtist    *string  `json:"originalArtist"`
	Comment           *string  `json:"comment"`
	DurationSec       *int     `json:"durationSec"`
	StartingBpm       *float64 `json:"startingBpm"`
	Rating            *int     `json:"rating"`
	Year              *int     `json:"year"`
	DateAdded         *string  `json:"dateAdded"`
	Color             *Color   `json:"color"`
	ArtworkAvailable  bool     `json:"artworkAvailable"`
	WaveformAvailable bool     `json:"waveformAvailable"`
	BeatgridAvailable bool     `json:"beatgridAvailable"`
}

// Player is one player's state (also the SSE "player" event payload, SsePlayer).
type Player struct {
	Number        int       `json:"number"`
	Name          string    `json:"name"`
	Address       string    `json:"address"`
	UpdatedAt     int64     `json:"updatedAt"`
	PlayState     string    `json:"playState"`
	IsPlaying     bool      `json:"isPlaying"`
	IsPaused      bool      `json:"isPaused"`
	IsCued        bool      `json:"isCued"`
	IsLooping     bool      `json:"isLooping"`
	IsOnAir       *bool     `json:"isOnAir"`
	IsSynced      bool      `json:"isSynced"`
	IsTempoMaster bool      `json:"isTempoMaster"`
	TrackBpm      *float64  `json:"trackBpm"`
	Pitch         float64   `json:"pitch"`
	Tempo         *float64  `json:"tempo"`
	BeatNumber    *int      `json:"beatNumber"`
	BeatWithinBar *int      `json:"beatWithinBar"`
	Position      *Position `json:"position"`
	Track         *Track    `json:"track"`
}

// MasterInfo is GET /v1/master and the SSE "master" event (SseMaster).
type MasterInfo struct {
	Player *int     `json:"player"`
	Tempo  *float64 `json:"tempo"`
}

// SseMaster has the same shape as MasterInfo.
type SseMaster = MasterInfo

// SsePlayer is a flat Player.
type SsePlayer = Player

// WaveformPreview is GET /v1/players/{n}/waveform/preview.
type WaveformPreview struct {
	Style     string   `json:"style"`
	Segments  int      `json:"segments"`
	MaxHeight int      `json:"maxHeight"`
	Heights   []int    `json:"heights"`
	Colors    []string `json:"colors"`
}

// SseSnapshot is the first event on every stream connection.
type SseSnapshot struct {
	Players    []Player   `json:"players"`
	Master     MasterInfo `json:"master"`
	Devices    []Device   `json:"devices"`
	Online     bool       `json:"online"`
	ServerTime int64      `json:"serverTime"`
}

// BeatPosition is SseBeat.position.
type BeatPosition struct {
	Ms int64 `json:"ms"`
}

// BeatWaveform is SseBeat.waveform: the preview column at the current position.
type BeatWaveform struct {
	Height int    `json:"height"`
	Color  string `json:"color"`
}

// SseBeat is sent on every beat.
type SseBeat struct {
	Player        int           `json:"player"`
	BeatNumber    *int          `json:"beatNumber"`
	BeatWithinBar *int          `json:"beatWithinBar"`
	Tempo         *float64      `json:"tempo"`
	NextBeatInMs  *int          `json:"nextBeatInMs"`
	NextBarInMs   *int          `json:"nextBarInMs"`
	IsTempoMaster bool          `json:"isTempoMaster"`
	Position      *BeatPosition `json:"position"`
	Waveform      *BeatWaveform `json:"waveform"`
	ReceivedAt    int64         `json:"receivedAt"`
}

// SseTrack is {player, track}; Track is nil when unloaded.
type SseTrack struct {
	Player int    `json:"player"`
	Track  *Track `json:"track"`
}

// SseDevice is a device connect/disconnect. Action is connected or disconnected.
type SseDevice struct {
	Action string `json:"action"`
	Device Device `json:"device"`
}

// SseOnline reports joining or leaving the DJ Link network.
type SseOnline struct {
	Online bool `json:"online"`
}
