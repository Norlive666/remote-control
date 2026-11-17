import sys
from pathlib import Path

bridge_dir = Path(__file__).resolve().parent
sys.path.insert(0, str(bridge_dir / "vendor"))
sys.path.insert(0, str(bridge_dir))

from tune import main

if __name__ == "__main__":
    raise SystemExit(main())
