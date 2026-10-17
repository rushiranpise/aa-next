package uk.noammm.aamods.headunit

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLException
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

// The head unit's side of Android Auto's TLS. Ported from Headunit Revived (andreknieriem/open-headunit), AGPL-3.0.
// The head unit is the TLS client and must present a certificate: AA Mods' own (assets/headunit), which the module
// makes Android Auto trust instead of requiring a Google-issued one. The phone isn't verified: it's on the same device.
internal class Tls(context: Context) {
    private val engine: SSLEngine
    private val network: ByteBuffer
    private var plain: ByteBuffer

    init {
        val certificate = context.assets.open("headunit/cert.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val key = context.assets.open("headunit/key.pk8").use {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(it.readBytes()))
        }
        val ssl = SSLContext.getInstance("TLSv1.2")
        ssl.init(arrayOf(OwnCertificate(certificate, key)), arrayOf(TrustAll), null)
        engine = ssl.createSSLEngine().apply {
            useClientMode = true
            enabledProtocols = arrayOf("TLSv1.2")
        }
        network = ByteBuffer.allocate(engine.session.packetBufferSize)
        plain = ByteBuffer.allocate(maxOf(engine.session.applicationBufferSize, 0x10000))
    }

    // Records travel in ENCAPSULATED_SSL messages: read returns the next one's TLS bytes, write sends some
    fun handshake(read: () -> ByteArray, write: (ByteArray) -> Unit) {
        engine.beginHandshake()
        var pending = ByteArray(0)
        while (true) {
            when (engine.handshakeStatus) {
                HandshakeStatus.FINISHED, HandshakeStatus.NOT_HANDSHAKING -> return
                HandshakeStatus.NEED_TASK -> runTasks()
                HandshakeStatus.NEED_WRAP -> {
                    network.clear()
                    val result = engine.wrap(EMPTY, network)
                    if (result.status != Status.OK) throw SSLException("TLS handshake wrap: ${result.status}")
                    if (network.position() > 0) write(network.array().copyOf(network.position()))
                }
                HandshakeStatus.NEED_UNWRAP -> {
                    if (pending.isEmpty()) pending = read()
                    val input = ByteBuffer.wrap(pending)
                    plain.clear()
                    val result = engine.unwrap(input, plain)
                    when (result.status) {
                        Status.OK -> pending = ByteArray(input.remaining()).also { input.get(it) }
                        Status.BUFFER_UNDERFLOW -> pending += read() // a record split over two messages
                        else -> throw SSLException("TLS handshake unwrap: ${result.status}")
                    }
                }
                else -> throw SSLException("TLS handshake status ${engine.handshakeStatus}")
            }
        }
    }

    @Synchronized
    fun encrypt(body: ByteArray): ByteArray {
        val source = ByteBuffer.wrap(body)
        val out = ByteArrayOutputStream(body.size + 64)
        while (source.hasRemaining()) {
            network.clear()
            val result = engine.wrap(source, network)
            if (result.status != Status.OK) throw SSLException("TLS wrap: ${result.status}")
            out.write(network.array(), 0, network.position())
        }
        return out.toByteArray()
    }

    // One frame body holds one or more whole TLS records. A record that only carries TLS housekeeping gives no bytes.
    @Synchronized
    fun decrypt(cipher: ByteArray, length: Int): ByteArray {
        val source = ByteBuffer.wrap(cipher, 0, length)
        plain.clear()
        while (source.hasRemaining()) {
            val result = engine.unwrap(source, plain)
            when (result.status) {
                Status.OK -> if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) break
                Status.BUFFER_OVERFLOW -> plain = ByteBuffer.allocate(plain.capacity() * 2).also { plain.flip(); it.put(plain) }
                else -> throw SSLException("TLS unwrap: ${result.status}")
            }
            if (engine.handshakeStatus == HandshakeStatus.NEED_TASK) runTasks()
        }
        return plain.array().copyOf(plain.position())
    }

    private fun runTasks() {
        while (true) (engine.delegatedTask ?: return).run()
    }

    // Always offers AA Mods' certificate: Android Auto's certificate request names only Google's root, which a
    // standard key manager would match against and then send nothing
    private class OwnCertificate(private val certificate: X509Certificate, private val key: PrivateKey) : X509ExtendedKeyManager() {
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = key
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    }

    private companion object {
        const val ALIAS = "aamods"
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}
