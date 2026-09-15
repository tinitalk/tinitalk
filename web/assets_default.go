//go:build !webui

package webui

import "io/fs"

// DefaultDirectory leaves static serving disabled in a regular server build.
const DefaultDirectory = ""

type absentAssets struct{}

func (absentAssets) Open(name string) (fs.File, error) {
	return nil, &fs.PathError{Op: "open", Path: name, Err: fs.ErrNotExist}
}
func embeddedFiles() fs.FS { return absentAssets{} }
