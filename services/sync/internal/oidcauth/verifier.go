// SPDX-License-Identifier: GPL-3.0-only
package oidcauth

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"time"

	"github.com/go-jose/go-jose/v4"
)

const (
	enrollmentScope = "zapime.device_enrollment"
	maxJWKSBytes    = 64 * 1024
)

var ErrConfiguration = errors.New("oidc_configuration_invalid")

// Verifier keeps a pinned public key snapshot. Operators replace the key file
// and restart the service when the configured issuer rotates keys.
type Verifier struct {
	policy AccessPolicy
	keys   jose.JSONWebKeySet
}

func NewVerifier(policy AccessPolicy, input io.Reader) (*Verifier, error) {
	if !policy.valid() {
		return nil, ErrConfiguration
	}
	data, err := io.ReadAll(io.LimitReader(input, maxJWKSBytes+1))
	if err != nil || len(data) == 0 || len(data) > maxJWKSBytes {
		return nil, ErrConfiguration
	}
	var supplied jose.JSONWebKeySet
	if json.Unmarshal(data, &supplied) != nil {
		return nil, ErrConfiguration
	}
	var keys jose.JSONWebKeySet
	ids := make(map[string]bool)
	for _, key := range supplied.Keys {
		if !key.IsPublic() {
			return nil, ErrConfiguration
		}
		if verificationKey(key) == nil {
			continue
		}
		if !visibleIdentifier(key.KeyID) || ids[key.KeyID] {
			return nil, ErrConfiguration
		}
		ids[key.KeyID] = true
		keys.Keys = append(keys.Keys, key)
	}
	if len(keys.Keys) == 0 {
		return nil, ErrConfiguration
	}
	return &Verifier{policy: policy, keys: keys}, nil
}

// FromEnv leaves account login disabled unless all required options are set.
func FromEnv() (*Verifier, error) {
	issuer := os.Getenv("ZAPIME_OIDC_ISSUER")
	audience := os.Getenv("ZAPIME_OIDC_AUDIENCE")
	clientID := os.Getenv("ZAPIME_OIDC_CLIENT_ID")
	filename := os.Getenv("ZAPIME_OIDC_JWKS_FILE")
	if issuer == "" && audience == "" && clientID == "" && filename == "" {
		return nil, nil
	}
	if filename == "" {
		return nil, ErrConfiguration
	}
	file, err := os.Open(filename)
	if err != nil {
		return nil, ErrConfiguration
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() > maxJWKSBytes {
		return nil, ErrConfiguration
	}
	return NewVerifier(AccessPolicy{Issuer: issuer, Audience: audience, ClientID: clientID, Scope: enrollmentScope,
		MaxLifetime: 5 * time.Minute}, file)
}

func (v *Verifier) Verify(token string, now time.Time) (Subject, error) {
	return VerifyAccessToken(v.policy, v.keys, token, now)
}
