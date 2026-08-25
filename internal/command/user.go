package command

import (
	"errors"
	"fmt"
	"io"

	"tinitalk/internal/state"
)

func runUser(w io.Writer, args []string) error {
	if len(args) == 0 {
		return errors.New("usage: tinitalk user add|list|disable|rotate-token")
	}
	action := args[0]
	dataDir, rest, err := parseDataDir(args[1:])
	if err != nil {
		return err
	}
	db, err := state.OpenDir(dataDir)
	if err != nil {
		return err
	}
	defer db.Close()

	switch action {
	case "add":
		if len(rest) != 2 {
			return errors.New("usage: tinitalk user add --data-dir DIR LOGIN DISPLAY_NAME")
		}
		token, err := db.AddUser(rest[0], rest[1])
		if err != nil {
			return err
		}
		_, _ = fmt.Fprintf(w, "login: %s\ntoken: %s\n", rest[0], token)
	case "list":
		if len(rest) != 0 {
			return errors.New("usage: tinitalk user list --data-dir DIR")
		}
		users, err := db.ListUsers()
		if err != nil {
			return err
		}
		for _, user := range users {
			status := "enabled"
			if user.Disabled {
				status = "disabled"
			}
			_, _ = fmt.Fprintf(w, "%s\t%s\t%s\n", user.Login, user.DisplayName, status)
		}
	case "disable":
		if len(rest) != 1 {
			return errors.New("usage: tinitalk user disable --data-dir DIR LOGIN")
		}
		if err := db.DisableUser(rest[0]); err != nil {
			return err
		}
		_, _ = fmt.Fprintf(w, "disabled: %s\n", rest[0])
	case "rotate-token":
		if len(rest) != 1 {
			return errors.New("usage: tinitalk user rotate-token --data-dir DIR LOGIN")
		}
		token, err := db.RotateToken(rest[0])
		if err != nil {
			return err
		}
		_, _ = fmt.Fprintf(w, "login: %s\ntoken: %s\n", rest[0], token)
	default:
		return fmt.Errorf("unknown user command %q", action)
	}
	return nil
}
