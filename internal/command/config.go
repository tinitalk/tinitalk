package command

import (
	"bytes"
	"io"
)

func Run(w io.Writer, args ...string) error {
	_, err := run(w, args)
	return err
}

func RunResult(args ...string) (string, error) {
	var out bytes.Buffer
	err := Run(&out, args...)
	return out.String(), err
}
