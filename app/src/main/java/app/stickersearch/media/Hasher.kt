package app.stickersearch.media

import java.io.InputStream
import java.security.MessageDigest

object Hasher {
    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    private fun hex(d: ByteArray): String {
        val sb = StringBuilder(d.size * 2)
        for (x in d) {
            val v = x.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
