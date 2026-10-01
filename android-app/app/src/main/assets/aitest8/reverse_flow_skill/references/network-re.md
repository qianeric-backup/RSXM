# Network Protocol Reverse Engineering

## PCAP / Traffic analysis entry points

### First-pass triage

```bash
# Quick PCAP stats
capinfos capture.pcap
capinfos capture.pcapng

# Protocol hierarchy
tshark -r capture.pcap -q -z io,phs

# Endpoint statistics
tshark -r capture.pcap -q -z endpoints,ip
tshark -r capture.pcap -q -z conv,tcp

# Extract HTTP objects
tshark -r capture.pcap --export-objects http,./http_objects/

# Follow TCP stream
tshark -r capture.pcap -q -z follow,tcp,ascii,0
```

### Wireshark quick filters

```
tcp.stream eq 0                    # Follow stream 0
tcp.flags.syn == 1 && tcp.flags.ack == 0  # TCP SYN packets (connection start)
tcp.analysis.retransmission        # Retransmissions (packet loss / middlebox issues)
tcp.len > 0                        # Only packets with payload
http.request or http.response      # HTTP traffic
dns or mdns                        # DNS/DNS-SD traffic
tls.handshake.type == 1            # TLS Client Hello (SNI extraction)
data.data contains <hex_bytes>     # Binary payload search
frame contains "<string>"          # ASCII search in raw packet
ip.src == 192.168.1.100 && tcp.port == 443
```

## Binary protocol analysis methodology

### Step 1: Identify message boundaries

```
Indicators of message framing:
- Fixed-length header with a length field
- Magic bytes / sync word at message start
- Consistent message sizes in histogram
- Specific delimiters (e.g., \r\n, 0x00, 0x0d0a)
- TCP PSH flag patterns (application-level message boundaries often align with PSH)
```

```python
# Analyze message sizes from PCAP
from collections import Counter
# For each TCP stream, extract payload sizes
sizes = Counter()
for pkt in stream_packets:
    if hasattr(pkt, 'tcp') and pkt.tcp.payload:
        sizes[len(pkt.tcp.payload)] += 1
print(sizes.most_common(20))  # Top 20 message sizes
```

### Step 2: Find structure fields

```
Common fields in order:
1. Magic/Sync bytes (4 bytes common: 0xDEADBEEF, name, etc.)
2. Protocol version (1-4 bytes)
3. Message type / command ID (1-4 bytes)
4. Total length / payload length (2-4 bytes)
5. Sequence number / request ID (2-4 bytes)
6. Flags / options bitmask (1-4 bytes)
7. Checksum/CRC (2-4 bytes, often at end of header)
8. Payload
```

### Step 3: Diff similar messages

```
Technique: collect N messages of same type, XOR compare:
- Fields that are identical → static (magic, version, type)
- Fields that increment by 1 → sequence/counter
- Fields that differ by small amounts → length fields
- Fields that differ completely → payload/data/hash
- Fields that are always 0 → reserved/padding
```

```python
# XOR-based field diffing
def diff_fields(msgs: list[bytes]) -> dict[int, set[int]]:
    if len(msgs) < 2:
        return {}
    diffs = {}
    min_len = min(len(m) for m in msgs)
    for i in range(min_len):
        vals = {m[i] for m in msgs}
        diffs[i] = vals
    return diffs

# Classify each byte position:
# len(vals)==1 → constant field
# len(vals) in (2,3) → likely flag/enum/version
# vals are sequential → likely counter
# vals represent small int → length field candidate
```

### Step 4: Field type inference

```python
def infer_field_type(byte_pos: int, values: list[bytes]) -> str:
    """Infer field type at byte position from sample values."""
    # Try as single byte
    b_vals = [m[byte_pos] for m in values if len(m) > byte_pos]
    
    # Try as uint16 BE/LE
    u16be_vals = [int.from_bytes(m[byte_pos:byte_pos+2], 'big') 
                  for m in values if len(m) >= byte_pos+2]
    
    # Heuristic: if u16be matches total message length → length field (BE u16)
    for v, m in zip(u16be_vals, values):
        if v == len(m):
            return f"uint16_be — matches total message length"
        if v == len(m) - byte_pos - 2:
            return f"uint16_be — matches remaining payload"
    
    # Try as uint32 BE/LE for same heuristics
    # ...
    
    # If values are small (0-255) → likely single byte field
    if all(0 <= v <= 255 for v in b_vals):
        if len(set(b_vals)) <= 4:
            return f"enum/flag ({set(b_vals)})"
    
    return "unknown"
```

### Step 5: State machine inference

```
Method: extract (type, direction) pairs per stream, build transition graph

Stream 1: C→S [type=0x01]  S→C [type=0x81]  C→S [type=0x03]  S→C [type=0x83]
Stream 2: C→S [type=0x01]  S→C [type=0x81]  C→S [type=0x05]  S→C [type=0x85]
...

Observed patterns:
- Type 0x01 always starts conversation → likely "Hello"/"Connect"
- Type 0x81 always follows 0x01 → likely "Hello Response"
- Type 0x03/0x05 appear after 0x81 → likely "Command" variants
- Server never sends before first client 0x01 → client-initiated protocol
```

## Common protocol patterns

### Request-Response

```
Client: [magic][type][length][seq][payload]
Server: [magic][type][length][seq][status][payload]

Where:
- type matches between request and response (or response = request | 0x80)
- seq is echoed back
- status byte: 0x00=success, 0x01-0xFF = error codes
- payload can be empty for ACK responses
```

### Publish-Subscribe / Push

```
Server: [magic][type][length][channel_id][payload]

Client may send subscribe message first, then server pushes:
- No request/response pairing
- Sequence numbers monotonic per channel
- Keep-alive / heartbeat messages interleaved
```

### Streaming / Chunked

```
Header: [magic][type][total_length][chunk_index][chunk_count][chunk_data]
Footer: [magic][type][checksum]

Reassembly: collect all chunks by (stream_id, chunk_index), validate checksum
```

## Encoding identification

### Common encodings and their fingerprints

| Encoding | Detection |
|---|---|
| Base64 | `[A-Za-z0-9+/]{4,}={0,2}` with length multiple of 4 |
| Hex | `[0-9a-fA-F]{2,}` even-length |
| Protobuf | Varint-heavy, field: `(field_number << 3) | wire_type`, small tags (0x08, 0x12, 0x1a) |
| MessagePack | 0x80-0x8f (fixmap), 0x90-0x9f (fixarray), 0xa0-0xbf (fixstr) |
| BSON | Starts with int32 total length, field: `\x00` type + name + value |
| MsgPack/BSON | First-byte pattern: 0x80-0x9f range prominent |
| JSON | Starts with `{` or `[`, contains `"key":` patterns |
| XML | Starts with `<` or contains `<?xml` |
| gzip/zlib | 0x1f 0x8b (gzip), 0x78 0x9c / 0x78 0x01 / 0x78 0xda (zlib) |
| LZ4 | 0x04 0x22 0x4d 0x18 (magic, frame format) |
| TLV (Type-Length-Value) | Repeating pattern: 1-2 byte type, 1-4 byte length, variable value |
| ASN.1 DER | SEQUENCE (0x30), INTEGER (0x02), OID (0x06), OCTET STRING (0x04) |

### Protobuf wire types

```
0 = Varint (int32, int64, uint32, uint64, sint32, sint64, bool, enum)
1 = 64-bit (fixed64, sfixed64, double)
2 = Length-delimited (string, bytes, embedded messages, packed repeated fields)
3 = Start group (deprecated)
4 = End group (deprecated)
5 = 32-bit (fixed32, sfixed32, float)

Tool: protoc --decode_raw < captured_protobuf.bin
```

## TLS/SSL traffic analysis

### TLS fingerprinting

```bash
# Extract JA3/JA4 fingerprints from Client Hello
tshark -r capture.pcap -Y "tls.handshake.type == 1" -T fields -e tls.handshake.ja3

# SNI (Server Name Indication) extraction
tshark -r capture.pcap -Y "tls.handshake.extensions_server_name" -T fields -e tls.handshake.extensions_server_name

# Certificate extraction
tshark -r capture.pcap -Y "tls.handshake.certificate" -T fields -e tls.handshake.certificate

# Supported cipher suites
tshark -r capture.pcap -Y "tls.handshake.ciphersuite" -T fields -e tls.handshake.ciphersuite
```

### SSLKEYLOGFILE approach

```bash
# If you have SSLKEYLOGFILE from the endpoint:
export SSLKEYLOGFILE=/path/to/sslkeys.log
# Wireshark: Edit → Preferences → Protocols → TLS → (Pre)-Master-Secret log filename
tshark -r capture.pcap -o tls.keylog_file:sslkeys.log
```

## DNS analysis

```bash
# Query types distribution
tshark -r capture.pcap -q -z dns,tree

# Extract all queried domains
tshark -r capture.pcap -Y "dns.flags.response == 0" -T fields -e dns.qry.name | sort -u

# DNS tunneling detection (long TXT/MX records, high entropy subdomains)
tshark -r capture.pcap -Y "dns.qry.name matches \"[A-Za-z0-9+/]{30,}\""

# Fast flux detection (many A records with short TTL for same domain)
tshark -r capture.pcap -Y "dns.flags.response == 1" -T fields -e dns.qry.name -e dns.a -e dns.ttl
```

## C2 pattern detection

### Beaconing detection

```
Key indicators of C2 beaconing:
- Regular intervals (fixed period or jittered)
- Small, consistent payload sizes
- Similar request patterns to same endpoint
- Heartbeat-style messages with minimal content
- HTTP POST with small, encrypted/encoded body
- DNS queries with encoded subdomains (DNS tunneling)

Detection via timing analysis:
- Compute inter-arrival time for packets to same destination
- Fixed interval → automated beacon
- Jittered but bounded → sophisticated beacon
```

```python
# Simple beacon detection
def detect_beacon(timestamps: list[float], threshold: float = 0.1) -> bool:
    """Detect if timestamps show regular beaconing pattern."""
    if len(timestamps) < 4:
        return False
    intervals = [timestamps[i+1] - timestamps[i] for i in range(len(timestamps)-1)]
    mean = sum(intervals) / len(intervals)
    variance = sum((i - mean) ** 2 for i in intervals) / len(intervals)
    # Low coefficient of variation → regular interval
    cv = (variance ** 0.5) / mean if mean > 0 else float('inf')
    return cv < threshold
```

## Custom protocol reverse checklist

```
□ Collect raw payloads for each message type
□ Align messages by identified magic/type bytes
□ Create byte-by-byte diff table across samples
□ Identify length fields (try uint8/16/32, big and little endian)
□ Identify type/command fields
□ Identify sequence/correlation IDs
□ Identify flag/bitmask fields
□ Identify checksum/CRC fields (try CRC-16, CRC-32, Adler-32, Fletcher, XOR sum)
□ Map state transitions (generated from type+direction pairs)
□ Document field table: offset, length, name, type, constraints, evidence
□ Write a simple parser (Python struct) to validate understanding
□ Fuzz unknown fields and observe behavior changes
```

### Checksum/CRC identification

```python
import struct

def try_checksum(data: bytes, known_checksum: int, checksum_bytes: int) -> list[str]:
    """Try common checksum algorithms and return matches."""
    data_without_crc = data[:-checksum_bytes]
    matches = []
    
    # XOR sum
    xor_sum = 0
    for b in data_without_crc:
        xor_sum ^= b
    if xor_sum == known_checksum:
        matches.append(f"XOR sum = {hex(xor_sum)}")
    
    # Modulo-256 sum
    if sum(data_without_crc) % 256 == known_checksum:
        matches.append(f"Modulo-256 sum")
    
    # CRC-16 variants
    import binascii
    for name in ['crc-16', 'crc-16-buypass', 'crc-16-modbus', 'crc-ccitt-zero']:
        try:
            crc = binascii.crc_hqx(data_without_crc, 0xFFFF)  # varies by variant
            if crc == known_checksum:
                matches.append(name)
        except:
            pass
    
    # CRC-32
    crc32 = binascii.crc32(data_without_crc) & 0xFFFFFFFF
    if crc32 == known_checksum:
        matches.append("CRC-32")
    
    # Adler-32
    a, b = 1, 0
    for byte in data_without_crc:
        a = (a + byte) % 65521
        b = (b + a) % 65521
    adler32 = (b << 16) | a
    if adler32 == known_checksum:
        matches.append("Adler-32")
    
    return matches
```
