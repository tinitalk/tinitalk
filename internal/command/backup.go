package command

import (
	"errors"
	"fmt"
	"io"

	"tinitalk/internal/state"
)

func runBackup(w io.Writer, args []string) error {
	dataDir, rest, err := parseDataDir(args)
	if err != nil {
		return err
	}
	if len(rest) != 2 || rest[0] != "--out" {
		return errors.New("usage: tinitalk backup --out FILE [--data-dir DIR]")
	}
	db, err := state.OpenDir(dataDir)
	if err != nil {
		return err
	}
	defer db.Close()
	if err := db.BackupTo(rest[1]); err != nil {
		return err
	}
	_, _ = fmt.Fprintf(w, "backup: %s\n", rest[1])
	return nil
}
