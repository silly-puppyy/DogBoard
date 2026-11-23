#!/usr/bin/env python3
"""Signs the pack index, so the app only installs packs the DogBoard maintainers published (PackDownloads checks it with
the public key built into the app). ECDSA P-256 with SHA-256: verifiable on every Android version DogBoard supports
(Ed25519 needs Android 13).

    tools/i18n/sign_index.py keygen               # once: the private key -> ~/.config/dogboard/pack-signing-key.pem
    tools/i18n/sign_index.py sign DIST/index.json # writes DIST/index.json.sig (base64 DER signature)

The private key never goes into the repository or a release; back it up (a lost key means a new app release with a
new public key). keygen prints the public key (base64 DER) for PackDownloads.PUBLIC_KEY.
"""
import base64
import os
import sys

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

KEY = os.path.expanduser('~/.config/dogboard/pack-signing-key.pem')


def public_b64(key):
    return base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == 'keygen':
        if os.path.exists(KEY): raise SystemExit(f'{KEY} exists: refusing to replace a signing key')
        os.makedirs(os.path.dirname(KEY), mode=0o700, exist_ok=True)
        key = ec.generate_private_key(ec.SECP256R1())
        fd = os.open(KEY, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as f:
            f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        print(public_b64(key))
    elif len(sys.argv) == 3 and sys.argv[1] == 'sign':
        key = serialization.load_pem_private_key(open(KEY, 'rb').read(), password=None)
        data = open(sys.argv[2], 'rb').read()
        sig = key.sign(data, ec.ECDSA(hashes.SHA256()))
        open(sys.argv[2] + '.sig', 'w').write(base64.b64encode(sig).decode() + '\n')
        print('signed', sys.argv[2], 'with key', public_b64(key)[:24] + '...')
    else:
        raise SystemExit(__doc__)


if __name__ == '__main__':
    main()
