from pyogg import opus
import ctypes

class OpusEncoder:
    def __init__(self, rate, channels, bitrate):
        err = ctypes.c_int()
        self.enc = opus.opus_encoder_create(
            rate, channels, opus.OPUS_APPLICATION_AUDIO, ctypes.byref(err)
        )
        if err.value != 0:
            raise RuntimeError(f"opus_encoder_create failed with code {err.value}")
        opus.opus_encoder_ctl(self.enc, opus.OPUS_SET_BITRATE_REQUEST, bitrate)
        opus.opus_encoder_ctl(self.enc, opus.OPUS_SET_COMPLEXITY_REQUEST, 5)
        self.channels = channels
        self.out = (ctypes.c_ubyte * 4000)()

    def encode(self, chunk):
        frames = len(chunk) // (self.channels * 2)
        pcm = (ctypes.c_int16 * (frames * self.channels)).from_buffer_copy(chunk)
        n = opus.opus_encode(self.enc, pcm, frames, self.out, len(self.out))
        if n < 0:
            raise RuntimeError(f"opus_encode failed with code {n}")
        return bytes(self.out[:n])

    def close(self):
        opus.opus_encoder_destroy(self.enc)