// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * A minimal self-signed X.509 certificate written straight to DER (R27,
 * ADR-046), so ADB wireless debugging's TLS layer can present our RSA ADB key
 * as a client certificate without pulling in a certificate library. adbd pins
 * whatever certificate we present during a successful pairing, so the same
 * certificate is reused for the later TLS connect.
 *
 * Only what adbd needs is emitted: a v3 cert, sha256WithRSAEncryption, a single
 * CN, a long validity, and the key's own SubjectPublicKeyInfo (which the JCA
 * already hands us DER-encoded).
 */
internal object X509SelfSigned {
    private val SHA256_WITH_RSA = oid(intArrayOf(1, 2, 840, 113549, 1, 1, 11))
    private val COMMON_NAME = oid(intArrayOf(2, 5, 4, 3))

    /** Builds and signs a self-signed certificate for [keyPair] with the given [commonName]. */
    fun create(keyPair: KeyPair, commonName: String = "Latch ADB", years: Int = 30): X509Certificate {
        val now = System.currentTimeMillis()
        val tbs = tbsCertificate(
            keyPair,
            commonName,
            notBefore = Date(now - 24L * 3600 * 1000),
            notAfter = Date(now + years * 365L * 24 * 3600 * 1000),
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val cert = seq(tbs + algorithmIdentifier() + bitString(signature))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(cert.inputStream()) as X509Certificate
    }

    private fun tbsCertificate(keyPair: KeyPair, commonName: String, notBefore: Date, notAfter: Date): ByteArray {
        val version = explicit(0, integer(BigInteger.valueOf(2))) // v3
        val serial = integer(BigInteger.valueOf(System.currentTimeMillis()))
        val name = rdnSequence(commonName)
        val validity = seq(time(notBefore) + time(notAfter))
        val spki = keyPair.public.encoded // already a DER SubjectPublicKeyInfo
        return seq(version + serial + algorithmIdentifier() + name + validity + name + spki)
    }

    private fun algorithmIdentifier(): ByteArray = seq(SHA256_WITH_RSA + nullValue())

    private fun rdnSequence(commonName: String): ByteArray =
        seq(set(seq(COMMON_NAME + utf8String(commonName))))

    // --- a tiny DER encoder (definite-length, enough for this certificate) ---

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        when {
            value.size < 0x80 -> out.write(value.size)
            else -> {
                val len = BigInteger.valueOf(value.size.toLong()).toByteArray().let {
                    if (it[0].toInt() == 0) it.copyOfRange(1, it.size) else it
                }
                out.write(0x80 or len.size)
                out.write(len)
            }
        }
        out.write(value)
        return out.toByteArray()
    }

    private fun seq(value: ByteArray) = tlv(0x30, value)
    private fun set(value: ByteArray) = tlv(0x31, value)
    private fun explicit(index: Int, value: ByteArray) = tlv(0xa0 or index, value)
    private fun integer(value: BigInteger) = tlv(0x02, value.toByteArray())
    private fun nullValue() = byteArrayOf(0x05, 0x00)
    private fun utf8String(s: String) = tlv(0x0c, s.toByteArray(Charsets.UTF_8))
    private fun bitString(bytes: ByteArray) = tlv(0x03, byteArrayOf(0x00) + bytes) // 0 unused bits

    /** UTCTime for years 1950–2049, GeneralizedTime from 2050 on (RFC 5280 §4.1.2.5). */
    private fun time(date: Date): ByteArray {
        val year = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            .apply { time = date }.get(java.util.Calendar.YEAR)
        return if (year < 2050) {
            tlv(0x17, formatUtc(date, "yyMMddHHmmss'Z'"))
        } else {
            tlv(0x18, formatUtc(date, "yyyyMMddHHmmss'Z'"))
        }
    }

    private fun formatUtc(date: Date, pattern: String): ByteArray {
        val format = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(date).toByteArray(Charsets.US_ASCII)
    }

    private fun oid(arcs: IntArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(arcs[0] * 40 + arcs[1])
        for (i in 2 until arcs.size) {
            var arc = arcs[i]
            val stack = ArrayDeque<Int>()
            stack.addFirst(arc and 0x7f)
            arc = arc ushr 7
            while (arc > 0) {
                stack.addFirst((arc and 0x7f) or 0x80)
                arc = arc ushr 7
            }
            stack.forEach(body::write)
        }
        return tlv(0x06, body.toByteArray())
    }
}
