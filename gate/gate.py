#!/usr/bin/env python3
"""Release gate: compares what an APK can do with gate/baseline.json (schema 2, with the gate 2.1 sections).

Written for DiscoRedact from the contract in the program's gate-baseline spec, section 2 (Prikey's reference
gate/ could not be read from this repo's session). Python 3 standard library only.

  gate.py generate    --apk APK --deps LOG [--baseline B] [--out FILE] [--require-tools]
  gate.py check       --apk APK --deps LOG [--baseline B] [--generated FILE] [--require-tools] [--require-pin] [--compare-to BASE]
  gate.py cert        --apk APK [--baseline B] [--require-pin]
  gate.py inspect     --apk APK [--require-tools]
  gate.py fingerprint --apk APK

Exit codes: 0 match, 1 differences (or a missing pin under --require-pin, or a failed certificate check),
2 the check could not run. Exit 2 is never a pass.
"""

import argparse
import fnmatch
import hashlib
import io
import json
import os
import re
import struct
import subprocess
import sys
import zipfile

SCHEMA = 2
MB = 1024 * 1024
CAP_ENTRY = 256 * MB
CAP_TOTAL = 1024 * MB
CAP_STRINGS = 64 * MB
MAX_DEPTH = 3
NONE32 = 0xFFFFFFFF
ANDROID_NS = "http://schemas.android.com/apk/res/android"


class GateError(Exception):
    """The check could not run (exit 2)."""


def fail(msg):
    raise GateError(msg)


# Framework attribute IDs (public), for the attributes the gate reads. An attribute is identified by its ID,
# whatever its namespace; a name that disagrees with its known ID is refused.
ATTR_IDS = {
    "theme": 0x01010000, "label": 0x01010001, "icon": 0x01010002, "name": 0x01010003,
    "permission": 0x01010006, "readPermission": 0x01010007, "writePermission": 0x01010008,
    "protectionLevel": 0x01010009, "sharedUserId": 0x0101000B, "enabled": 0x0101000E,
    "debuggable": 0x0101000F, "exported": 0x01010010, "authorities": 0x01010018,
    "grantUriPermissions": 0x0101001B, "value": 0x01010024, "resource": 0x01010025,
    "mimeType": 0x01010026, "scheme": 0x01010027, "host": 0x01010028, "port": 0x01010029,
    "path": 0x0101002A, "pathPrefix": 0x0101002B, "pathPattern": 0x0101002C,
    "minSdkVersion": 0x0101020C, "versionCode": 0x0101021B, "versionName": 0x0101021C,
    "targetSdkVersion": 0x01010270, "maxSdkVersion": 0x01010271, "backupAgent": 0x0101027F,
    "allowBackup": 0x01010280, "glEsVersion": 0x01010281, "required": 0x0101028E,
    "fullBackupContent": 0x010104EB, "usesCleartextTraffic": 0x010104EC,
    "networkSecurityConfig": 0x01010527, "foregroundServiceType": 0x01010599,
    "dataExtractionRules": 0x0101063E, "usesPermissionFlags": 0x01010644,
}
ID_TO_ATTR = {v: k for k, v in ATTR_IDS.items()}

T_NULL, T_REFERENCE, T_ATTRIBUTE, T_STRING = 0x00, 0x01, 0x02, 0x03
T_DYNAMIC_REFERENCE = 0x07
T_INT_DEC, T_INT_HEX, T_INT_BOOLEAN = 0x10, 0x11, 0x12
NEVER_FOR_LOCATION = 0x10000


# ---------------------------------------------------------------------------------------------------------------------
# Binary helpers

def u8(b, o):
    if o < 0 or o + 1 > len(b):
        fail("malformed input: truncated data")
    return b[o]


def u16(b, o):
    if o < 0 or o + 2 > len(b):
        fail("malformed input: truncated data")
    return struct.unpack_from("<H", b, o)[0]


def u32(b, o):
    if o < 0 or o + 4 > len(b):
        fail("malformed input: truncated data")
    return struct.unpack_from("<I", b, o)[0]


def u64(b, o):
    if o < 0 or o + 8 > len(b):
        fail("malformed input: truncated data")
    return struct.unpack_from("<Q", b, o)[0]


def chunk_header(b, pos, end):
    ctype, hsz, size = u16(b, pos), u16(b, pos + 2), u32(b, pos + 4)
    if hsz < 8 or size < 8 or hsz > size or pos + size > end:
        fail("malformed input: chunk at %d does not fit its parent" % pos)
    return ctype, hsz, size


def parse_string_pool(b, pos, end):
    ctype, hsz, size = chunk_header(b, pos, end)
    if ctype != 0x0001 or hsz < 28:
        fail("malformed input: not a string pool")
    count, _styles, flags, sstart = u32(b, pos + 8), u32(b, pos + 12), u32(b, pos + 16), u32(b, pos + 20)
    if count > (size // 4):
        fail("malformed input: string count")
    utf8 = flags & 0x100
    base = pos + sstart
    chunk_end = pos + size
    cache = {}
    decoded = 0
    out = []
    for i in range(count):
        off = u32(b, pos + hsz + 4 * i)
        if off in cache:
            out.append(cache[off])
            continue
        p = base + off
        if p >= chunk_end:
            fail("malformed input: string offset")
        if utf8:
            n = u8(b, p)
            p += 1
            if n & 0x80:
                p += 1
            blen = u8(b, p)
            p += 1
            if blen & 0x80:
                blen = ((blen & 0x7F) << 8) | u8(b, p)
                p += 1
            if p + blen > chunk_end:
                fail("malformed input: string length")
            s = bytes(b[p:p + blen]).decode("utf-8", "replace")
            decoded += blen
        else:
            n = u16(b, p)
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | u16(b, p)
                p += 2
            if p + 2 * n > chunk_end:
                fail("malformed input: string length")
            s = bytes(b[p:p + 2 * n]).decode("utf-16-le", "replace")
            decoded += 2 * n
        if decoded > CAP_STRINGS:
            fail("string pool larger than the 64 MB cap")
        cache[off] = s
        out.append(s)
    return out


# ---------------------------------------------------------------------------------------------------------------------
# Compiled XML

class Attr:
    __slots__ = ("raw", "dtype", "data")

    def __init__(self, raw, dtype, data):
        self.raw, self.dtype, self.data = raw, dtype, data


class Elem:
    def __init__(self, tag, depth):
        self.tag, self.depth = tag, depth
        self.attrs = {}
        self.nodes = []  # Elem or ("#text", str)

    def children(self, tag=None):
        return [n for n in self.nodes if isinstance(n, Elem) and (tag is None or n.tag == tag)]


def attr_key(name, ns, resid):
    if resid in ID_TO_ATTR:
        known = ID_TO_ATTR[resid]
        if name != known:
            fail("malformed input: attribute %r carries the resource ID of android:%s" % (name, known))
        return "android:" + name
    if ns == ANDROID_NS:
        if name in ATTR_IDS:
            fail("malformed input: android:%s has a missing or different resource ID" % name)
        return "android:" + name
    if not ns:
        return name
    return "{%s}%s" % (ns, name)


def decode_axml(b):
    if len(b) < 8 or u16(b, 0) != 0x0003:
        fail("malformed input: not compiled XML")
    _t, hsz, size = chunk_header(b, 0, len(b))
    strings, resmap = [], []

    def S(i):
        if i == NONE32:
            return None
        if i >= len(strings):
            fail("malformed input: string index")
        return strings[i]

    pos, stack, root = hsz, [], None
    while pos < size:
        ctype, chsz, csize = chunk_header(b, pos, size)
        if ctype == 0x0001:
            strings = parse_string_pool(b, pos, size)
        elif ctype == 0x0180:
            resmap = [u32(b, pos + chsz + 4 * i) for i in range((csize - chsz) // 4)]
        elif ctype == 0x0102:
            ext = pos + chsz
            name = S(u32(b, ext + 4))
            astart, asize, acount = u16(b, ext + 8), u16(b, ext + 10), u16(b, ext + 12)
            if name is None or asize < 20 or ext + astart + asize * acount > pos + csize:
                fail("malformed input: element header")
            el = Elem(name, len(stack))
            for i in range(acount):
                a = ext + astart + i * asize
                ns_i, name_i, raw_i = u32(b, a), u32(b, a + 4), u32(b, a + 8)
                dtype, data = u8(b, a + 15), u32(b, a + 16)
                aname = S(name_i)
                if aname is None:
                    fail("malformed input: attribute without a name")
                resid = resmap[name_i] if name_i < len(resmap) and resmap[name_i] else None
                key = attr_key(aname, S(ns_i), resid)
                if key in el.attrs:
                    fail("malformed input: element <%s> has two attributes that read as %s" % (name, key))
                raw = S(raw_i)
                if dtype == T_STRING and (raw is None or S(data) != raw):
                    fail("malformed input: string attribute %s without a matching raw copy" % key)
                if dtype in (T_REFERENCE, T_DYNAMIC_REFERENCE) and raw is not None:
                    fail("malformed input: reference attribute %s also carries a raw string" % key)
                el.attrs[key] = Attr(raw, dtype, data)
            if stack:
                stack[-1].nodes.append(el)
            elif root is None:
                root = el
            else:
                fail("malformed input: two root elements")
            stack.append(el)
        elif ctype == 0x0103:
            if not stack:
                fail("malformed input: unbalanced end element")
            stack.pop()
        elif ctype == 0x0104:
            text = (S(u32(b, pos + chsz)) or "").strip()
            if text and stack:
                stack[-1].nodes.append(("#text", text))
        pos += csize
    if root is None or stack:
        fail("malformed input: compiled XML without a complete root element")
    return root


def value_text(a):
    """Canonical attribute value (frozen form of gate 2.1)."""
    if a.raw is not None:
        return a.raw
    if a.dtype == T_INT_BOOLEAN:
        return "true" if a.data else "false"
    if a.dtype in (T_INT_DEC, T_INT_HEX):
        return str(a.data)
    if a.dtype in (T_REFERENCE, T_DYNAMIC_REFERENCE):
        return None
    return "(type 0x%02x)0x%08x" % (a.dtype, a.data)


def canonical_lines(root):
    lines = []

    def walk(el):
        attrs = [[k, value_text(el.attrs[k])] for k in sorted(el.attrs)]
        lines.append(json.dumps([el.depth, el.tag, attrs], ensure_ascii=True, separators=(",", ":")))
        for n in el.nodes:
            if isinstance(n, Elem):
                walk(n)
            else:
                lines.append(json.dumps([el.depth + 1, "#text", [["#text", n[1]]]], ensure_ascii=True, separators=(",", ":")))

    walk(root)
    return lines


def get_bool(el, key):
    a = el.attrs.get(key)
    if a is None:
        return None
    if a.dtype != T_INT_BOOLEAN:
        fail("%s on <%s> is not a literal boolean" % (key, el.tag))
    return bool(a.data)


def get_int(el, key, allow_string=False):
    a = el.attrs.get(key)
    if a is None:
        return None
    if a.dtype in (T_INT_DEC, T_INT_HEX):
        return a.data
    if allow_string and a.dtype == T_STRING:
        return int(a.raw) if a.raw.isdigit() else a.raw
    fail("%s on <%s> is not a literal number" % (key, el.tag))


def get_str(el, key):
    a = el.attrs.get(key)
    if a is None:
        return None
    if a.raw is None:
        fail("%s on <%s> is not a literal string" % (key, el.tag))
    return a.raw


# ---------------------------------------------------------------------------------------------------------------------
# Resource table

class ResTable:
    def __init__(self):
        self.strings = []
        self.entries = {}  # resid -> list of (config bytes, dtype, data)
        self.pools = []

    def resolve(self, resid, depth=0):
        if depth > 8:
            fail("resource reference loop at 0x%08x" % resid)
        out = []
        for cfg, dtype, data in self.entries.get(resid, []):
            if dtype in (T_REFERENCE, T_DYNAMIC_REFERENCE) and data >> 24 != 0x01:
                out.extend((cfg, d, v) for _c, d, v in self.resolve(data, depth + 1))
            else:
                out.append((cfg, dtype, data))
        return out


def parse_arsc(b):
    t = ResTable()
    if len(b) < 12 or u16(b, 0) != 0x0002:
        fail("malformed input: not a resource table")
    _t, hsz, size = chunk_header(b, 0, len(b))
    pos = hsz
    while pos < size:
        ctype, chsz, csize = chunk_header(b, pos, size)
        if ctype == 0x0001:
            t.strings = parse_string_pool(b, pos, size)
            t.pools.append(t.strings)
        elif ctype == 0x0200:
            pkg_id = u32(b, pos + 8)
            end = pos + csize
            p = pos + chsz
            while p < end:
                ptype, phsz, psize = chunk_header(b, p, end)
                if ptype == 0x0001:
                    t.pools.append(parse_string_pool(b, p, end))
                elif ptype == 0x0201:
                    type_id, flags = u8(b, p + 8), u8(b, p + 9)
                    count, estart = u32(b, p + 12), u32(b, p + 16)
                    cfg_size = u32(b, p + 20)
                    if 20 + cfg_size > phsz:
                        fail("malformed input: type config")
                    cfg = bytes(b[p + 20:p + 20 + cfg_size])
                    offsets = []
                    if flags & 0x01:  # sparse
                        for i in range(count):
                            offsets.append((u16(b, p + phsz + 4 * i), u16(b, p + phsz + 4 * i + 2) * 4))
                    elif flags & 0x02:  # 16-bit offsets
                        for i in range(count):
                            o = u16(b, p + phsz + 2 * i)
                            if o != 0xFFFF:
                                offsets.append((i, o * 4))
                    else:
                        for i in range(count):
                            o = u32(b, p + phsz + 4 * i)
                            if o != NONE32:
                                offsets.append((i, o))
                    for idx, off in offsets:
                        e = p + estart + off
                        if e + 8 > p + psize:
                            fail("malformed input: resource entry")
                        eflags = u16(b, e + 2)
                        if eflags & 0x0008:  # compact entry
                            dtype, data = eflags >> 8, u32(b, e + 4)
                        elif eflags & 0x0001:  # complex (bag): not a single value
                            continue
                        else:
                            esize = u16(b, e)
                            dtype, data = u8(b, e + esize + 3), u32(b, e + esize + 4)
                        resid = ((pkg_id & 0xFF) << 24) | (type_id << 16) | idx
                        t.entries.setdefault(resid, []).append((cfg, dtype, data))
                p += psize
        pos += csize
    return t


# ---------------------------------------------------------------------------------------------------------------------
# Dex

def mutf8(raw):
    try:
        s = raw.replace(b"\xc0\x80", b"\x00").decode("utf-8", "surrogatepass")
        return s.encode("utf-16", "surrogatepass").decode("utf-16", "replace")
    except UnicodeError:
        return raw.decode("utf-8", "replace")


def uleb128(b, o):
    result, shift = 0, 0
    for _ in range(5):
        byte = u8(b, o)
        o += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, o
        shift += 7
    fail("malformed input: uleb128")


def parse_dex(b):
    if len(b) < 0x70 or b[:4] != b"dex\n":
        fail("malformed input: not a dex file")
    ver = bytes(b[4:7])
    if not ver.isdigit():
        fail("malformed input: dex version")
    if int(ver) >= 41:
        fail("dex container format (version %d) is not supported" % int(ver))
    n_str, off_str, n_type, off_type = u32(b, 0x38), u32(b, 0x3C), u32(b, 0x40), u32(b, 0x44)
    if off_str + 4 * n_str > len(b) or off_type + 4 * n_type > len(b):
        fail("malformed input: dex tables")
    strings, cache, total = [], {}, 0
    for i in range(n_str):
        off = u32(b, off_str + 4 * i)
        if off in cache:
            strings.append(cache[off])
            continue
        _n, p = uleb128(b, off)
        end = b.find(b"\x00", p)
        if end < 0:
            fail("malformed input: dex string")
        total += end - p
        if total > CAP_STRINGS:
            fail("dex string table larger than the 64 MB cap")
        s = mutf8(bytes(b[p:end]))
        cache[off] = s
        strings.append(s)
    types = []
    for i in range(n_type):
        idx = u32(b, off_type + 4 * i)
        if idx >= len(strings):
            fail("malformed input: dex type index")
        types.append(strings[idx])
    return strings, types


# ---------------------------------------------------------------------------------------------------------------------
# Hosts and network types (section 3)

TLD = ("com|net|org|io|dev|app|co|me|info|biz|xyz|ai|gov|edu|mil|int|us|uk|eu|de|fr|ca|au|jp|nl|ch|ru|cn|tv|cc|gg|ly"
       "|to|page|cloud|site|online|tech|link|social|onion|arpa|local|lan")
SCHEME = ("https?|wss?|s?ftps?|sftp|ssh|git|rtsps?|rtmps?|mqtts?|smtps?|imaps?|pop3s?|ldaps?|xmpps?|sips?|tcp|udp"
          "|grpcs?|quic")
LABELS = r"(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:%s)" % TLD
URL_RE = re.compile(r"(?i)(?<![a-z0-9+.-])(%s)://([^\s/?#\"'<>\\^`{|}()\[\],;]*)" % SCHEME)
SUFFIX_RE = re.compile(r"(?<![A-Za-z0-9._%%+-])\.(%s)(?![A-Za-z0-9-]|\.[A-Za-z0-9])" % LABELS)
BARE_RE = re.compile(r"(?<![A-Za-z0-9._%%+-])(%s)(?![A-Za-z0-9-]|\.[A-Za-z0-9])" % LABELS)
OCTET = r"(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)"
IPV4_RE = re.compile(r"(?<![0-9.])(%s(?:\.%s){3})(?![0-9.])" % (OCTET, OCTET))
PACKAGE_WORDS = {"java", "javax", "kotlin", "kotlinx", "android", "androidx", "dalvik", "sun", "jdk", "junit"}
KEPT_IPS = {"0.0.0.0", "1.1.1.1", "1.0.0.1", "1.1.1.2", "1.0.0.2", "1.1.1.3", "1.0.0.3"}
NETWORK_TYPE_RE = re.compile(
    r"^\[*L(java/net/|javax/net/|java/nio/channels/[A-Za-z]*Socket|android/net/(?!Uri;|Uri\$)|android/webkit/"
    r"|android/app/DownloadManager|org/apache/http/|okhttp3/|com/squareup/okhttp/|retrofit2/|io/ktor/|io/grpc/"
    r"|io/netty/|org/chromium/net/|com/android/volley/)")
SHARED_SUFFIXES = {
    "github.io", "gitlab.io", "pages.dev", "workers.dev", "netlify.app", "vercel.app", "web.app", "firebaseapp.com",
    "appspot.com", "herokuapp.com", "blogspot.com", "azurewebsites.net", "cloudfront.net", "amazonaws.com",
    "googleapis.com", "fastly.net", "akamaized.net", "duckdns.org", "ngrok.io", "ngrok-free.app", "onrender.com",
    "fly.dev", "glitch.me",
}
SHARED_SECOND = {"co", "com", "net", "org", "ac", "gov", "edu", "ne", "or", "go", "gob", "nic", "mil"}
FRAMEWORK_PREFIXES = ("Ljava/", "Ljavax/", "Landroid/", "Ldalvik/", "Lkotlin/")


def scan_hosts(text, package):
    found = set()

    def own(host):
        return package and (host == package or host.startswith(package + "."))

    def cut(m):
        scheme, authority = m.group(1).lower(), m.group(2)
        authority = authority.rsplit("@", 1)[-1].lower()
        host, port = authority, ""
        if authority.startswith("["):
            close = authority.find("]")
            host, port = authority[:close + 1], authority[close + 1:]
        elif ":" in authority:
            host, port = authority.split(":", 1)
            port = ":" + port
        host = host.rstrip(".")
        if host and not (scheme == "http" and host == "schemas.android.com") and not own(host):
            found.add("%s://%s%s" % (scheme, host, port))
        return " "

    text = URL_RE.sub(cut, text)

    def suffix(m):
        host = m.group(1)
        if not own(host):
            found.add("." + host)
        return " "

    text = SUFFIX_RE.sub(suffix, text)
    for m in BARE_RE.finditer(text):
        host = m.group(1)
        first, last = host.split(".", 1)[0], host.rsplit(".", 1)[-1]
        if first in PACKAGE_WORDS and last not in ("com", "org"):
            continue
        if own(host):
            continue
        found.add(host)
    for m in IPV4_RE.finditer(text):
        ip = m.group(1)
        arcs = [int(x) for x in ip.split(".")]
        if ip in ("127.0.0.1", "255.255.255.255"):
            continue
        if ip not in KEPT_IPS and arcs[0] in (0, 1, 2) and all(a < 64 for a in arcs):
            continue
        found.add(ip)
    return found


def host_of(value):
    v = value.split("://", 1)[-1].lstrip(".")
    if v.startswith("["):
        return v
    return v.split(":", 1)[0]


# ---------------------------------------------------------------------------------------------------------------------
# APK walk

class Budget:
    def __init__(self):
        self.total = 0

    def read(self, zf, info):
        out = io.BytesIO()
        with zf.open(info) as f:
            while True:
                chunk = f.read(MB)
                if not chunk:
                    break
                out.write(chunk)
                if out.tell() > CAP_ENTRY:
                    fail("%s inflates past the 256 MB cap" % info.filename)
                self.total += len(chunk)
                if self.total > CAP_TOTAL:
                    fail("the APK inflates past the 1 GB cap")
        return out.getvalue()


class Apk:
    def __init__(self):
        self.manifest = None
        self.table = None
        self.top = {}  # name -> bytes (top-level entries the gate reads whole)
        self.native_libs, self.extra_code = set(), set()
        self.dex_strings, self.dex_types = [], set()
        self.xml_pools = []
        self.files = {}  # top-level path -> bytes, compiled XML and other small resources read on demand
        self.zip = None


def classify_and_read(apk, zf, prefix, depth, budget, top_level):
    """Walks one archive level. Returns True when the archive holds code (dex or class files) at any depth."""
    if depth > MAX_DEPTH:
        fail("archives nested deeper than %d levels" % MAX_DEPTH)
    has_code = False
    for info in zf.infolist():
        if info.is_dir():
            continue
        name = info.filename
        path = prefix + name
        with zf.open(info) as f:
            head = f.read(4)
        root_dex = top_level and re.fullmatch(r"classes\d*\.dex", name)
        if head == b"dex\n":
            has_code = True
            data = budget.read(zf, info)
            strings, types = parse_dex(data)
            apk.dex_strings.extend(strings)
            apk.dex_types.update(types)
            if not root_dex:
                apk.extra_code.add(path)
        elif head == b"\x7fELF" or name.endswith(".so") or (top_level and name.startswith("lib/")):
            apk.native_libs.add(path)
        elif head == b"PK\x03\x04":
            data = budget.read(zf, info)
            try:
                inner = zipfile.ZipFile(io.BytesIO(data))
            except zipfile.BadZipFile:
                fail("malformed input: nested archive %s" % path)
            if classify_and_read(apk, inner, path + "!", depth + 1, budget, False):
                has_code = True
                apk.extra_code.add(path)
        elif head == b"\xca\xfe\xba\xbe" or name.endswith(".class"):
            has_code = True
            apk.extra_code.add(path)
        elif head == b"\x03\x00\x08\x00":
            data = budget.read(zf, info)
            if top_level:
                apk.files[name] = data
            _pool_of_xml(data, apk)
            if top_level and name == "AndroidManifest.xml":
                apk.manifest = decode_axml(data)
        elif head == b"\x02\x00\x0c\x00":
            data = budget.read(zf, info)
            table = parse_arsc(data)
            apk.xml_pools.extend(table.pools)
            if top_level and name == "resources.arsc":
                apk.table = table
    return has_code


def _pool_of_xml(data, apk):
    """Reads a compiled XML's string pool for the hosts scan."""
    _t, hsz, size = chunk_header(data, 0, len(data))
    pos = hsz
    while pos < size:
        ctype, _h, csize = chunk_header(data, pos, size)
        if ctype == 0x0001:
            apk.xml_pools.append(parse_string_pool(data, pos, size))
            return
        pos += csize


def read_apk(path, extra_text_paths=()):
    apk = Apk()
    budget = Budget()
    try:
        zf = zipfile.ZipFile(path)
    except (zipfile.BadZipFile, OSError) as e:
        fail("malformed input: %s" % e)
    apk.zip = zf
    try:
        classify_and_read(apk, zf, "", 0, budget, True)
        apk.extra_text = []
        for info in zf.infolist():
            if any(fnmatch.fnmatchcase(info.filename, g) for g in extra_text_paths):
                apk.extra_text.append(budget.read(zf, info).decode("utf-8", "replace"))
        apk.budget = budget
    except GateError:
        raise
    except (zipfile.BadZipFile, zipfile.LargeZipFile, EOFError, ValueError, KeyError, IndexError, struct.error,
            UnicodeError, OverflowError, NotImplementedError, OSError) as e:
        fail("malformed input: %s" % e)
    if apk.manifest is None:
        fail("malformed input: no compiled AndroidManifest.xml")
    return apk


# ---------------------------------------------------------------------------------------------------------------------
# Manifest facts

def qualify(package, name):
    if name is None:
        return None
    if name.startswith("."):
        return package + name
    if "." not in name:
        return package + "." + name
    return name


def file_variants(apk, resid):
    """Every configuration's value of a resource that should be a file: list of (kind, lines)."""
    if apk.table is None:
        fail("resource 0x%08x referenced but the APK has no resources.arsc" % resid)
    values = apk.table.resolve(resid)
    if not values:
        fail("unresolvable resource reference 0x%08x" % resid)
    variants = []
    for _cfg, dtype, data in values:
        if dtype != T_STRING or data >= len(apk.table.strings):
            return None  # a value, not a file
        path = apk.table.strings[data]
        try:
            raw = apk.budget.read(apk.zip, apk.zip.getinfo(path))
        except KeyError:
            fail("resource file %s is missing from the APK" % path)
        if raw[:4] == b"\x03\x00\x08\x00":
            variants.append((0, tuple(canonical_lines(decode_axml(raw))), path))
        else:
            variants.append((1, ("raw sha256 " + hashlib.sha256(raw).hexdigest(),), path))
    return variants


def variants_digest(variants):
    uniq = sorted({(k, lines) for k, lines, _p in variants})
    joined = "\n---\n".join("\n".join(lines) for _k, lines in uniq)
    return hashlib.sha256(joined.encode("utf-8")).hexdigest(), [list(lines) for _k, lines in uniq]


def resource_values(apk, resid):
    """A reference that resolves to values (not files): each configuration's value, joined by |."""
    if resid >> 24 == 0x01:
        return "android:0x%08x" % resid
    if apk.table is None:
        fail("resource 0x%08x referenced but the APK has no resources.arsc" % resid)
    vals = apk.table.resolve(resid)
    if not vals:
        fail("unresolvable resource reference 0x%08x" % resid)
    out = []
    for _cfg, dtype, data in vals:
        out.append(value_text(Attr(apk.table.strings[data] if dtype == T_STRING and data < len(apk.table.strings) else None, dtype, data)))
    return "|".join(sorted(set(str(v) for v in out)))


def nsc_configs(apk, resid):
    variants = []
    for _cfg, dtype, data in apk.table.resolve(resid) if apk.table else []:
        if dtype != T_STRING:
            fail("network security config 0x%08x is not a file" % resid)
        path = apk.table.strings[data]
        try:
            variants.append(decode_axml(apk.budget.read(apk.zip, apk.zip.getinfo(path))))
        except KeyError:
            fail("network security config %s is missing" % path)
    if not variants:
        fail("unresolvable network security config reference 0x%08x" % resid)
    configs = []

    def plain_bool(el, key):
        a = el.attrs.get(key)
        if a is None:
            return "absent"
        if a.dtype == T_INT_BOOLEAN:
            return "true" if a.data else "false"
        if a.raw in ("true", "false"):
            return a.raw
        fail("%s on <%s> is not a literal boolean" % (key, el.tag))

    def walk(el, kind):
        domains, anchors, pins = [], [], []
        for d in el.children("domain"):
            name = " ".join(t for _x, t in [n for n in d.nodes if not isinstance(n, Elem)])
            domains.append(name + (";includeSubdomains" if plain_bool(d, "includeSubdomains") == "true" else ""))
        for ta in el.children("trust-anchors"):
            for c in ta.children("certificates"):
                a = c.attrs.get("src")
                if a is None:
                    fail("certificates without src")
                if a.dtype in (T_REFERENCE, T_DYNAMIC_REFERENCE):
                    src = ",".join(sorted({p for _k, _l, p in (file_variants(apk, a.data) or [])}))
                else:
                    src = a.raw
                anchors.append(src + (";overridePins" if plain_bool(c, "overridePins") == "true" else ""))
        for ps in el.children("pin-set"):
            exp = ps.attrs.get("expiration")
            for pin in ps.children("pin"):
                digest = pin.attrs.get("digest")
                text = "".join(t for _x, t in [n for n in pin.nodes if not isinstance(n, Elem)])
                pins.append("%s,%s%s" % (digest.raw if digest else "", text, ";expiration=" + exp.raw if exp and exp.raw else ""))
        configs.append({"kind": kind, "cleartext": plain_bool(el, "cleartextTrafficPermitted"), "domains": sorted(domains),
                        "trust_anchors": sorted(anchors), "pins": sorted(pins)})
        for child in el.children("domain-config"):
            walk(child, "domain-config")

    for root in variants:
        for el in root.children():
            if el.tag in ("base-config", "domain-config", "debug-overrides"):
                walk(el, el.tag)
    uniq = {json.dumps(c, sort_keys=True): c for c in configs}
    return [uniq[k] for k in sorted(uniq, key=lambda k: (uniq[k]["kind"], k))]


DATA_KEYS = ("scheme", "host", "port", "path", "pathPrefix", "pathPattern", "pathSuffix", "pathAdvancedPattern", "mimeType")


def manifest_facts(apk):
    m = apk.manifest
    if m.tag != "manifest":
        fail("malformed input: manifest root is <%s>" % m.tag)
    package = get_str(m, "package")
    if not package:
        fail("malformed input: manifest without a package")
    facts = {"package": package, "shared_user_id": get_str(m, "android:sharedUserId")}
    sdk = (m.children("uses-sdk") or [None])[0]
    min_sdk = get_int(sdk, "android:minSdkVersion", True) if sdk is not None else None
    target = get_int(sdk, "android:targetSdkVersion", True) if sdk is not None else None
    facts["sdk"] = {"min": min_sdk if min_sdk is not None else 1, "target": target}
    effective_target = target if target is not None else (min_sdk if min_sdk is not None else 1)
    apps = m.children("application")
    if len(apps) > 1:
        fail("malformed input: two <application> elements")
    app = apps[0] if apps else Elem("application", 1)
    facts["debuggable"] = bool(get_bool(app, "android:debuggable"))
    allow = get_bool(app, "android:allowBackup")
    fbc = app.attrs.get("android:fullBackupContent")
    if fbc is None:
        fbc_v = "absent"
    elif fbc.dtype == T_INT_BOOLEAN:
        fbc_v = "true" if fbc.data else "false"
    elif fbc.dtype in (T_REFERENCE, T_DYNAMIC_REFERENCE):
        fbc_v = "resource"
    else:
        fail("android:fullBackupContent is neither a literal boolean nor a resource")
    facts["backup"] = {
        "allow_backup": True if allow is None else allow,
        "full_backup_content": fbc_v,
        "data_extraction_rules": "android:dataExtractionRules" in app.attrs,
        "backup_agent": qualify(package, get_str(app, "android:backupAgent")),
    }
    clear = get_bool(app, "android:usesCleartextTraffic")
    nsc = app.attrs.get("android:networkSecurityConfig")
    if nsc is not None and nsc.dtype not in (T_REFERENCE, T_DYNAMIC_REFERENCE):
        fail("android:networkSecurityConfig is not a resource reference")
    facts["network"] = {
        "uses_cleartext_traffic": "absent" if clear is None else ("true" if clear else "false"),
        "network_security_config": nsc_configs(apk, nsc.data) if nsc is not None else None,
    }
    requested, declared = [], []
    for tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
        for el in m.children(tag):
            name = get_str(el, "android:name")
            if not name:
                fail("a <%s> without a name" % tag)
            entry = name
            mx = get_int(el, "android:maxSdkVersion")
            if mx is not None:
                entry += ";maxSdkVersion=%d" % mx
            flags = get_int(el, "android:usesPermissionFlags")
            if flags:
                parts = []
                if flags & NEVER_FOR_LOCATION:
                    parts.append("neverForLocation")
                if flags & ~NEVER_FOR_LOCATION:
                    parts.append("0x%x" % (flags & ~NEVER_FOR_LOCATION))
                entry += ";usesPermissionFlags=" + "|".join(parts)
            requested.append(entry)
    for el in m.children("permission"):
        name = get_str(el, "android:name")
        if not name:
            fail("a <permission> without a name")
        lvl = get_int(el, "android:protectionLevel")
        declared.append(name + (";protectionLevel=0x%x" % lvl if lvl is not None else ""))
    facts["permissions"] = {"requested": sorted(set(requested)), "declared": sorted(set(declared))}
    queries = []
    for q in m.children("queries"):
        for el in q.children():
            if el.tag == "package":
                n = get_str(el, "android:name")
                if not n:
                    fail("a <queries><package> without a name")
                queries.append("package:" + n)
            elif el.tag == "provider":
                a = get_str(el, "android:authorities")
                if not a:
                    fail("a <queries><provider> without authorities")
                queries.append("provider:" + a)
            elif el.tag == "intent":
                actions, categories, data = intent_parts([el])
                queries.append("intent:" + ";".join(sorted(["action:" + x for x in actions] + ["category:" + x for x in categories] + ["data:" + x for x in data])))
    facts["queries"] = sorted(set(queries))
    natives = []
    for el in app.children("uses-native-library"):
        n = get_str(el, "android:name")
        if not n:
            fail("a <uses-native-library> without a name")
        req = get_bool(el, "android:required")
        natives.append("%s;required=%s" % (n, "false" if req is False else "true"))
    facts["uses_native_libraries"] = sorted(set(natives))
    comps = []
    for el in app.children():
        if el.tag not in ("activity", "activity-alias", "service", "receiver", "provider"):
            continue
        name = qualify(package, get_str(el, "android:name"))
        if not name:
            fail("a <%s> without a name" % el.tag)
        filters = el.children("intent-filter")
        exp = get_bool(el, "android:exported")
        if exp is None:
            if el.tag == "provider":
                exp = isinstance(effective_target, int) and effective_target < 17
            else:
                exp = bool(filters)
        if el.tag in ("activity", "activity-alias") and not exp:
            continue
        actions, categories, data = intent_parts(filters)
        c = {"type": el.tag, "name": name, "exported": exp,
             "permission": get_str(el, "android:permission"),
             "read_permission": get_str(el, "android:readPermission"),
             "write_permission": get_str(el, "android:writePermission"),
             "grant_uri_permissions": bool(get_bool(el, "android:grantUriPermissions"))}
        if el.tag == "provider":
            auth = get_str(el, "android:authorities") or ""
            c["authorities"] = sorted(a for a in auth.split(";") if a)
        if el.tag == "service":
            fst = get_int(el, "android:foregroundServiceType")
            c["foreground_service_type"] = "0x%x" % fst if fst is not None else None
        c["actions"], c["categories"], c["data"] = actions, categories, data
        comps.append(c)
    facts["components"] = sorted(comps, key=lambda c: (c["type"], c["name"]))
    features = []
    for el in m.children("uses-feature"):
        req = "false" if get_bool(el, "android:required") is False else "true"
        n = get_str(el, "android:name")
        if n:
            entry = "%s;required=%s" % (n, req)
        else:
            gl = get_int(el, "android:glEsVersion")
            if gl is None:
                fail("a <uses-feature> without a name or glEsVersion")
            entry = "glEsVersion=0x%x;required=%s" % (gl, req)
        v = el.attrs.get("android:version")
        if v is not None:
            if v.dtype not in (T_INT_DEC, T_INT_HEX):
                fail("uses-feature version is not an integer")
            entry += ";version=%d" % v.data
        features.append(entry)
    facts["uses_features"] = sorted(set(features))
    metas = []
    for el in app.children("meta-data"):
        metas.append(meta_entry(apk, el))
    facts["meta_data"] = sorted(set(metas))
    prov = []
    for el in app.children("provider"):
        for md in el.children("meta-data"):
            r = md.attrs.get("android:resource")
            if r is None:
                continue
            if r.dtype not in (T_REFERENCE, T_DYNAMIC_REFERENCE):
                fail("provider meta-data resource is not a reference")
            variants = file_variants(apk, r.data)
            if variants is None:
                fail("provider meta-data resource 0x%08x is not a file" % r.data)
            digest, elements = variants_digest(variants)
            prov.append({"provider": qualify(package, get_str(el, "android:name")), "name": get_str(md, "android:name"),
                         "sha256": digest, "elements": [line for v in elements for line in v]})
    facts["provider_resources"] = sorted(prov, key=lambda p: (p["provider"], p["name"] or ""))
    return facts


def meta_entry(apk, el):
    name = get_str(el, "android:name")
    if not name:
        fail("a <meta-data> without a name")
    v = el.attrs.get("android:value")
    r = el.attrs.get("android:resource")
    if v is not None:
        if v.dtype in (T_REFERENCE, T_DYNAMIC_REFERENCE):
            return "%s;resource=%s" % (name, resource_values(apk, v.data))
        return "%s;value=%s" % (name, value_text(v))
    if r is not None:
        if r.dtype not in (T_REFERENCE, T_DYNAMIC_REFERENCE):
            return "%s;resource=%s" % (name, value_text(r))
        if r.data >> 24 == 0x01:
            return "%s;resource=android:0x%08x" % (name, r.data)
        values = apk.table.resolve(r.data) if apk.table else []
        is_file = [dt == T_STRING and apk.table.strings[d].startswith("res/") for _c, dt, d in values]
        if values and all(is_file):
            digest, _e = variants_digest(file_variants(apk, r.data))
            return "%s;resource=sha256:%s" % (name, digest)
        if any(is_file):
            fail("meta-data %s is a file in one configuration and a value in another" % name)
        return "%s;resource=%s" % (name, resource_values(apk, r.data))
    return name


def intent_parts(filters):
    actions, categories, data = set(), set(), set()
    for f in filters:
        for a in f.children("action"):
            actions.add(get_str(a, "android:name") or "")
        for c in f.children("category"):
            categories.add(get_str(c, "android:name") or "")
        for d in f.children("data"):
            parts = []
            for k in DATA_KEYS:
                a = d.attrs.get("android:" + k)
                if a is not None:
                    parts.append("%s=%s" % (k, value_text(a)))
            if parts:
                data.add(";".join(parts))
    return sorted(actions), sorted(categories), sorted(data)


# ---------------------------------------------------------------------------------------------------------------------
# Dependencies (Gradle output)

DEP_LINE = re.compile(r"^[|+\\ ]*[+\\]--- (.*)$")


def parse_deps(log_text, configuration, project):
    lines = log_text.splitlines()
    start = None
    for i, line in enumerate(lines):
        if line.startswith(configuration + " - "):
            start = i
            break
    if start is None:
        fail("dependency report for %s not found in the Gradle log" % configuration)
    modules = set()
    for line in lines[start + 1:]:
        if not line.strip():
            break
        if line.strip() == "No dependencies":
            continue
        m = DEP_LINE.match(line)
        if not m:
            fail("unreadable dependency line: %r" % line)
        item = m.group(1).strip()
        if "FAILED" in item:
            fail("unresolved dependency: %s" % item)
        if item.endswith(" (c)") or item.endswith(" (n)") or item.startswith("project "):
            continue
        item = re.sub(r" \(\*\)$", "", item)
        coords = item.split(" -> ")[0].strip()
        parts = coords.split(":")
        if len(parts) < 2 or not parts[0] or not parts[1]:
            fail("unreadable dependency line: %r" % line)
        modules.add(parts[0] + ":" + parts[1])
    files, desugaring, done = set(), set(), set()
    for line in lines:
        if line.startswith("gate-file-dependency: "):
            _proj, path = line[len("gate-file-dependency: "):].split(" ", 1)
            files.add(path.strip())
        elif line.startswith("gate-desugaring: "):
            _proj, coords = line[len("gate-desugaring: "):].split(" ", 1)
            desugaring.add(coords.strip())
        elif line.startswith("gate-file-dependencies: done "):
            done.add(line[len("gate-file-dependencies: done "):].strip())
    for marker in (":", project):
        if marker not in done:
            fail("gate-file-dependencies: done %s is missing from the Gradle log" % marker)
    return sorted(modules), sorted(files), sorted(desugaring)


# ---------------------------------------------------------------------------------------------------------------------
# SDK tools

def find_tool(name):
    env = os.environ.get(name.upper())
    if env:
        return env if os.path.exists(env) else None
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        return None
    bt = os.path.join(sdk, "build-tools")
    if not os.path.isdir(bt):
        return None

    def key(v):
        return [int(x) if x.isdigit() else -1 for x in re.split(r"[.-]", v)]

    for v in sorted(os.listdir(bt), key=key, reverse=True):
        p = os.path.join(bt, v, name)
        if os.path.exists(p):
            return p
    return None


def run_tool(args):
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=300)
    except (OSError, subprocess.TimeoutExpired) as e:
        fail("%s could not run: %s" % (args[0], e))
    return r


def aapt2_cross_check(apk_path, facts, require_tools):
    aapt2 = find_tool("aapt2")
    if aapt2 is None:
        if require_tools:
            fail("aapt2 not found (ANDROID_HOME/build-tools or AAPT2) and --require-tools is set")
        return False
    r = run_tool([aapt2, "dump", "badging", apk_path])
    if r.returncode != 0:
        fail("aapt2 dump badging failed: %s" % r.stderr.strip()[:500])
    pkg = re.search(r"^package: name='([^']*)'", r.stdout, re.M)
    sdk = re.search(r"^(?:sdkVersion|minSdkVersion):'([^']*)'", r.stdout, re.M)
    tgt = re.search(r"^targetSdkVersion:'([^']*)'", r.stdout, re.M)
    dbg = re.search(r"^application-debuggable", r.stdout, re.M) is not None
    problems = []
    if not pkg or pkg.group(1) != facts["package"]:
        problems.append("package %s vs %s" % (pkg.group(1) if pkg else None, facts["package"]))
    if not sdk or str(sdk.group(1)) != str(facts["sdk"]["min"]):
        problems.append("minSdk %s vs %s" % (sdk.group(1) if sdk else None, facts["sdk"]["min"]))
    want_target = facts["sdk"]["target"] if facts["sdk"]["target"] is not None else facts["sdk"]["min"]
    if (tgt.group(1) if tgt else str(facts["sdk"]["min"])) != str(want_target):
        problems.append("targetSdk %s vs %s" % (tgt.group(1) if tgt else None, want_target))
    if dbg != facts["debuggable"]:
        problems.append("debuggable %s vs %s" % (dbg, facts["debuggable"]))
    r = run_tool([aapt2, "dump", "permissions", apk_path])
    if r.returncode != 0:
        fail("aapt2 dump permissions failed: %s" % r.stderr.strip()[:500])
    req, decl = set(), set()
    for line in r.stdout.splitlines():
        m = re.match(r"^(uses-permission(?:-sdk-23)?|permission):\s*(?:name=')?([^'\s]+)", line.strip())
        if m:
            (decl if m.group(1) == "permission" else req).add(m.group(2))
    if req != {p.split(";")[0] for p in facts["permissions"]["requested"]}:
        problems.append("requested permissions %s vs %s" % (sorted(req), facts["permissions"]["requested"]))
    if decl != {p.split(";")[0] for p in facts["permissions"]["declared"]}:
        problems.append("declared permissions %s vs %s" % (sorted(decl), facts["permissions"]["declared"]))
    if problems:
        fail("the manifest decoder disagrees with aapt2: " + "; ".join(problems))
    return True


# ---------------------------------------------------------------------------------------------------------------------
# Facts and baseline

def apk_facts(apk_path, baseline, require_tools, tools=True):
    scan = baseline.get("scan") or {}
    extra = scan.get("extra_text_paths") or []
    apk = read_apk(apk_path, extra)
    facts = manifest_facts(apk)
    if tools:
        aapt2_cross_check(apk_path, facts, require_tools)
    facts["native_libs"] = sorted(apk.native_libs)
    facts["extra_code"] = sorted(apk.extra_code)
    facts["desugared_library"] = any(t.startswith("Lj$/") or t.startswith("[Lj$/") for t in apk.dex_types)
    facts["network_api"] = sorted(t for t in apk.dex_types if NETWORK_TYPE_RE.match(t))
    hosts = set()
    seen = set()
    for s in list(apk.dex_strings) + [x for pool in apk.xml_pools for x in pool] + apk.extra_text:
        if s in seen:
            continue
        seen.add(s)
        hosts |= scan_hosts(s, facts["package"])
    facts["hosts"] = sorted(hosts)
    digest_globs = scan.get("asset_digest_paths", ["*.tflite", "*.onnx", "*.ort", "*.task"]) if "asset_digest_paths" in scan else ["*.tflite", "*.onnx", "*.ort", "*.task"]
    if digest_globs is None:
        fail("scan.asset_digest_paths is null")
    assets = []
    for info in apk.zip.infolist():
        if info.is_dir():
            continue
        if any(fnmatch.fnmatch(info.filename.lower(), g.lower()) for g in digest_globs):
            data = apk.budget.read(apk.zip, info)
            assets.append({"path": info.filename, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)})
    facts["assets"] = sorted(assets, key=lambda a: a["path"])
    return facts


def validate_rules(rules):
    if not isinstance(rules, list):
        fail("notes must be a list")
    for r in rules:
        if not isinstance(r, dict) or not r.get("note") or not isinstance(r.get("note"), str):
            fail("note rule without a note: %r" % (r,))
        if r.get("field") == "hosts":
            if set(r) != {"field", "suffix", "note"}:
                fail("hosts note rule must have exactly field, suffix, note: %r" % (r,))
            labels = r["suffix"].split(".")
            if len(labels) < 2 or not all(labels):
                fail("hosts note rule suffix needs two labels or more: %r" % r["suffix"])
            if r["suffix"] in SHARED_SUFFIXES or (len(labels) == 2 and labels[0] in SHARED_SECOND):
                fail("hosts note rule on a shared suffix: %r" % r["suffix"])
        elif r.get("field") == "network_api":
            if set(r) != {"field", "prefix", "note"}:
                fail("network_api note rule must have exactly field, prefix, note: %r" % (r,))
            p = r["prefix"]
            segs = [x for x in p[1:].split("/") if x] if p.startswith("L") else []
            if len(segs) < 2 or not p.endswith("/") or p.startswith(FRAMEWORK_PREFIXES):
                fail("network_api note rule prefix refused: %r" % p)
        else:
            fail("note rule for an unknown field: %r" % (r,))


def rule_note(rules, field, value):
    for r in rules:
        if r["field"] != field:
            continue
        if field == "hosts":
            h = host_of(value)
            if h == r["suffix"] or h.endswith("." + r["suffix"]):
                return "(rule %s) %s" % (r["suffix"], r["note"])
        elif value.lstrip("[").startswith(r["prefix"]):
            return "(rule %s) %s" % (r["prefix"], r["note"])
    return ""


def build_baseline(facts, existing, modules, files, desugaring):
    rules = existing.get("notes", [])
    validate_rules(rules)

    def noted(field, values):
        old = {e["value"]: e.get("note", "") for e in existing.get(field, []) if isinstance(e, dict) and "value" in e}
        return [{"value": v, "note": old.get(v) or rule_note(rules, field, v)} for v in values]

    deps = existing.get("dependencies") or {}
    scan = existing.get("scan") or {"extra_text_paths": []}
    b = {
        "schema": SCHEMA,
        "app": existing.get("app"),
        "variant": existing.get("variant"),
        "package": facts["package"],
        "shared_user_id": facts["shared_user_id"],
        "sdk": facts["sdk"],
        "debuggable": facts["debuggable"],
        "backup": facts["backup"],
        "network": facts["network"],
        "permissions": facts["permissions"],
        "queries": facts["queries"],
        "uses_native_libraries": facts["uses_native_libraries"],
        "components": facts["components"],
    }
    for opt in ("uses_features", "meta_data", "provider_resources"):
        if facts[opt]:
            b[opt] = facts[opt]
    b["native_libs"] = facts["native_libs"]
    if facts["assets"]:
        b["assets"] = facts["assets"]
    b.update({
        "extra_code": facts["extra_code"],
        "desugared_library": facts["desugared_library"],
        "network_api": noted("network_api", facts["network_api"]),
        "dependencies": {"project": deps.get("project", ":app"), "configuration": deps.get("configuration"),
                         "modules": modules, "files": files, "desugaring": desugaring},
        "hosts": noted("hosts", facts["hosts"]),
        "signing": {"cert_sha256": (existing.get("signing") or {}).get("cert_sha256")},
        "scan": scan,
        "notes": rules,
    })
    return b


def dump(b):
    return json.dumps(b, indent=2, ensure_ascii=True) + "\n"


# Fields compared against every APK, in output order. kind: scalar, list (+/- items), keyed (+/-/~ by key), noted.
APK_FIELDS = [
    ("package", "scalar"), ("shared_user_id", "scalar"), ("sdk.min", "scalar"), ("sdk.target", "scalar"),
    ("debuggable", "scalar"), ("backup.allow_backup", "scalar"), ("backup.full_backup_content", "scalar"),
    ("backup.data_extraction_rules", "scalar"), ("backup.backup_agent", "scalar"),
    ("network.uses_cleartext_traffic", "scalar"), ("network.network_security_config", "scalar"),
    ("permissions.requested", "list"), ("permissions.declared", "list"), ("queries", "list"),
    ("uses_native_libraries", "list"), ("components", "keyed"), ("uses_features", "list"), ("meta_data", "list"),
    ("provider_resources", "keyed"), ("native_libs", "list"), ("assets", "keyed"), ("extra_code", "list"),
    ("desugared_library", "scalar"), ("network_api", "noted"), ("dependencies.modules", "list"),
    ("dependencies.files", "list"), ("dependencies.desugaring", "list"), ("hosts", "noted"),
]
CONFIG_FIELDS = [
    ("schema", "scalar"), ("app", "scalar"), ("variant", "scalar"), ("dependencies.project", "scalar"),
    ("dependencies.configuration", "scalar"), ("signing.cert_sha256", "scalar"), ("scan", "scalar"), ("notes", "scalar"),
]
OPTIONAL = {"uses_features", "meta_data", "provider_resources", "assets"}
MISSING = object()


def get_path(b, path):
    cur = b
    for part in path.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return [] if path in OPTIONAL else MISSING
        cur = cur[part]
    return cur


def item_key(field, item):
    if field == "components":
        return "%s %s" % (item.get("type"), item.get("name"))
    if field == "provider_resources":
        return "%s %s" % (item.get("provider"), item.get("name"))
    if field == "assets":
        return item.get("path")
    return item


def j(v):
    return json.dumps(v, ensure_ascii=True, sort_keys=True)


def diff(old, new, fields, with_notes=False):
    """Lines (op, field, text) for every difference between two baselines, in field order."""
    out = []
    for field, kind in fields:
        a, b = get_path(old, field), get_path(new, field)
        if kind != "scalar":
            a = [] if a is MISSING or a is None else a
            b = [] if b is MISSING or b is None else b
        if kind == "scalar" or a is MISSING or b is MISSING or not isinstance(a, list) or not isinstance(b, list):
            if j(None if a is MISSING else a) != j(None if b is MISSING else b):
                out.append(("~", field, "%s -> %s" % (j(None if a is MISSING else a), j(None if b is MISSING else b))))
            continue
        if kind == "noted":
            av = {e["value"]: e.get("note", "") for e in a if isinstance(e, dict)}
            bv = {e["value"]: e.get("note", "") for e in b if isinstance(e, dict)}
            for v in sorted(set(bv) - set(av)):
                out.append(("+", field, "%s (note: %s)" % (v, bv[v]) if with_notes else v))
            for v in sorted(set(av) - set(bv)):
                out.append(("-", field, v))
            if with_notes:
                for v in sorted(set(av) & set(bv)):
                    if av[v] != bv[v]:
                        out.append(("~", field, "%s note: %s -> %s" % (v, j(av[v]), j(bv[v]))))
            continue
        ak = {}
        for x in a:
            ak.setdefault(item_key(field, x), x)
        bk = {}
        for x in b:
            bk.setdefault(item_key(field, x), x)
        for k in sorted(set(bk) - set(ak), key=str):
            out.append(("+", field, k if isinstance(k, str) else j(k)))
        for k in sorted(set(ak) - set(bk), key=str):
            out.append(("-", field, k if isinstance(k, str) else j(k)))
        if kind == "keyed":
            for k in sorted(set(ak) & set(bk), key=str):
                if j(ak[k]) != j(bk[k]):
                    out.append(("~", field, "%s: %s -> %s" % (k, j(ak[k]), j(bk[k]))))
        if set(ak) == set(bk) and not [o for o in out if o[1] == field] and j(a) != j(b):
            out.append(("~", field, "order or duplicates: %s -> %s" % (j(a), j(b))))
    return out


def gh_escape(s):
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def in_actions():
    return os.environ.get("GITHUB_ACTIONS") == "true"


def missing_notes(b):
    out = []
    for field in ("hosts", "network_api"):
        for e in b.get(field, []):
            if not isinstance(e, dict) or not str(e.get("note", "")).strip():
                out.append(("~", field, "%s has no note" % (e.get("value") if isinstance(e, dict) else e)))
    return out


def pin_problem(b):
    pins = (b.get("signing") or {}).get("cert_sha256")
    if not pins or not isinstance(pins, list) or not all(isinstance(p, str) and re.fullmatch(r"[0-9a-f]{64}", p) for p in pins):
        return ("~", "signing.cert_sha256", "no valid pin: %s" % j(pins))
    return None


def load_baseline(path, generate=False):
    try:
        with open(path, encoding="utf-8") as f:
            b = json.load(f)
    except (OSError, ValueError) as e:
        fail("cannot read baseline %s: %s" % (path, e))
    if not isinstance(b, dict):
        fail("baseline %s is not a JSON object" % path)
    if b.get("schema") != SCHEMA and not generate:
        fail("baseline schema %r is not %d" % (b.get("schema"), SCHEMA))
    deps = b.get("dependencies") or {}
    if not deps.get("configuration"):
        fail("baseline has no dependencies.configuration")
    scan = b.get("scan") or {}
    if "asset_digest_paths" in scan and scan["asset_digest_paths"] is None:
        fail("scan.asset_digest_paths is null")
    validate_rules(b.get("notes", []))
    return b


def read_text(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError as e:
        fail("cannot read %s: %s" % (path, e))


def generated_for(args, existing):
    deps = existing.get("dependencies") or {}
    modules, files, desugaring = parse_deps(read_text(args.deps), deps["configuration"], deps.get("project", ":app"))
    facts = apk_facts(args.apk, existing, args.require_tools)
    return build_baseline(facts, existing, modules, files, desugaring)


def cmd_generate(args):
    existing = load_baseline(args.baseline, generate=True)
    new = generated_for(args, existing)
    out = args.out or args.baseline
    changes = diff(existing, new, APK_FIELDS + CONFIG_FIELDS, with_notes=True)
    with open(out, "w", encoding="ascii") as f:
        f.write(dump(new))
    for op, field, text in changes:
        print("CHANGED %s %s: %s" % (op, field, text))
    print("gate: wrote %s (%d changes)" % (out, len(changes)))
    return 0


def cmd_check(args):
    base = load_baseline(args.baseline)
    new = generated_for(args, base)
    if args.generated:
        d = os.path.dirname(args.generated)
        if d:
            os.makedirs(d, exist_ok=True)
        with open(args.generated, "w", encoding="ascii") as f:
            f.write(dump(new))
    pins = (base.get("signing") or {}).get("cert_sha256")
    if pins:
        print("gate: pinned signing certificate: %s" % ", ".join(pins))
    if args.compare_to is not None:
        compare(args.compare_to, base)
    problems = diff(base, new, APK_FIELDS) + missing_notes(base)
    if args.require_pin:
        p = pin_problem(base)
        if p:
            problems.append(p)
    if not problems:
        print("gate: OK, the APK matches %s" % args.baseline)
        return 0
    print("gate: CHECK FAILED, the APK differs from %s in %d places:" % (args.baseline, len(problems)))
    for op, field, text in problems:
        line = "GATE %s %s: %s" % (op, field, text)
        print(line)
        if in_actions():
            print("::error title=Release gate::%s" % gh_escape(line))
    print("gate: + is in the APK but not the baseline, - is in the baseline but not the APK, ~ changed.")
    print("gate: if the change is intended, update %s in this pull request (copy the generated baseline this run "
          "printed) and list it under GATE CHANGES." % args.baseline)
    return 1


def compare(base_path, current):
    if not os.path.exists(base_path):
        line = "BASELINE + (all): no baseline on the base branch, every field is new"
        print(line)
        if in_actions():
            print("::warning title=Gate baseline changed in this PR::%s" % gh_escape(line))
        return
    try:
        with open(base_path, encoding="utf-8") as f:
            old = json.load(f)
    except (OSError, ValueError) as e:
        fail("cannot read base baseline %s: %s" % (base_path, e))
    for op, field, text in diff(old, current, CONFIG_FIELDS + APK_FIELDS, with_notes=True):
        line = "BASELINE %s %s: %s" % (op, field, text)
        print(line)
        if in_actions():
            print("::warning title=Gate baseline changed in this PR::%s" % gh_escape(line))


def cmd_inspect(args):
    facts = apk_facts(args.apk, {"scan": {"extra_text_paths": []}}, args.require_tools)
    print(json.dumps(facts, indent=2, ensure_ascii=True))
    return 0


# ---------------------------------------------------------------------------------------------------------------------
# Signing

SIG_V2, SIG_V3, SIG_V31 = 0x7109871A, 0xF05368C0, 0x1B93AD61


def lp(b, o, end):
    n = u32(b, o)
    if o + 4 + n > end:
        fail("malformed input: length-prefixed field")
    return o + 4, o + 4 + n


def fingerprints(path):
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError as e:
        fail("cannot read %s: %s" % (path, e))
    eocd = data.rfind(b"PK\x05\x06", max(0, len(data) - 65557))
    if eocd < 0:
        fail("no zip end record")
    cd = u32(data, eocd + 16)
    if cd < 32 or data[cd - 16:cd] != b"APK Sig Block 42":
        fail("no APK Signing Block")
    size = u64(data, cd - 24)
    start = cd - size - 8
    if start < 0 or u64(data, start) != size:
        fail("malformed APK Signing Block")
    blocks = {}
    p, end = start + 8, cd - 24
    while p < end:
        n = u64(data, p)
        if n < 4 or p + 8 + n > end:
            fail("malformed APK Signing Block pair")
        blocks[u32(data, p + 8)] = (p + 12, p + 8 + n)
        p += 8 + n
    for scheme in (SIG_V31, SIG_V3, SIG_V2):
        if scheme in blocks:
            s, e = blocks[scheme]
            digests = []
            seq_s, seq_e = lp(data, s, e)
            q = seq_s
            while q < seq_e:
                sg_s, sg_e = lp(data, q, seq_e)
                sd_s, sd_e = lp(data, sg_s, sg_e)
                _dg_s, dg_e = lp(data, sd_s, sd_e)
                cs_s, cs_e = lp(data, dg_e, sd_e)
                c_s, c_e = lp(data, cs_s, cs_e)
                digests.append(hashlib.sha256(data[c_s:c_e]).hexdigest())
                q = sg_e
            if not digests:
                fail("signing block without signers")
            return sorted(digests)
    fail("no v2, v3 or v3.1 signature block")


def cmd_fingerprint(args):
    for d in fingerprints(args.apk):
        print(d)
    return 0


def cmd_cert(args):
    base = load_baseline(args.baseline)
    pins = (base.get("signing") or {}).get("cert_sha256")
    if args.require_pin and pin_problem(base):
        print("GATE ~ signing.cert_sha256: no valid pin")
        return 1
    apksigner = find_tool("apksigner")
    if apksigner is None:
        fail("apksigner not found")
    r = run_tool([apksigner, "verify", "--print-certs", args.apk])
    if r.returncode != 0:
        print("gate: apksigner verify failed (unsigned or invalid APK): %s" % r.stderr.strip()[:500])
        return 1
    got = sorted(set(m.lower() for m in re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})", r.stdout)))
    if not got:
        print("gate: no certificate digest in apksigner output")
        return 1
    if got != sorted(pins or []):
        print("gate: CERTIFICATE MISMATCH: APK %s, pinned %s" % (got, pins))
        return 1
    print("gate: OK, signed by the pinned certificate %s" % ", ".join(got))
    return 0


# ---------------------------------------------------------------------------------------------------------------------

def main(argv=None):
    ap = argparse.ArgumentParser(prog="gate.py")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("generate", "check", "cert", "inspect", "fingerprint"):
        p = sub.add_parser(name)
        p.add_argument("--apk", required=True)
        if name in ("generate", "check", "cert"):
            p.add_argument("--baseline", default="gate/baseline.json")
        if name in ("generate", "check"):
            p.add_argument("--deps", required=True)
        if name in ("generate", "check", "inspect"):
            p.add_argument("--require-tools", action="store_true")
        if name in ("check", "cert"):
            p.add_argument("--require-pin", action="store_true")
        if name == "generate":
            p.add_argument("--out")
        if name == "check":
            p.add_argument("--generated")
            p.add_argument("--compare-to")
    args = ap.parse_args(argv)
    handlers = {"generate": cmd_generate, "check": cmd_check, "cert": cmd_cert, "inspect": cmd_inspect,
                "fingerprint": cmd_fingerprint}
    try:
        return handlers[args.cmd](args)
    except GateError as e:
        print("gate: CANNOT CHECK: %s" % e)
        if in_actions():
            print("::error title=Release gate::%s" % gh_escape("cannot check: %s" % e))
        return 2
    except (MemoryError, RecursionError) as e:
        print("gate: CANNOT CHECK: %s" % type(e).__name__)
        return 2
    except (struct.error, IndexError, KeyError, ValueError, UnicodeError, OverflowError, zipfile.BadZipFile) as e:
        print("gate: CANNOT CHECK: malformed input: %s: %s" % (type(e).__name__, e))
        return 2


if __name__ == "__main__":
    sys.exit(main())
