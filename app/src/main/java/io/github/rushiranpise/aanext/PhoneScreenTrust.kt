package io.github.rushiranpise.aanext

import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

// Android Auto's trust manager for head units, plus AA Next's phone screen certificate: a head unit presenting exactly
// that certificate is trusted, anything else goes to Google's check as before. The certificate is also offered as an
// accepted issuer, so the phone screen's TLS client sends it when Android Auto asks for a client certificate.
class PhoneScreenTrust(private val google: X509TrustManager, private val phoneScreen: X509Certificate) : X509ExtendedTrustManager() {
    private val extended = google as? X509ExtendedTrustManager

    private fun isPhoneScreen(chain: Array<X509Certificate>?) = chain?.firstOrNull()?.encoded?.contentEquals(phoneScreen.encoded) == true

    @Throws(CertificateException::class)
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
        if (!isPhoneScreen(chain)) google.checkClientTrusted(chain, authType)
    }

    @Throws(CertificateException::class)
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, socket: Socket?) {
        if (isPhoneScreen(chain)) return
        extended?.checkClientTrusted(chain, authType, socket) ?: google.checkClientTrusted(chain, authType)
    }

    @Throws(CertificateException::class)
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) {
        if (isPhoneScreen(chain)) return
        extended?.checkClientTrusted(chain, authType, engine) ?: google.checkClientTrusted(chain, authType)
    }

    @Throws(CertificateException::class)
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = google.checkServerTrusted(chain, authType)

    @Throws(CertificateException::class)
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, socket: Socket?) {
        extended?.checkServerTrusted(chain, authType, socket) ?: google.checkServerTrusted(chain, authType)
    }

    @Throws(CertificateException::class)
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) {
        extended?.checkServerTrusted(chain, authType, engine) ?: google.checkServerTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = google.acceptedIssuers + phoneScreen
}
