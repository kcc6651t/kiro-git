package auth

import (
	"crypto/subtle"
	"crypto/tls"
	"fmt"
	"net"
	"strings"
)

// Authorizer performs the Agent's second-layer authentication checks in addition
// to the TLS stack having already validated the client certificate chain.
type Authorizer struct {
	allowedSubjects []string
	allowedNets     []*net.IPNet
	apiToken        string
}

// New builds an Authorizer. Config validation rejects invalid CIDRs at startup
// (fail-closed); any still slipping through are skipped here.
func New(allowedSubjects, allowedCidrs []string, apiToken string) *Authorizer {
	nets := make([]*net.IPNet, 0, len(allowedCidrs))
	for _, c := range allowedCidrs {
		if _, n, err := net.ParseCIDR(strings.TrimSpace(c)); err == nil {
			nets = append(nets, n)
		}
	}
	return &Authorizer{
		allowedSubjects: allowedSubjects,
		allowedNets:     nets,
		apiToken:        apiToken,
	}
}

// CheckSourceIP verifies the remote address is within an allowed CIDR. When no
// CIDRs are configured, all sources are allowed (firewall is then the only gate).
func (a *Authorizer) CheckSourceIP(remoteAddr string) error {
	if len(a.allowedNets) == 0 {
		return nil
	}
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		host = remoteAddr
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return fmt.Errorf("cannot parse source IP: %s", remoteAddr)
	}
	for _, n := range a.allowedNets {
		if n.Contains(ip) {
			return nil
		}
	}
	return fmt.Errorf("source IP not allowed: %s", host)
}

// CheckClientCert verifies the verified peer certificate's subject/CN is allowed.
// The TLS handshake (with RequireAndVerifyClientCert) has already validated the
// chain against the configured client CA; this pins the identity.
func (a *Authorizer) CheckClientCert(state *tls.ConnectionState) error {
	if state == nil || len(state.PeerCertificates) == 0 {
		return fmt.Errorf("no client certificate presented")
	}
	if len(a.allowedSubjects) == 0 {
		// No explicit allow-list: any cert that passed CA validation is accepted.
		return nil
	}
	cert := state.PeerCertificates[0]
	cn := cert.Subject.CommonName
	full := cert.Subject.String()
	for _, allowed := range a.allowedSubjects {
		allowed = strings.TrimSpace(allowed)
		if strings.HasPrefix(allowed, "CN=") {
			if cn == strings.TrimPrefix(allowed, "CN=") {
				return nil
			}
		}
		if full == allowed {
			return nil
		}
	}
	return fmt.Errorf("client certificate subject not allowed: %s", full)
}

// CheckToken verifies the optional second-factor token (constant-time compare).
func (a *Authorizer) CheckToken(provided string) error {
	if a.apiToken == "" {
		return nil
	}
	if subtle.ConstantTimeCompare([]byte(a.apiToken), []byte(provided)) == 1 {
		return nil
	}
	return fmt.Errorf("invalid api token")
}

// ClientSubject returns the CN of the verified client certificate for auditing.
func ClientSubject(state *tls.ConnectionState) string {
	if state == nil || len(state.PeerCertificates) == 0 {
		return ""
	}
	return state.PeerCertificates[0].Subject.String()
}
