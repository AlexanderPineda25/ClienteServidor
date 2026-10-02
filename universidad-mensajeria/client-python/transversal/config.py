import os

class Config:
    def __init__(self, filename="client.properties"):
        self.filename = filename
        self.props = {}
        if os.path.exists(filename):
            with open(filename, 'r', encoding='utf-8') as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith('#') and '=' in line:
                        key, val = line.split('=', 1)
                        self.props[key.strip()] = val.strip()

    def get(self, key, default=None):
        return self.props.get(key, default)

    def set(self, key, value):
        self.props[key] = str(value)

    def save(self):
        try:
            with open(self.filename, 'w', encoding='utf-8') as f:
                f.write("# client.properties (host · puerto · db.url · idioma — §5.2)\n")
                for key in ("host", "puerto", "db.url", "idioma"):
                    if key in self.props:
                        f.write(f"{key}={self.props[key]}\n")
        except OSError:
            pass
