import os

class Config:
    def __init__(self, filename="client.properties"):
        self.props = {}
        if os.path.exists(filename):
            with open(filename, 'r', encoding='utf-8') as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith('#'):
                        key, val = line.split('=', 1)
                        self.props[key.strip()] = val.strip()

    def get(self, key, default=None):
        return self.props.get(key, default)
