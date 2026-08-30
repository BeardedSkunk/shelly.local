package shelly.local.data.api

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

class ProgressRequestBody(
    private val bytes: ByteArray,
    private val mediaType: MediaType?,
    private val onProgress: (Int) -> Unit,
) : RequestBody() {
    override fun contentType() = mediaType
    override fun contentLength() = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        val total     = bytes.size.toLong()
        var written   = 0L
        var offset    = 0
        val chunkSize = 8192
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            sink.write(bytes, offset, end - offset)
            written += (end - offset)
            offset   = end
            onProgress(((written * 100) / total).toInt())
        }
    }
}
