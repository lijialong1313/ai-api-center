"""给中间件 SQLite 库 seed 一个联调用 API Key 与一个指向假 vLLM 的后端。"""
import hashlib, sqlite3, sys, time

DB = sys.argv[1] if len(sys.argv) > 1 else "data/ai-api-center.db"
BACKEND_URL = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:59998"
PLAIN = "sk-ac-e2e-fake-0001"

conn = sqlite3.connect(DB)
now = time.strftime("%Y-%m-%d %H:%M:%S")
h = hashlib.sha256(PLAIN.encode()).hexdigest()

conn.execute("DELETE FROM api_keys WHERE key_hash=?", (h,))
conn.execute(
    "INSERT INTO api_keys(key_hash,key_prefix,label,weight,enabled,created_at,updated_at) "
    "VALUES(?,?,?,?,?,?,?)",
    (h, PLAIN[:11], "e2e-fake", 5, 1, now, now),
)
conn.execute("DELETE FROM backend_pools WHERE backend_id='e2e-gpu'")
conn.execute(
    "INSERT INTO backend_pools(backend_id,name,base_url,capacity,enabled,"
    "health_path,last_health_status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
    ("e2e-gpu", "假vLLM", BACKEND_URL, 2, 1, "/health", "UNKNOWN", now, now),
)
conn.commit()
keys = conn.execute("SELECT id,label,weight,enabled FROM api_keys").fetchall()
bps = conn.execute("SELECT id,backend_id,base_url,capacity,enabled FROM backend_pools").fetchall()
conn.close()

print("SEED_PLAIN=" + PLAIN)
print("KEYS=" + repr(keys))
print("BACKENDS=" + repr(bps))
