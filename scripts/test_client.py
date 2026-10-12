import asyncio
import json
import time

from websockets.asyncio.client import connect

URL = "ws://127.0.0.1:8765"


async def main():
    async with connect(URL) as ws:
        window_bytes = 0
        window_start = time.monotonic()
        async for message in ws:
            if isinstance(message, str):
                print("config:", json.loads(message))
                continue

            window_bytes += len(message)
            now = time.monotonic()
            if now - window_start >= 1:
                print(f"{window_bytes} bytes/s")
                window_bytes = 0
                window_start = now


try:
    asyncio.run(main())
except KeyboardInterrupt:
    pass
