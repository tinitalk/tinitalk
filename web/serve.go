package webui

import (
	"io/fs"
	"net/http"
	"os"
	"path"
	"strings"
)

// WithAPI serves the client at the origin root while retaining API routing.
func WithAPI(static, api http.Handler) http.Handler {
	mux := http.NewServeMux()
	mux.Handle("/api", api)
	mux.Handle("/api/", api)
	mux.Handle("/healthz", api)
	mux.Handle("/", static)
	return mux
}

// Handler serves the same build that can be deployed to an ordinary web server.
// "embedded" selects assets included with the webui build tag.
func Handler(directory string) (http.Handler, error) {
	var files fs.FS = os.DirFS(directory)
	if directory == "embedded" {
		files = embeddedFiles()
	}
	if _, err := fs.Stat(files, "index.html"); err != nil {
		return nil, err
	}
	server := http.FileServer(http.FS(files))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" && r.Method != "HEAD" {
			http.Error(w, "method not allowed", 405)
			return
		}
		name := strings.TrimPrefix(path.Clean("/"+r.URL.Path), "/")
		if name == "" {
			name = "index.html"
		}
		if strings.HasPrefix(name, ".") {
			http.NotFound(w, r)
			return
		}
		if _, err := fs.Stat(files, name); err != nil {
			// Navigation uses URL fragments; missing paths must remain 404.
			http.NotFound(w, r)
			return
		}
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Cache-Control", "no-cache")
		if path.Ext(name) == ".webmanifest" {
			w.Header().Set("Content-Type", "application/manifest+json")
		}
		if strings.HasPrefix(name, "assets/") {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		}
		server.ServeHTTP(w, r)
	}), nil
}
