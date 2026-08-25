package state

import (
	"database/sql"
	"errors"
)

func requireAffected(result sql.Result, message string) error {
	n, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if n == 0 {
		return errors.New(message)
	}
	return nil
}
