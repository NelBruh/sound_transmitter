import asyncio
import json

from websockets import *

from sound_transmitter_pc.audio import OpusEncoder
from sound_transmitter_pc.capturing import capture, get_format
from sound_transmitter_pc.config import BITRATE, CODEC, HOST, PORT, log


async def main():
    rate, channels = get_format()
    encoder = OpusEncoder(rate, channels, BITRATE) if CODEC == "opus" else None
    audio_config = json.dumps(
        {"rate": rate, "channels": channels, "format": "int16", "codec": CODEC}
    )

    async def handler(connection: ServerConnection):
        log.info("phone connected: %s", connection.remote_address)
        await connection.send(audio_config)
        await connection.wait_closed()
        log.info("phone disconnected: %s", connection.remote_address)

    async with serve(handler=handler,
                      host=HOST, 
                      port=PORT, 
                      ping_interval=20, 
                      ping_timeout=20) as server:
        log.info("listening on ws://%s:%s, audio format %s", HOST, PORT, audio_config)
        gen = capture()
        try:
            while True:
                chunk = await asyncio.to_thread(next, gen)
                if encoder:
                    chunk = encoder.encode(chunk)
                broadcast(server.connections, chunk)
        finally:
            log.info("stopping capture")
            try:
                gen.close()
            except ValueError:
                pass
            if encoder:
                encoder.close()
