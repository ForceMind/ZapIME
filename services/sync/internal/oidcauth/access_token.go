// SPDX-License-Identifier: GPL-3.0-only
// Package oidcauth verifies the identity proof used by the account-login flow.
package oidcauth

import (
	"crypto/rsa"
	"errors"
	"net/url"
	"slices"
	"strings"
	"time"

	"github.com/go-jose/go-jose/v4"
	"github.com/go-jose/go-jose/v4/jwt"
)

const maxAccessTokenBytes = 16 * 1024

var ErrInvalidAccessToken = errors.New("oidc_access_token_invalid")

// AccessPolicy is operator configuration, never supplied by the token.
type AccessPolicy struct {
	Issuer      string
	Audience    string
	ClientID    string
	Scope       string
	MaxLifetime time.Duration
}

func (p AccessPolicy) valid() bool {
	return httpsIdentifier(p.Issuer) && len(p.Issuer) <= 1024 && httpsIdentifier(p.Audience) &&
		visibleIdentifier(p.ClientID) && visibleIdentifier(p.Scope) && p.MaxLifetime > 0
}

// Subject is a verified issuer-scoped identity. Account membership, device
// authorization, and redemption of the token ID are separate checks.
type Subject struct {
	issuer, id, tokenID string
	expiresAt           time.Time
}

func (s Subject) Claims() (issuer, id, tokenID string, expiresAt time.Time) {
	return s.issuer, s.id, s.tokenID, s.expiresAt
}

type accessClaims struct {
	jwt.Claims
	ClientID string `json:"client_id"`
	Scope    string `json:"scope"`
}

func httpsIdentifier(value string) bool {
	parsed, err := url.Parse(value)
	return err == nil && parsed.Scheme == "https" && parsed.Hostname() != "" && parsed.User == nil &&
		parsed.RawQuery == "" && !parsed.ForceQuery && !strings.Contains(value, "#")
}

func visibleIdentifier(value string) bool {
	return value != "" && len(value) <= 255 && strings.IndexFunc(value, func(r rune) bool {
		return r < '!' || r > '~'
	}) < 0
}

// ValidSubject identifies the permitted shape of an administrator binding.
func ValidSubject(issuer, id string) bool {
	return httpsIdentifier(issuer) && len(issuer) <= 1024 && visibleIdentifier(id)
}

// VerifyAccessToken checks a compact RFC 9068 access token using an already
// trusted public key set. Header-provided keys and URLs are never consulted.
func VerifyAccessToken(policy AccessPolicy, keys jose.JSONWebKeySet, wire string, now time.Time) (Subject, error) {
	if !policy.valid() || now.IsZero() ||
		len(wire) > maxAccessTokenBytes || strings.Count(wire, ".") != 2 || strings.ContainsAny(wire, " \t\r\n") {
		return Subject{}, ErrInvalidAccessToken
	}
	token, err := jwt.ParseSigned(wire, []jose.SignatureAlgorithm{jose.RS256})
	if err != nil || len(token.Headers) != 1 {
		return Subject{}, ErrInvalidAccessToken
	}
	header := token.Headers[0]
	typ, _ := header.ExtraHeaders[jose.HeaderType].(string)
	if (typ != "at+jwt" && typ != "application/at+jwt") || !visibleIdentifier(header.KeyID) {
		return Subject{}, ErrInvalidAccessToken
	}
	matched := keys.Key(header.KeyID)
	if len(matched) != 1 {
		return Subject{}, ErrInvalidAccessToken
	}
	public := verificationKey(matched[0])
	if public == nil {
		return Subject{}, ErrInvalidAccessToken
	}
	var claims accessClaims
	if token.Claims(public, &claims) != nil ||
		claims.ValidateWithLeeway(jwt.Expected{Issuer: policy.Issuer, Time: now}, 0) != nil ||
		len(claims.Audience) != 1 || claims.Audience[0] != policy.Audience || claims.ClientID != policy.ClientID ||
		!slices.Contains(strings.Split(claims.Scope, " "), policy.Scope) ||
		!ValidSubject(claims.Issuer, claims.Subject) || !visibleIdentifier(claims.ID) || claims.Expiry == nil || claims.IssuedAt == nil {
		return Subject{}, ErrInvalidAccessToken
	}
	expires, issued := claims.Expiry.Time(), claims.IssuedAt.Time()
	if !now.Before(expires) || !issued.Before(expires) || expires.Sub(issued) > policy.MaxLifetime {
		return Subject{}, ErrInvalidAccessToken
	}
	return Subject{issuer: claims.Issuer, id: claims.Subject, tokenID: claims.ID, expiresAt: expires}, nil
}

func verificationKey(key jose.JSONWebKey) *rsa.PublicKey {
	public, ok := key.Key.(*rsa.PublicKey)
	if !ok || public == nil || public.N == nil || public.N.BitLen() < 2048 ||
		(key.Algorithm != "" && key.Algorithm != string(jose.RS256)) || (key.Use != "" && key.Use != "sig") {
		return nil
	}
	return public
}
