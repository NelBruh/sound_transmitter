import asyncio
import threading

import pystray
from PIL import Image, ImageDraw

from sound_transmitter_pc.config import PORT, log
from sound_transmitter_pc.transport import main as run_server


def _icon_image():
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.ellipse((2, 2, 62, 62), fill=(30, 136, 229, 255))
    for x, top in ((18, 24), (28, 14), (38, 20), (48, 28)):
        draw.rectangle((x, top, x + 5, 64 - top), fill=(255, 255, 255, 255))
    return image


def run():
    loop = asyncio.new_event_loop()
    task = loop.create_task(run_server())

    def serve():
        try:
            loop.run_until_complete(task)
        except asyncio.CancelledError:
            pass
        except Exception:
            log.exception("server stopped with an error")
        finally:
            icon.stop()

    def quit_app(icon, item):
        loop.call_soon_threadsafe(task.cancel)

    icon = pystray.Icon(
        "sound-transmitter",
        _icon_image(),
        "Sound Transmitter",
        menu=pystray.Menu(
            pystray.MenuItem(f"Listening on port {PORT}", None, enabled=False),
            pystray.MenuItem("Quit", quit_app),
        ),
    )
    threading.Thread(target=serve, daemon=True).start()
    icon.run()
