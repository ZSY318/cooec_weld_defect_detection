package com.example.welddetect

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * 把逐帧标注后的 Bitmap 编码成 H.264 .mp4 文件。
 *
 * 输入采用 ByteBuffer 模式 + [MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible],
 * 经由 [MediaCodec.getInputImage] 按各 plane 的 rowStride/pixelStride 填充,从而同时兼容
 * planar(I420) 与 semi-planar(NV12) 编码器,避免 OpenGL/EGL 样板代码。
 *
 * 用法: 逐帧 [encode],最后调用 [finish] 收尾(写出 moov 并释放)。
 * [width]/[height] 必须为偶数;不等尺寸的帧会被缩放到该尺寸。
 */
class FrameVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    outFile: File,
) {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var frameIndex = 0L
    private val argb = IntArray(width * height)

    init {
        require(width % 2 == 0 && height % 2 == 0) { "编码尺寸必须为偶数: ${width}x$height" }
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val bitRate = (width.toLong() * height * fps / 8)
            .coerceIn(2_000_000L, 16_000_000L).toInt()
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun encode(frame: Bitmap) {
        val src = if (frame.width == width && frame.height == height) frame
        else Bitmap.createScaledBitmap(frame, width, height, true)
        src.getPixels(argb, 0, width, 0, 0, width, height)
        if (src !== frame) src.recycle()

        var index = -1
        var tries = 0
        while (index < 0 && tries < 50) {
            index = codec.dequeueInputBuffer(10_000)
            if (index < 0) { drain(false); tries++ }
        }
        if (index < 0) return // 实在拿不到输入缓冲,丢弃该帧以免卡死

        fillYuv420(codec.getInputImage(index)!!)
        val ptsUs = frameIndex * 1_000_000L / fps
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, ptsUs, 0)
        frameIndex++
        drain(false)
    }

    fun finish() {
        // 送入 EOS
        var index = -1
        var tries = 0
        while (index < 0 && tries < 50) {
            index = codec.dequeueInputBuffer(10_000)
            if (index < 0) { drain(false); tries++ }
        }
        if (index >= 0) {
            codec.queueInputBuffer(
                index, 0, 0, frameIndex * 1_000_000L / fps,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }
        drain(true)
        runCatching { codec.stop() }
        codec.release()
        if (muxerStarted) runCatching { muxer.stop() }
        muxer.release()
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return // 还没结束,暂无输出就返回
                    // 结束阶段继续等待,直到拿到 EOS
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val encoded = codec.getOutputBuffer(outIndex)!!
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0 // 编解码配置数据由 addTrack 处理,不写入
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** ARGB(argb 缓冲) -> YUV420 (BT.601 limited range),按 plane stride 写入,兼容 I420/NV12。 */
    private fun fillYuv420(image: Image) {
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val yRs = yP.rowStride; val yPs = yP.pixelStride
        val uRs = uP.rowStride; val uPs = uP.pixelStride
        val vRs = vP.rowStride; val vPs = vP.pixelStride

        for (y in 0 until height) {
            val rowBase = y * width
            for (x in 0 until width) {
                val c = argb[rowBase + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yB.put(y * yRs + x * yPs, yy.coerceIn(0, 255).toByte())
                if (y and 1 == 0 && x and 1 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val cx = x / 2; val cy = y / 2
                    uB.put(cy * uRs + cx * uPs, u.coerceIn(0, 255).toByte())
                    vB.put(cy * vRs + cx * vPs, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }
}
