// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator

/**
 * The hand-written DER must produce a certificate the standard JCA parses and
 * whose signature verifies under its own key — otherwise a phone's TLS stack
 * would reject it during pairing.
 */
class X509SelfSignedTest {
    @Test fun producesAParseableSelfSignedCertificate() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = X509SelfSigned.create(pair, commonName = "Latch Test")

        // Parses, carries our public key, and self-verifies.
        assertEquals(pair.public, cert.publicKey)
        cert.verify(pair.public) // throws if the signature is wrong
        assertTrue(cert.subjectX500Principal.name.contains("Latch Test"))
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        cert.checkValidity() // not expired / not yet valid
    }
}
