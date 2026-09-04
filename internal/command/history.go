package command

import (
	"errors"
	"fmt"
	"io"
	"time"

	"tinitalk/internal/state"
)

const historyDateLayout = "2006-01-02"

func runHistory(w io.Writer, args []string) error {
	if len(args) == 0 || args[0] != "prune" {
		return errors.New("usage: tinitalk history prune --before YYYY-MM-DD [--data-dir DIR]")
	}
	dataDir, rest, err := parseDataDir(args[1:])
	if err != nil {
		return err
	}
	if len(rest) != 2 || rest[0] != "--before" {
		return errors.New("usage: tinitalk history prune --before YYYY-MM-DD [--data-dir DIR]")
	}
	before, err := time.Parse(historyDateLayout, rest[1])
	if err != nil {
		return errors.New("--before must be a date in YYYY-MM-DD format")
	}
	db, err := state.OpenDir(dataDir)
	if err != nil {
		return err
	}
	defer db.Close()
	deleted, err := db.PruneCallHistory(before)
	if err != nil {
		return err
	}
	_, _ = fmt.Fprintf(w, "history.before: %s\nhistory.deleted: %d\n", before.Format(historyDateLayout), deleted)
	return nil
}
