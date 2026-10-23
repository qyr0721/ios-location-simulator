package com.iosloc.spoofer.ios

import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.pkcs.RSAPrivateKey as Asn1RsaPrivateKey
import org.bouncycastle.asn1.pkcs.RSAPublicKey as Asn1RsaPublicKey
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.StringReader
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.Date

/**
 * Generates the root / host / device X.509 certificate triple that the lockdownd
 * Pair request expects, mirroring go-ios ios/crypto_utils.go:
 *
 *  - root : self-signed CA, SHA1withRSA, SKI extension, valid 10 years
 *  - host : signed by root, the identity this Android app presents during TLS
 *  - device : cert wrapping the iPhone's DevicePublicKey, signed by root
 */
object PairingCrypto {

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private const val TEN_YEARS_MS = 365L * 24 * 3600 * 1000 * 10

    data class IdentityBundle(
        val rootCertPem: String,
        val hostCertPem: String,
        val deviceCertPem: String,
        val rootPrivateKeyPem: String,
        val hostPrivateKeyPem: String
    )

    fun createIdentity(devicePublicKeyPem: String): IdentityBundle {
        val kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        kpg.initialize(2048)
        val rootPair = kpg.generateKeyPair()
        val hostPair = kpg.generateKeyPair()

        val now = Date()
        val notAfter = Date(now.time + TEN_YEARS_MS)
        val subject = X500Name("CN=")

        val extUtils = JcaX509ExtensionUtils()

        val rootCert = run {
            val rootInfo = SubjectPublicKeyInfo.getInstance(rootPair.public.encoded)
            val ski = extUtils.createSubjectKeyIdentifier(rootInfo)
            val builder = X509v3CertificateBuilder(
                subject, BigInteger.ZERO, now, notAfter, subject, rootInfo
            ).addExtension(Extension.subjectKeyIdentifier, false, ski)
            val signer = JcaContentSignerBuilder("SHA1withRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(rootPair.private)
            JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(builder.build(signer))
        }

        val issuerName = X500Name.getInstance(rootCert.subjectX500Principal.encoded)
        val hostCert = run {
            val hostInfo = SubjectPublicKeyInfo.getInstance(hostPair.public.encoded)
            val ski = extUtils.createSubjectKeyIdentifier(hostInfo)
            val builder = X509v3CertificateBuilder(
                issuerName, BigInteger.ONE, now, notAfter, subject, hostInfo
            ).addExtension(Extension.subjectKeyIdentifier, false, ski)
            val signer = JcaContentSignerBuilder("SHA1withRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(rootPair.private)
            JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(builder.build(signer))
        }

        // The iPhone's DevicePublicKey is a PKCS#1 "RSA PUBLIC KEY" PEM.
        val devicePublicKey = parseRsaPublicKeyFromPem(devicePublicKeyPem)
        val deviceInfo = SubjectPublicKeyInfo.getInstance(devicePublicKey.encoded)
        val deviceSki = extUtils.createSubjectKeyIdentifier(deviceInfo)
        val deviceCert = run {
            val builder = X509v3CertificateBuilder(
                issuerName, BigInteger.valueOf(2), now, notAfter, subject, deviceInfo
            ).addExtension(Extension.subjectKeyIdentifier, false, deviceSki)
            val signer = JcaContentSignerBuilder("SHA1withRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(rootPair.private)
            JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(builder.build(signer))
        }

        return IdentityBundle(
            rootCertPem = certPem(rootCert),
            hostCertPem = certPem(hostCert),
            deviceCertPem = certPem(deviceCert),
            rootPrivateKeyPem = pkcs1Pem(rootPair.private),
            hostPrivateKeyPem = pkcs1Pem(hostPair.private)
        )
    }

    private fun certPem(c: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(c.encoded) +
            "\n-----END CERTIFICATE-----\n"

    private fun pkcs1Pem(key: PrivateKey): String {
        // java.security RSA private keys are PKCS#8; the pair record expects
        // "RSA PRIVATE KEY" (PKCS#1). Convert via BouncyCastle.
        val pkcs8 = org.bouncycastle.asn1.pkcs.PrivateKeyInfo.getInstance(key.encoded)
        val rsa = Asn1RsaPrivateKey.getInstance(pkcs8.parsePrivateKey())
        val der = rsa.encoded
        return "-----BEGIN RSA PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END RSA PRIVATE KEY-----\n"
    }

    fun parseRsaPublicKeyFromPem(pem: String): RSAPublicKey {
        val der = readPemBlock(pem)
        val asn1 = Asn1RsaPublicKey.getInstance(ASN1Primitive.fromByteArray(der))
        val spec = RSAPublicKeySpec(asn1.modulus, asn1.publicExponent)
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    fun parseRsaPrivateKeyFromPem(pem: String): RSAPrivateKey {
        val der = readPemBlock(pem)
        val rsa = Asn1RsaPrivateKey.getInstance(ASN1Primitive.fromByteArray(der))
        val spec = RSAPrivateCrtKeySpec(
            rsa.modulus, rsa.publicExponent, rsa.privateExponent,
            rsa.prime1, rsa.prime2, rsa.exponent1, rsa.exponent2, rsa.coefficient
        )
        return KeyFactory.getInstance("RSA").generatePrivate(spec) as RSAPrivateKey
    }

    fun parseX509(pem: String): X509Certificate = TlsTunnel.parsePemCert(pem)

    private fun readPemBlock(pem: String): ByteArray {
        // Strip the PEM headers; BouncyCastle's PEMParser also handles it.
        if (pem.contains("-----BEGIN")) {
            val b64 = pem.lineSequence()
                .filter { !it.startsWith("-----") }
                .joinToString("")
                .trim()
            return Base64.getDecoder().decode(b64)
        }
        return Base64.getDecoder().decode(pem)
    }
}
