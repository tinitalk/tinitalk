package turnserver

import (
	"crypto/hmac"
	"crypto/sha1"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"strconv"
	"strings"
	"time"
)

const DefaultCredentialTTL = 10 * time.Minute

type TemporaryCredential struct {
	Username string
	Password string
	Expires  time.Time
}

type CredentialIssuer struct {
	Secret []byte
	TTL    time.Duration
	Now    func() time.Time
}

func (i CredentialIssuer) Issue(user string) TemporaryCredential {
	now := i.now()
	ttl := i.TTL
	if ttl == 0 {
		ttl = DefaultCredentialTTL
	}
	expires := now.Add(ttl).Truncate(time.Second)
	username := fmt.Sprintf("%d:%s", expires.Unix(), user)
	return TemporaryCredential{
		Username: username,
		Password: i.password(username),
		Expires:  expires,
	}
}

func (i CredentialIssuer) Valid(username, password string) bool {
	if username == "" || password == "" || !i.ValidUsername(username) {
		return false
	}
	expected := i.Password(username)
	return subtle.ConstantTimeCompare([]byte(expected), []byte(password)) == 1
}

func (i CredentialIssuer) ValidUsername(username string) bool {
	_, ok := i.Login(username)
	return ok
}

func (i CredentialIssuer) Login(username string) (string, bool) {
	expiresText, login, ok := strings.Cut(username, ":")
	if !ok || login == "" {
		return "", false
	}
	expires, err := strconv.ParseInt(expiresText, 10, 64)
	if err != nil || i.now().Unix() >= expires {
		return "", false
	}
	return login, true
}

func (i CredentialIssuer) Password(username string) string {
	return i.password(username)
}

func (i CredentialIssuer) password(username string) string {
	mac := hmac.New(sha1.New, i.Secret)
	_, _ = mac.Write([]byte(username))
	return base64.StdEncoding.EncodeToString(mac.Sum(nil))
}

func (i CredentialIssuer) now() time.Time {
	if i.Now != nil {
		return i.Now()
	}
	return time.Now()
}
