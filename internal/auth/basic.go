package auth

import "tinitalk/internal/state"

type BasicAuthenticator struct {
	db *state.DB
}

func NewBasicAuthenticator(db *state.DB) *BasicAuthenticator {
	return &BasicAuthenticator{db: db}
}

func (a *BasicAuthenticator) Authenticate(login, token string) (state.User, bool, error) {
	return a.db.Authenticate(login, token)
}
