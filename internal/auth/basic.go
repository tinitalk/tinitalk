package auth

import "tinitalk/internal/state"

type BasicAuthenticator struct {
	db *state.DB
}

func NewBasicAuthenticator(db *state.DB) *BasicAuthenticator {
	return &BasicAuthenticator{db: db}
}

func (a *BasicAuthenticator) Authenticate(login, token string) (state.User, bool) {
	user, ok, err := a.db.Authenticate(login, token)
	if err != nil {
		return state.User{}, false
	}
	return user, ok
}
