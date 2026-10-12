from queue import Empty, Queue

from pyaudiowpatch import *

from sound_transmitter_pc.config import log

CHUNK = 960


def capture():
    frames = Queue()

    def on_audio(in_data, frame_count, time_info, status):
        if status:
            log.warning("audio callback status flags: %s", status)
        frames.put(in_data)
        return None, paContinue

    with PyAudio() as p:
        device = p.get_default_wasapi_loopback()
        channels = device["maxInputChannels"]
        log.info(
            "capturing from %s (%d Hz, %d channels)",
            device["name"],
            int(device["defaultSampleRate"]),
            channels,
        )
        silence = bytes(CHUNK * channels * 2)
        with p.open(
            format=paInt16,
            channels=channels,
            rate=int(device["defaultSampleRate"]),
            input=True,
            input_device_index=device["index"],
            frames_per_buffer=CHUNK,
            stream_callback=on_audio,
        ):
            while True:
                try:
                    yield frames.get(timeout=0.05)
                except Empty:
                    yield silence


def get_format():
    with PyAudio() as p:
        device = p.get_default_wasapi_loopback()
        return int(device["defaultSampleRate"]), device["maxInputChannels"]