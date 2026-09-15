//go:build webui

package webui

import (
	"embed"
	"io/fs"
)

//go:embed dist
var bundle embed.FS

// DefaultDirectory enables the bundled client in a webui server build.
const DefaultDirectory = "embedded"

func embeddedFiles() fs.FS { files, _ := fs.Sub(bundle, "dist"); return files }
