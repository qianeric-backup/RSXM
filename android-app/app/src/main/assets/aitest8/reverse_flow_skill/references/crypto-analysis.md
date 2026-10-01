# Cryptography Analysis

## Cryptographic constant database

### AES

```
S-box (forward, first 16 bytes):
63 7c 77 7b f2 6b 6f c5 30 01 67 2b fe d7 ab 76

Inverse S-box (first 16 bytes):
52 09 6a d5 30 36 a5 38 bf 40 a3 9e 81 f3 d7 fb

Rcon (round constants):
01 02 04 08 10 20 40 80 1b 36

MixColumns matrix:
02 03 01 01
01 02 03 01
01 01 02 03
03 01 01 02
```

### DES

```
Initial permutation (IP, first 16 entries):
58 50 42 34 26 18 10 02 60 52 44 36 28 20 12 04

Permuted Choice 1 (PC-1, first 16 entries):
57 49 41 33 25 17 09 01 58 50 42 34 26 18 10 02

Permuted Choice 2 (PC-2, first 16 entries):
14 17 11 24 01 05 03 28 15 06 21 10 23 19 12 04
```

### Hash algorithms — Initialization Vectors

```
MD5 (128-bit, little-endian):
A = 0x67452301
B = 0xEFCDAB89
C = 0x98BADCFE
D = 0x10325476

SHA-1 (160-bit, big-endian):
H0 = 0x67452301
H1 = 0xEFCDAB89
H2 = 0x98BADCFE
H3 = 0x10325476
H4 = 0xC3D2E1F0

SHA-256 (first 8 of 8, big-endian):
H0 = 0x6A09E667  (sqrt(2) fractional part)
H1 = 0xBB67AE85  (sqrt(3) fractional part)
H2 = 0x3C6EF372  (sqrt(5) fractional part)
H3 = 0xA54FF53A  (sqrt(7) fractional part)
H4 = 0x510E527F
H5 = 0x9B05688C
H6 = 0x1F83D9AB
H7 = 0x5BE0CD19

SHA-512 (first 8 of 8, 64-bit big-endian):
H0 = 0x6A09E667F3BCC908
H1 = 0xBB67AE8584CAA73B
H2 = 0x3C6EF372FE94F82B
H3 = 0xA54FF53A5F1D36F1
H4 = 0x510E527FADE682D1
H5 = 0x9B05688C2B3E6C1F
H6 = 0x1F83D9ABFB41BD6B
H7 = 0x5BE0CD19137E2179

SHA-3 / Keccak (no traditional IV; uses round constants):
RC[0]  = 0x0000000000000001
RC[1]  = 0x0000000000008082
RC[2]  = 0x800000000000808A
RC[3]  = 0x8000000080008000
...
```

### Other common constants

```
CRC-32 polynomial: 0xEDB88320 (reflected) or 0x04C11DB7 (normal)
CRC-16 polynomial: 0x8005, 0x1021 (CCITT)
CRC-16-USB: 0x8005

Blake2b IV (first 4 of 8):
IV0 = 0x6A09E667F3BCC908  (same as SHA-512 — fractional parts of sqrt)
IV1 = 0xBB67AE8584CAA73B
IV2 = 0x3C6EF372FE94F82B
IV3 = 0xA54FF53A5F1D36F1

ChaCha20 "expand 32-byte k" constant:
ASCII: "expand 32-byte k"
Hex: 0x61707865 0x3320646e 0x79622d32 0x6b206574

Salsa20 "expand 32-byte k":
ASCII: "expand 32-byte k"  (same as ChaCha20)

RC4 (no IV; state initialization 0-255 linear fill)

SM4 (Chinese national standard) S-box:
d6 90 e9 fe cc e1 3d b7 16 b6 14 c2 28 fb 2c 05 ...

TEA / XTEA:
Delta = 0x9E3779B9  (derived from golden ratio)

Blowfish:
P-array starts with fractional digits of PI (hex): 243f6a88...
S-boxes also derived from PI
```

### Base64 alphabet variants

```
Standard:   ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/
URL-safe:   ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_
Radix-64:   ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/
IMAP:       ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+,
```

## Automated crypto scanning

Use the bundled script:

```bash
# Full crypto scan
python <skill>/scripts/find_crypto.py <binary> --out <case>/crypto/

# Quick constant-only scan
python <skill>/scripts/find_crypto.py <binary> --constants-only

# High-entropy region scanner
python <skill>/scripts/find_crypto.py <binary> --entropy-threshold 7.0

# Scan for specific algorithm
python <skill>/scripts/find_crypto.py <binary> --target aes
```

### Manual constant search

```bash
# Search for AES S-box in binary
xxd <binary> | grep -i "637c 777b"
xxd <binary> | grep -i "5209 6ad5"

# Search for MD5 IV (will match SHA-1 first 4 words too)
xxd <binary> | grep -i "0123 4567 89ab cdef"

# Search for Base64 alphabet
strings <binary> | grep "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

# Search for crypto-related strings
strings <binary> | grep -iE "(encrypt|decrypt|cipher|aes|des|rsa|sha|md5|hash|key|iv|salt|seed)"
```

## Algorithm identification by behavior

### Block cipher detection patterns

| Feature | AES | DES | Blowfish | TEA/XTEA | SM4 |
|---|---|---|---|---|---|
| Block size | 16 bytes | 8 bytes | 8 bytes | 8 bytes | 16 bytes |
| Key size | 16/24/32 | 8 | 1-56 | 16 | 16 |
| Rounds | 10/12/14 | 16 | 16 | 32/64 | 32 |
| S-box | 256-byte LUT | 8 S-boxes × 64 entries | Key-dependent S-box | No S-box | 256-byte LUT |
| Key schedule | Complex expansion | PC-1/PC-2 permutations | Subkey from P-array | Simple shift/XOR | Similar to AES |

### Code patterns indicating crypto

```
Loop with XOR on byte array → likely stream cipher (RC4, ChaCha) or XOR cipher
Loop with fixed-size block + key schedule → block cipher
Large static tables (256+ bytes) → S-boxes
Repeated rounds with same structure → Feistel network or SPN
Multi-precision arithmetic loops → RSA, DH, ECC
Rotate + XOR + ADD pattern → ARX cipher (ChaCha, Salsa, Speck)
```

### Mode of operation detection

```
ECB: parallel block processing, no IV, identical plaintext → identical ciphertext blocks
CBC: XOR with previous ciphertext block, IV present
CTR: counter increment per block, nonce + counter
GCM: CTR mode + GHASH multiplication (Galois field mul in GF(2^128))
CCM: CTR + CBC-MAC
XTS: tweak value XOR'd, used for disk encryption
```

## Key and entropy analysis

### Entropy calculation

```python
import math
from collections import Counter

def shannon_entropy(data: bytes) -> float:
    if not data:
        return 0.0
    counts = Counter(data)
    total = len(data)
    return -sum((c / total) * math.log2(c / total) for c in counts.values())
```

Entropy thresholds:
- < 3.0: text, XML, JSON, structured data
- 3.0-5.0: compiled code sections (.text)
- 5.0-7.0: mixed code + data
- 7.0-7.5: suspicious (possibly encrypted without compression)
- 7.5-8.0: encrypted or compressed data, packed code

### Key material detection heuristics

- 16/24/32 bytes of high-entropy data → likely AES key
- 8 bytes of high-entropy → likely DES key
- 64 bytes → SHA-512 HMAC key
- ASCII strings that look like base64 with length multiple of 4 → encoded key material
- Hex strings of common key lengths (32/64/128 chars) → hex-encoded keys
- Memory regions labeled "key", "secret", "password", "kek", "dek", "mk"

## Weak crypto patterns (flag these)

| Pattern | Why weak | Modern minimum |
|---|---|---|
| ECB mode | Identical plaintext blocks visible | CBC/CTR/GCM |
| Static/fixed IV | Enables known-plaintext attacks | Random IV per encryption |
| DES / 3DES | 56-bit effective key, brute-forceable | AES-128 minimum |
| RC4 | Multiple known biases | ChaCha20 or AES-CTR |
| MD5 / SHA-1 for security | Collision attacks practical | SHA-256 minimum |
| Custom/homemade cipher | No peer review, almost certainly broken | Standard algorithm |
| Hardcoded key in binary | Trivially extractable | Key derivation from user input or secure storage |
| Predictable RNG (rand/srand) | Deterministic output | Cryptographically secure RNG |
| Key = password directly (no KDF) | Weak against dictionary attacks | PBKDF2/bcrypt/Argon2 |
| RSA PKCS#1 v1.5 padding | Bleichenbacher attack | OAEP padding |
| CBC with no MAC | Padding oracle attack | Authenticated encryption (GCM/CCM) |

## Public-key crypto identification

### RSA

- 1024/2048/4096-bit keys (128/256/512 bytes)
- Montgomery multiplication loops (big int arithmetic)
- Common exponent values: 3, 65537 (0x10001)
- ASN.1 key format: `30 82 ... 02 82 ...` (DER-encoded)

### ECC (Elliptic Curve)

- Much smaller keys than RSA: 256-bit ECC ≈ 3072-bit RSA
- Curves: secp256k1 (Bitcoin), secp256r1/P-256, Curve25519
- Curve25519 base point: x = 9 (small, distinctive)
- Point addition/doubling operations on large numbers

### Diffie-Hellman

- DH parameters: prime p, generator g (commonly g=2 or g=5)
- MODP groups (RFC 3526): standardized primes for DH
- ECDH: same as ECC operations, typically on P-256 or Curve25519

## Decompiler identification tips

### Ghidra

```
- Look for functions named "FUN_encrypt", "FUN_decrypt", "crypto_*"
- Data sections with high entropy near crypto functions
- Constants appearing as immediate values in crypto functions
- Use "Find possible S-box" analysis in Ghidra
- Window → Data Type Manager → find crypto-related types
```

### IDA Pro

```
- IDA "Find crypt constants" plugin (FindCrypt, signsrch)
- Look for XOR loops with 256-byte array references
- Entropy view for data sections
```

### Binary Ninja

```
- "Find Cryptographic Constants" plugin
- Entropy view in hex editor
```

## Cryptographic hash cracking (authorized targets only)

```bash
# Identify hash type by length/format
# MD5: 32 hex chars (128-bit)
# SHA-1: 40 hex chars (160-bit)
# SHA-256: 64 hex chars
# SHA-512: 128 hex chars
# bcrypt: $2a$... (60 chars)
# NTLM: 32 hex chars (uppercase) — Windows password hash

# John the Ripper
john --format=raw-md5 --wordlist=rockyou.txt hashes.txt
john --format=Raw-SHA256 --wordlist=rockyou.txt hashes.txt

# Hashcat
hashcat -m 0 -a 0 hashes.txt rockyou.txt              # MD5 dict attack
hashcat -m 0 -a 3 hashes.txt ?l?l?l?l?d?d              # MD5 brute: 4 letters + 2 digits
hashcat -m 1000 -a 0 ntlm_hashes.txt rockyou.txt       # NTLM
hashcat -m 1400 -a 0 sha256_hashes.txt rockyou.txt     # SHA-256

# Hash-identifier tool
hash-identifier <hash_string>

# Common hash formats for hashcat
# 0 = MD5, 100 = SHA1, 1400 = SHA2-256, 1700 = SHA2-512
# 1000 = NTLM, 3200 = bcrypt, 11600 = 7-Zip
# 13600 = ZIP, 13721 = VeraCrypt
```
