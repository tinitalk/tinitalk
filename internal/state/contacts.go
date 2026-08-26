package state

import (
	"errors"
	"strings"
	"unicode/utf8"
)

type Contact struct {
	Login              string
	DisplayName        string
	DefaultDisplayName string
	CustomName         string
}

func (db *DB) ContactsForUser(owner string) ([]Contact, error) {
	ownerID, err := db.userID(owner)
	if err != nil {
		return nil, err
	}
	rows, err := db.sql.Query(`
		SELECT contact.login,
			COALESCE(uc.custom_name, contact.display_name),
			contact.display_name,
			COALESCE(uc.custom_name, '')
		FROM user_contacts uc
		JOIN users contact ON contact.id = uc.contact_user_id
		WHERE uc.owner_user_id = ? AND contact.disabled = 0
		ORDER BY COALESCE(uc.custom_name, contact.display_name) COLLATE NOCASE, contact.login
	`, ownerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var contacts []Contact
	for rows.Next() {
		var contact Contact
		if err := rows.Scan(&contact.Login, &contact.DisplayName, &contact.DefaultDisplayName, &contact.CustomName); err != nil {
			return nil, err
		}
		contacts = append(contacts, contact)
	}
	return contacts, rows.Err()
}

// SetContactName stores a personal name. An empty name restores the server name.
func (db *DB) SetContactName(owner, contact, name string) error {
	ownerID, err := db.userID(owner)
	if err != nil {
		return err
	}
	contactID, err := db.userID(contact)
	if err != nil {
		return err
	}
	name = strings.TrimSpace(name)
	if utf8.RuneCountInString(name) > 64 {
		return errors.New("contact name must be at most 64 characters")
	}
	var value any
	if name != "" {
		value = name
	}
	result, err := db.sql.Exec(`
		UPDATE user_contacts SET custom_name = ?
		WHERE owner_user_id = ? AND contact_user_id = ?
	`, value, ownerID, contactID)
	if err != nil {
		return err
	}
	return requireAffected(result, "contact not found")
}
