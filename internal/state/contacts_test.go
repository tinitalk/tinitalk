package state

import (
	"errors"
	"path/filepath"
	"strings"
	"testing"
)

func TestNewUserDoesNotChangePersonalContactBooks(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.db")
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	for _, login := range []string{"alice", "bob"} {
		if _, err := db.AddUser(login, login); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := db.AddContact("alice", "bob", "Папа"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.AddUser("carol", "Carol"); err != nil {
		t.Fatal(err)
	}
	check := func() {
		t.Helper()
		contacts, err := db.ContactsForUser("alice")
		if err != nil || len(contacts) != 1 || contacts[0].Login != "bob" || contacts[0].DisplayName != "Папа" {
			t.Fatalf("existing book = %+v, %v; want only bob named Папа", contacts, err)
		}
		for _, login := range []string{"bob", "carol"} {
			contacts, err := db.ContactsForUser(login)
			if err != nil || len(contacts) != 0 {
				t.Fatalf("%s book = %+v, %v; want empty", login, contacts, err)
			}
		}
	}
	check()
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	check()
}

func TestPersonalContactsUseIndependentCustomNames(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	for _, user := range []struct{ login, name string }{
		{"anna", "Анна"},
		{"gran", "Галина"},
		{"ira", "Ирина"},
	} {
		if _, err := db.AddUser(user.login, user.name); err != nil {
			t.Fatal(err)
		}
	}

	if _, err := db.AddContact("gran", "anna", "Мама"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.AddContact("ira", "anna", "Бабушка"); err != nil {
		t.Fatal(err)
	}

	granContacts, err := db.ContactsForUser("gran")
	if err != nil {
		t.Fatal(err)
	}
	iraContacts, err := db.ContactsForUser("ira")
	if err != nil {
		t.Fatal(err)
	}
	if got := contactByLogin(t, granContacts, "anna"); got.DisplayName != "Мама" || got.CustomName != "Мама" {
		t.Fatalf("gran's anna = %+v, want personal name Мама", got)
	}
	if got := contactByLogin(t, iraContacts, "anna"); got.DisplayName != "Бабушка" || got.CustomName != "Бабушка" {
		t.Fatalf("ira's anna = %+v, want personal name Бабушка", got)
	}
	if name, err := db.ContactDisplayName("gran", "anna"); err != nil || name != "Мама" {
		t.Fatalf("gran's resolved anna = %q, %v, want Мама", name, err)
	}

	if err := db.SetContactName("gran", "anna", ""); !errors.Is(err, ErrInvalidContactName) {
		t.Fatalf("empty name error = %v, want ErrInvalidContactName", err)
	}
	// Older books could have no personal name. Never substitute the admin's label.
	if _, err := db.sql.Exec(`UPDATE user_contacts SET custom_name = NULL WHERE owner_user_id = (SELECT id FROM users WHERE login = 'gran')`); err != nil {
		t.Fatal(err)
	}
	granContacts, err = db.ContactsForUser("gran")
	if err != nil {
		t.Fatal(err)
	}
	if got := contactByLogin(t, granContacts, "anna"); got.DisplayName != "anna" || got.CustomName != "" {
		t.Fatalf("gran's unnamed contact = %+v, want login anna", got)
	}
	if name, err := db.ContactDisplayName("gran", "anna"); err != nil || name != "anna" {
		t.Fatalf("gran's unnamed resolved contact = %q, %v, want anna", name, err)
	}
}

func TestContactPagesDoNotSkipAfterLoadedContactIsRenamed(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	for _, user := range []struct{ login, name string }{
		{"owner", "Owner"},
		{"a", "A"},
		{"b", "B"},
		{"c", "C"},
		{"d", "D"},
		{"e", "E"},
	} {
		if _, err := db.AddUser(user.login, user.name); err != nil {
			t.Fatal(err)
		}
	}

	for _, login := range []string{"a", "b", "c", "d", "e"} {
		if _, err := db.AddContact("owner", login, strings.ToUpper(login)); err != nil {
			t.Fatal(err)
		}
	}
	first, next, err := db.ContactsPageForUser("owner", 2, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := db.SetContactName("owner", first[0].Login, "Z"); err != nil {
		t.Fatal(err)
	}
	second, _, err := db.ContactsPageForUser("owner", 2, next)
	if err != nil {
		t.Fatal(err)
	}
	if second[0].DisplayName != "C" {
		t.Fatalf("second page starts with %q, want C", second[0].DisplayName)
	}
}

func TestCallsRequireBothUsersToKeepEachOtherAsContacts(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	for _, login := range []string{"alice", "bob"} {
		if _, err := db.AddUser(login, login); err != nil {
			t.Fatal(err)
		}
	}

	if allowed, err := db.CanCall("alice", "bob"); err != nil || allowed {
		t.Fatalf("empty books allowed = %v, %v, want false", allowed, err)
	}
	for _, pair := range [][2]string{{"alice", "bob"}, {"bob", "alice"}} {
		if _, err := db.AddContact(pair[0], pair[1], pair[1]); err != nil {
			t.Fatal(err)
		}
	}
	if allowed, err := db.CanCall("alice", "bob"); err != nil || !allowed {
		t.Fatalf("mutual contacts allowed = %v, %v, want true", allowed, err)
	}
	if err := db.RemoveContact("bob", "alice"); err != nil {
		t.Fatal(err)
	}
	if allowed, err := db.CanCall("alice", "bob"); err != nil || allowed {
		t.Fatalf("one-sided contacts allowed = %v, %v, want false", allowed, err)
	}
	contact, err := db.ContactForUser("alice", "bob")
	if err != nil {
		t.Fatal(err)
	}
	if contact.CanCall {
		t.Fatalf("contact = %+v, want calls unavailable", contact)
	}
}

func contactByLogin(t *testing.T, contacts []Contact, login string) Contact {
	t.Helper()
	for _, contact := range contacts {
		if contact.Login == login {
			return contact
		}
	}
	t.Fatalf("contact %q not found in %+v", login, contacts)
	return Contact{}
}
