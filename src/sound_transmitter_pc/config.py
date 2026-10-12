import logging
import sys
from pathlib import Path

HOST="0.0.0.0"
PORT=8765
CODEC="opus"
BITRATE=64000

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(module)s: %(message)s",
    filename=(
        Path(sys.executable).with_name("sound-transmitter.log")
        if getattr(sys, "frozen", False)
        else None
    ),
)
log = logging.getLogger("sound_transmitter")