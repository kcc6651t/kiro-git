package auth

import (
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"testing"
)

func TestCheckSourceIP(t *testing.T) {
	a := New(nil, []string{"10.10.0.5/32", "192.168.1.0/24"}, "")
	if err := a.CheckSourceIP("10.10.0.5:12345"); err != nil {
		t.Fatalf("expected allowed, got %v", err)
	}
	if err := a.CheckSourceIP("192.168.1.77:5000"); err != nil {
		t.Fatalf("expected allowed subnet, got %v", err)
	}
	if err := a.CheckSourceIP("10.10.0.6:12345"); err == nil {
		t.Fatal("expected rejection for disallowed IP")
	}
}

func TestCheckSourceIPAllowsAllWhenUnset(t *testing.T) {
	a := New(nil, nil, "")
	if err := a.CheckSourceIP("8.8.8.8:1"); err != nil {
		t.Fatalf("expected all sources allowed when no CIDRs, got %v", err)
	}
}

func TestCheckClientCertSubject(t *testing.T) {
	a := New([]string{"CN=file-preview-main"}, nil, "")

	good := &tls.ConnectionState{PeerCertificates: []*x509.Certificate{
		{Subject: pkix.Name{CommonName: "file-preview-main"}},
	}}
	if err := a.CheckClientCert(good); err != nil {
		t.Fatalf("expected allowed subject, got %v", err)
	}

	bad := &tls.ConnectionState{PeerCertificates: []*x509.Certificate{
		{Subject: pkix.Name{CommonName: "someone-else"}},
	}}
	if err := a.CheckClientCert(bad); err == nil {
		t.Fatal("expected rejection for wrong subject")
	}

	if err := a.CheckClientCert(&tls.ConnectionState{}); err == nil {
		t.Fatal("expected rejection when no cert presented")
	}
}

func TestCheckToken(t *testing.T) {
	a := New(nil, nil, "s3cret")
	if err := a.CheckToken("s3cret"); err != nil {
		t.Fatalf("expected valid token, got %v", err)
	}
	if err := a.CheckToken("wrong"); err == nil {
		t.Fatal("expected invalid token rejection")
	}

	noToken := New(nil, nil, "")
	if err := noToken.CheckToken(""); err != nil {
		t.Fatalf("expected no-token config to allow, got %v", err)
	}
}
