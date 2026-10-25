// Command cdj-tui is a terminal view of a cdj-music-link server.
package main

import (
	"flag"
	"fmt"
	"os"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
	"github.com/silmin/cdj-music-link/tui/internal/api"
	"github.com/silmin/cdj-music-link/tui/internal/ui"
)

func main() {
	def := os.Getenv("CDJ_BASE")
	if def == "" {
		def = "http://localhost:17080"
	}
	base := flag.String("base", def, "cdj-music-link base URL (env CDJ_BASE)")
	flag.Usage = func() {
		fmt.Fprintf(flag.CommandLine.Output(), "usage: cdj-tui [--base URL]\n\nkeys: q quit, b beat blink, w waveform rows\n\n")
		flag.PrintDefaults()
	}
	flag.Parse()

	c := api.NewClient(*base)
	snap, err := c.Snapshot()
	if err != nil {
		fmt.Fprintf(os.Stderr, "cdj-tui: %s: %v\n", c.Base, err)
		os.Exit(1)
	}
	// Query the background colour before bubbletea owns the terminal.
	lipgloss.HasDarkBackground()

	m := ui.New(c, snap)
	if _, err := tea.NewProgram(m, tea.WithAltScreen()).Run(); err != nil {
		fmt.Fprintf(os.Stderr, "cdj-tui: %v\n", err)
		os.Exit(1)
	}
}
