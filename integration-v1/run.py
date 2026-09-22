"""Use the real reference server, verifier, delivery and lifecycle against Android-built VPKs."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import threading

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("paravoid_reference", ROOT / "delivery/reference/server.py")
reference = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = reference
spec.loader.exec_module(reference)
output = Path(sys.argv[1])
server = reference.Server(("127.0.0.1", 0), reference.Catalog("public"))
base = f"http://127.0.0.1:{server.server_address[1]}/"
java = ["java", "-cp", str(output / "classes")]
try:
    subprocess.run(java + ["com.lelloman.paravoidandroid.contract.FullVpkFixtures",
                   str(ROOT / "compatibility/automatic-resources/build/outputs/paravoid"),
                   str(output / "fixtures"), base], check=True)
    server.catalog = reference.load_catalog(output / "fixtures/catalog-B.json")
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    flow = java + ["com.lelloman.paravoidandroid.integration.RealVpkFlow", str(output / "fixtures")]
    for step in (("init",), ("run", "1"), ("download",), ("run", "2"), ("replay",)):
        subprocess.run(flow + list(step), check=True)
    server.shutdown()
    thread.join()
    subprocess.run(flow + ["run", "2"], check=True)
    print("PASS real signed VPK: embedded A, HTTP B, staged-only update, cold B, replay rejection, offline B")
finally:
    server.server_close()
