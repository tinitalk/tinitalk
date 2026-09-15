package webui

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestRootClientPreservesAPIRoutes(t *testing.T) {
	static := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Static-Path", r.URL.Path)
		w.WriteHeader(http.StatusOK)
	})
	api := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	})
	h := WithAPI(static, api)
	for _, path := range []string{"/", "/manifest.webmanifest", "/shell-worker.js", "/push-worker.js"} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("GET", path, nil))
		if w.Code != http.StatusOK {
			t.Fatalf("client %s: %d", path, w.Code)
		}
	}
	for _, path := range []string{"/api", "/api/contacts", "/api/browser/socket", "/api/unknown", "/healthz"} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("GET", path, nil))
		if w.Code != http.StatusUnauthorized {
			t.Fatalf("API %s reached static handler: %d", path, w.Code)
		}
	}
}

func TestStaticClientAtRootAndMissingPaths(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "index.html"), []byte("<h1>TiniTalk</h1>"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "push-worker.js"), []byte("/* worker */"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "manifest.webmanifest"), []byte(`{"name":"TiniTalk"}`), 0600); err != nil {
		t.Fatal(err)
	}
	h, err := Handler(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		path   string
		status int
	}{{"/", 200}, {"/contacts", 404}, {"/missing.js", 404}, {"/push-worker.js", 200}, {"/manifest.webmanifest", 200}, {"/app/", 404}, {"/app/push-worker.js", 404}} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("GET", tc.path, nil))
		if w.Code != tc.status {
			t.Fatalf("%s: %d", tc.path, w.Code)
		}
		if tc.path == "/push-worker.js" && w.Header().Get("Cache-Control") != "no-cache" {
			t.Fatal("worker cannot be cached indefinitely")
		}
		if tc.path == "/manifest.webmanifest" && w.Header().Get("Content-Type") != "application/manifest+json" {
			t.Fatal("manifest content type must not depend on host MIME registry")
		}
	}
}
