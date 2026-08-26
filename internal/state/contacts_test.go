package state

import (
	"path/filepath"
	"testing"
)

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

	if err := db.SetContactName("gran", "anna", "Мама"); err != nil {
		t.Fatal(err)
	}
	if err := db.SetContactName("ira", "anna", "Бабушка"); err != nil {
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
	if got := contactByLogin(t, granContacts, "anna"); got.DisplayName != "Мама" || got.DefaultDisplayName != "Анна" || got.CustomName != "Мама" {
		t.Fatalf("gran's anna = %+v, want personal name Мама", got)
	}
	if got := contactByLogin(t, iraContacts, "anna"); got.DisplayName != "Бабушка" || got.DefaultDisplayName != "Анна" || got.CustomName != "Бабушка" {
		t.Fatalf("ira's anna = %+v, want personal name Бабушка", got)
	}

	if err := db.SetContactName("gran", "anna", ""); err != nil {
		t.Fatal(err)
	}
	granContacts, err = db.ContactsForUser("gran")
	if err != nil {
		t.Fatal(err)
	}
	if got := contactByLogin(t, granContacts, "anna"); got.DisplayName != "Анна" || got.CustomName != "" {
		t.Fatalf("gran's reset anna = %+v, want default name Анна", got)
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
