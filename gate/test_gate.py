"""Tests for gate.py. Synthetic APKs are built here (compiled XML, dex, resource table, signing block); the SDK tools
are never called, because runners have ANDROID_HOME."""

import contextlib
import hashlib
import io
import json
import os
import struct
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gate  # noqa: E402

A = gate.ANDROID_NS


# ---- encoders ---------------------------------------------------------------------------------------------------

def string_pool(strings):
    offsets, data = [], b""
    for s in strings:
        offsets.append(len(data))
        enc = s.encode("utf-16-le")
        data += struct.pack("<H", len(enc) // 2) + enc + b"\0\0"
    while len(data) % 4:
        data += b"\0"
    body = struct.pack("<IIIII", len(strings), 0, 0, 28 + 4 * len(strings), 0)
    body += b"".join(struct.pack("<I", o) for o in offsets) + data
    return struct.pack("<HHI", 1, 28, 8 + len(body)) + body


def ref(resid):
    return ("ref", resid)


def axml(root):
    """root = (tag, [(ns, name, value[, resid])], [children or ('#text', s)]). resid None = the known ID for android."""
    id_names, texts = [], []

    def collect(el):
        tag, attrs, kids = el
        for a in attrs:
            ns, name = a[0], a[1]
            rid = a[3] if len(a) > 3 else (gate.ATTR_IDS.get(name) if ns == A else None)
            if rid:
                if (name, rid) not in id_names:
                    id_names.append((name, rid))
        for k in kids:
            if isinstance(k, tuple) and k[0] != "#text":
                collect(k)

    collect(root)
    pool = [n for n, _ in id_names]
    index = {}

    def s(x):
        if x is None:
            return 0xFFFFFFFF
        if x not in index:
            index[x] = len(pool)
            pool.append(x)
        return index[x]

    chunks = []

    def emit(el):
        tag, attrs, kids = el
        enc = []
        for a in attrs:
            ns, name, value = a[0], a[1], a[2]
            rid = a[3] if len(a) > 3 else (gate.ATTR_IDS.get(name) if ns == A else None)
            name_i = id_names.index((name, rid)) if rid else s("\x00" + name)
            if isinstance(value, bool):
                raw, dtype, data = 0xFFFFFFFF, 0x12, 0xFFFFFFFF if value else 0
            elif isinstance(value, int):
                raw, dtype, data = 0xFFFFFFFF, 0x10, value
            elif isinstance(value, tuple):
                raw, dtype, data = 0xFFFFFFFF, 0x01, value[1]
            else:
                raw = s(value)
                dtype, data = 0x03, raw
            enc.append(struct.pack("<IIIHBBI", s(ns), name_i, raw, 8, 0, dtype, data))
        ext = struct.pack("<IIHHHHHH", 0xFFFFFFFF, s(tag), 20, 20, len(attrs), 0, 0, 0)
        body = struct.pack("<II", 1, 0xFFFFFFFF) + ext + b"".join(enc)
        chunks.append(struct.pack("<HHI", 0x0102, 16, 8 + len(body)) + body)
        for k in kids:
            if k[0] == "#text":
                b = struct.pack("<II", 1, 0xFFFFFFFF) + struct.pack("<I", s(k[1])) + struct.pack("<HBBI", 8, 0, 0, 0)
                chunks.append(struct.pack("<HHI", 0x0104, 16, 8 + len(b)) + b)
            else:
                emit(k)
        b = struct.pack("<IIII", 1, 0xFFFFFFFF, 0xFFFFFFFF, s(tag))
        chunks.append(struct.pack("<HHI", 0x0103, 16, 8 + len(b)) + b)

    emit(root)
    # Attribute names without an ID were stored with a \0 prefix to keep them apart from ID names; strip it.
    names = [p[1:] if p.startswith("\x00") else p for p in pool]
    sp = string_pool(names)
    rm = b"".join(struct.pack("<I", r) for _, r in id_names)
    rmap = struct.pack("<HHI", 0x0180, 8, 8 + len(rm)) + rm
    body = sp + rmap + b"".join(chunks)
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body


def uleb(n):
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            return out + bytes([b])


def dex(strings, types, version=b"035"):
    strings = sorted(set(strings) | set(types))
    n = len(strings)
    ids_off = 0x70
    type_off = ids_off + 4 * n
    data_off = type_off + 4 * len(types)
    data, offs = b"", []
    for s in strings:
        offs.append(data_off + len(data))
        enc = s.encode("utf-8")
        data += uleb(len(s)) + enc + b"\0"
    header = bytearray(0x70)
    header[0:8] = b"dex\n" + version + b"\0"
    struct.pack_into("<IIII", header, 0x38, n, ids_off, len(types), type_off)
    return bytes(header) + b"".join(struct.pack("<I", o) for o in offs) + \
        b"".join(struct.pack("<I", strings.index(t)) for t in types) + data


def arsc(pkg_id, type_id, values):
    """values: list of strings; entry i is a string value pointing at values[i] in the global pool."""
    gpool = string_pool(values)
    count = len(values)
    cfg = struct.pack("<I", 64) + b"\0" * 60
    hsz = 20 + len(cfg)
    entries = b""
    offsets = []
    for i in range(count):
        offsets.append(len(entries))
        entries += struct.pack("<HHI", 8, 0, i) + struct.pack("<HBBI", 8, 0, 0x03, i)
    estart = hsz + 4 * count
    tbody = struct.pack("<BBHII", type_id, 0, 0, count, estart) + cfg + b"".join(struct.pack("<I", o) for o in offsets) + entries
    tchunk = struct.pack("<HHI", 0x0201, hsz, 8 + len(tbody)) + tbody
    tstr, kstr = string_pool(["xml"]), string_pool(["k%d" % i for i in range(count)])
    name = "app".encode("utf-16-le").ljust(256, b"\0")
    phdr = struct.pack("<I", pkg_id) + name + struct.pack("<IIIII", 288, 1, 288 + len(tstr), count, 0)
    pbody = phdr + tstr + kstr + tchunk
    pchunk = struct.pack("<HHI", 0x0200, 288, 8 + len(pbody)) + pbody
    body = struct.pack("<I", 1) + gpool + pchunk
    return struct.pack("<HHI", 0x0002, 12, 8 + len(body)) + body[0:]


def manifest(perms=("android.permission.INTERNET",), app_attrs=None, comps=None, extra=None, pkg="app.example"):
    app_attrs = app_attrs if app_attrs is not None else [(A, "allowBackup", False), (A, "dataExtractionRules", ref(0x7F010000))]
    kids = [("uses-sdk", [(A, "minSdkVersion", 35), (A, "targetSdkVersion", 36)], [])]
    kids += [("uses-permission", [(A, "name", p)], []) for p in perms]
    kids += extra or []
    kids.append(("application", app_attrs, comps if comps is not None else [
        ("activity", [(A, "name", ".MainActivity"), (A, "exported", True)], [
            ("intent-filter", [], [
                ("action", [(A, "name", "android.intent.action.MAIN")], []),
                ("category", [(A, "name", "android.intent.category.LAUNCHER")], []),
            ]),
        ]),
        ("activity", [(A, "name", ".Hidden")], []),
    ]))
    return axml(("manifest", [(None, "package", pkg)], kids))


def make_apk(path, man, dex_bytes=None, extra=None):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("AndroidManifest.xml", man)
        z.writestr("classes.dex", dex_bytes if dex_bytes is not None else dex(["https://discord.com/api/v9"], ["Lapp/example/Main;"]))
        for k, v in (extra or {}).items():
            z.writestr(k, v)


DEPS_LOG = """> Task :app:dependencies
releaseRuntimeClasspath - Resolved configuration for runtime for variant: release
+--- project :core
|    \\--- org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0
|         \\--- org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0 (*)
+--- androidx.core:core-ktx:1.16.0
|    \\--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
+--- androidx.compose:compose-bom:2025.09.00
|    \\--- androidx.compose.ui:ui:1.9.0 (c)
\\--- androidx.room:room-runtime:{strictly 2.7.2} -> 2.7.2

gate-file-dependencies: done :
gate-file-dependencies: done :core
gate-file-dependencies: done :app
"""

SEED = {"schema": 2, "app": "Test", "variant": "release",
        "dependencies": {"project": ":app", "configuration": "releaseRuntimeClasspath"},
        "hosts": [], "network_api": [], "signing": {"cert_sha256": None}, "scan": {"extra_text_paths": []}, "notes": []}


def read(path, mode="r"):
    with open(path, mode) as f:
        return f.read()


def write(path, data, mode="w"):
    with open(path, mode) as f:
        f.write(data)


def run(argv):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = gate.main(argv)
    return code, out.getvalue()


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.d = self.tmp.name
        self.env = {k: os.environ.pop(k) for k in ("ANDROID_HOME", "ANDROID_SDK_ROOT", "AAPT2", "APKSIGNER", "GITHUB_ACTIONS") if k in os.environ}

    def tearDown(self):
        os.environ.update(self.env)
        self.tmp.cleanup()

    def p(self, name):
        return os.path.join(self.d, name)

    def write(self, name, text):
        with open(self.p(name), "w") as f:
            f.write(text)
        return self.p(name)

    def seed(self, **over):
        b = json.loads(json.dumps(SEED))
        b.update(over)
        return self.write("baseline.json", json.dumps(b))


class ManifestTest(Base):
    def facts(self, man, extra=None):
        make_apk(self.p("a.apk"), man, extra=extra)
        return gate.apk_facts(self.p("a.apk"), SEED, require_tools=False, tools=False)

    def test_basic_facts(self):
        f = self.facts(manifest())
        self.assertEqual(f["package"], "app.example")
        self.assertEqual(f["sdk"], {"min": 35, "target": 36})
        self.assertEqual(f["permissions"]["requested"], ["android.permission.INTERNET"])
        self.assertFalse(f["debuggable"])
        self.assertEqual(f["backup"], {"allow_backup": False, "full_backup_content": "absent",
                                       "data_extraction_rules": True, "backup_agent": None})
        self.assertEqual(f["network"], {"uses_cleartext_traffic": "absent", "network_security_config": None})
        self.assertEqual([c["name"] for c in f["components"]], ["app.example.MainActivity"])  # unexported activity left out
        self.assertEqual(f["components"][0]["actions"], ["android.intent.action.MAIN"])
        self.assertIn("https://discord.com", f["hosts"])

    def test_services_receivers_providers_always_listed_with_defaults(self):
        comps = [
            ("service", [(A, "name", "x.S"), (A, "foregroundServiceType", 0x40000000)], []),
            ("receiver", [(A, "name", ".R")], [("intent-filter", [], [("action", [(A, "name", "a.B")], [])])]),
            ("provider", [(A, "name", "x.P"), (A, "authorities", "b;a")], []),
        ]
        f = self.facts(manifest(comps=comps))
        by = {c["type"]: c for c in f["components"]}
        self.assertFalse(by["service"]["exported"])
        self.assertEqual(by["service"]["foreground_service_type"], "0x40000000")
        self.assertTrue(by["receiver"]["exported"])  # has an intent filter
        self.assertFalse(by["provider"]["exported"])  # targetSdk >= 17
        self.assertEqual(by["provider"]["authorities"], ["a", "b"])

    def test_permission_flags_and_declared(self):
        extra = [("uses-permission", [(A, "name", "android.permission.NEARBY_WIFI_DEVICES"), (A, "usesPermissionFlags", 0x10000)], []),
                 ("uses-permission-sdk-23", [(A, "name", "p.Q"), (A, "maxSdkVersion", 28)], []),
                 ("permission", [(A, "name", "app.example.P"), (A, "protectionLevel", 2)], [])]
        f = self.facts(manifest(extra=extra))
        self.assertIn("android.permission.NEARBY_WIFI_DEVICES;usesPermissionFlags=neverForLocation", f["permissions"]["requested"])
        self.assertIn("p.Q;maxSdkVersion=28", f["permissions"]["requested"])
        self.assertEqual(f["permissions"]["declared"], ["app.example.P;protectionLevel=0x2"])

    def test_decoy_in_foreign_namespace_is_its_own_key(self):
        comps = [("receiver", [(A, "name", ".R"), ("http://evil.example", "exported", "false", 0)], [])]
        f = self.facts(manifest(comps=comps))
        self.assertFalse(f["components"][0]["exported"])  # the decoy does not count; no filter, so not exported

    def test_id_carries_the_meaning_whatever_the_namespace(self):
        comps = [("receiver", [(A, "name", ".R"), ("http://evil.example", "exported", True, gate.ATTR_IDS["exported"])], [])]
        f = self.facts(manifest(comps=comps))
        self.assertTrue(f["components"][0]["exported"])

    def test_name_disagreeing_with_its_id_is_refused(self):
        comps = [("receiver", [(A, "name", ".R"), (A, "visible", True, gate.ATTR_IDS["exported"])], [])]
        with self.assertRaises(gate.GateError):
            self.facts(manifest(comps=comps))

    def test_known_name_without_its_id_is_refused(self):
        comps = [("receiver", [(A, "name", ".R"), (A, "exported", True, 0)], [])]
        with self.assertRaises(gate.GateError):
            self.facts(manifest(comps=comps))

    def test_non_literal_boolean_is_refused(self):
        with self.assertRaises(gate.GateError):
            self.facts(manifest(app_attrs=[(A, "allowBackup", ref(0x7F020000))]))

    def test_duplicate_attribute_is_refused(self):
        comps = [("receiver", [(A, "name", ".R"), (A, "exported", True), ("http://x", "exported", False, gate.ATTR_IDS["exported"])], [])]
        with self.assertRaises(gate.GateError):
            self.facts(manifest(comps=comps))

    def test_queries_and_features(self):
        extra = [("queries", [], [("package", [(A, "name", "com.x")], []),
                                  ("intent", [], [("action", [(A, "name", "a.V")], []), ("data", [(A, "scheme", "https")], [])])]),
                 ("uses-feature", [(A, "name", "android.hardware.camera"), (A, "required", False)], [])]
        f = self.facts(manifest(extra=extra))
        self.assertEqual(f["queries"], ["intent:action:a.V;data:scheme=https", "package:com.x"])
        self.assertEqual(f["uses_features"], ["android.hardware.camera;required=false"])

    def test_network_security_config_resolves_through_the_table(self):
        nsc = axml(("network-security-config", [], [
            ("base-config", [(None, "cleartextTrafficPermitted", False)], [
                ("trust-anchors", [], [("certificates", [(None, "src", "system")], [])])]),
            ("domain-config", [], [("domain", [(None, "includeSubdomains", True)], [("#text", "discord.com")])]),
        ]))
        man = manifest(app_attrs=[(A, "networkSecurityConfig", ref(0x7F010000))])
        f = self.facts(man, extra={"res/xml/nsc.xml": nsc, "resources.arsc": arsc(0x7F, 1, ["res/xml/nsc.xml"])})
        cfg = f["network"]["network_security_config"]
        self.assertEqual([c["kind"] for c in cfg], ["base-config", "domain-config"])
        self.assertEqual(cfg[0]["cleartext"], "false")
        self.assertEqual(cfg[0]["trust_anchors"], ["system"])
        self.assertEqual(cfg[1]["domains"], ["discord.com;includeSubdomains"])

    def test_meta_data_file_reference_is_a_digest(self):
        xml = axml(("paths", [], [("files-path", [(None, "name", "x"), (None, "path", ".")], [])]))
        app_attrs = [(A, "allowBackup", False)]
        comps = [("meta-data", [(A, "name", "m.v"), (A, "value", "hello")], []),
                 ("meta-data", [(A, "name", "m.r"), (A, "resource", ref(0x7F010000))], []),
                 ("provider", [(A, "name", "x.FP"), (A, "authorities", "a")], [
                     ("meta-data", [(A, "name", "android.support.FILE_PROVIDER_PATHS"), (A, "resource", ref(0x7F010000))], [])])]
        f = self.facts(manifest(app_attrs=app_attrs, comps=comps), extra={"res/xml/p.xml": xml, "resources.arsc": arsc(0x7F, 1, ["res/xml/p.xml"])})
        digest = hashlib.sha256("\n".join(gate.canonical_lines(gate.decode_axml(xml))).encode()).hexdigest()
        self.assertIn("m.v;value=hello", f["meta_data"])
        self.assertIn("m.r;resource=sha256:" + digest, f["meta_data"])
        self.assertEqual(f["provider_resources"][0]["sha256"], digest)
        self.assertEqual(gate.canonical_lines(gate.decode_axml(xml))[1], '[1,"files-path",[["name","x"],["path","."]]]')


class ApkWalkTest(Base):
    def test_dex_network_types_desugaring_and_version(self):
        d = dex(["x"], ["Ljava/net/Socket;", "Landroid/net/Uri;", "Lokhttp3/OkHttpClient;", "Lj$/time/Instant;", "[Ljava/net/URL;"])
        make_apk(self.p("a.apk"), manifest(), dex_bytes=d)
        f = gate.apk_facts(self.p("a.apk"), SEED, False, tools=False)
        self.assertEqual(f["network_api"], ["Ljava/net/Socket;", "Lokhttp3/OkHttpClient;", "[Ljava/net/URL;"])
        self.assertTrue(f["desugared_library"])
        make_apk(self.p("b.apk"), manifest(), dex_bytes=dex([], [], version=b"041"))
        with self.assertRaises(gate.GateError):
            gate.apk_facts(self.p("b.apk"), SEED, False, tools=False)

    def test_native_libs_extra_code_and_nesting(self):
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, "w") as z:
            z.writestr("classes.dex", dex(["s"], []))
            z.writestr("lib/x.so", b"\x7fELFxx")
        make_apk(self.p("a.apk"), manifest(), extra={"lib/arm64-v8a/libz.so": b"\x7fELF", "assets/x.jar": inner.getvalue(),
                                                       "classes2.dex": dex(["t"], []), "assets/sneaky.bin": dex(["u"], [])})
        f = gate.apk_facts(self.p("a.apk"), SEED, False, tools=False)
        self.assertEqual(f["native_libs"], ["assets/x.jar!lib/x.so", "lib/arm64-v8a/libz.so"])
        self.assertEqual(f["extra_code"], ["assets/sneaky.bin", "assets/x.jar", "assets/x.jar!classes.dex"])

    def test_nesting_deeper_than_three_is_refused(self):
        data = b"x"
        for i in range(5):
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, "w") as z:
                z.writestr("n%d.zip" % i if i else "f.txt", data)
            data = buf.getvalue()
        make_apk(self.p("a.apk"), manifest(), extra={"assets/deep.zip": data})
        with self.assertRaises(gate.GateError):
            gate.apk_facts(self.p("a.apk"), SEED, False, tools=False)

    def test_truncated_manifest_is_exit_2_not_a_traceback(self):
        make_apk(self.p("a.apk"), manifest()[:60])
        self.write("deps.log", DEPS_LOG)
        code, out = run(["check", "--apk", self.p("a.apk"), "--deps", self.p("deps.log"), "--baseline", self.seed()])
        self.assertEqual(code, 2)
        self.assertIn("CANNOT CHECK", out)


class HostsTest(unittest.TestCase):
    def test_urls_bare_hosts_and_exclusions(self):
        text = ("see https://User@Discord.com:443/api/v9?x and wss://gateway.discord.gg/ plus cdn.example.net, "
                "java.net kotlin.io androidx.core.app java.vm.info android.com java.sun.com http://schemas.android.com/apk "
                "app.example.ui.host.com 2.5.4.3 1.3.6.1 1.1.1.1 0.0.0.0 1.200.3.4 127.0.0.1 10.0.0.1 Dispatchers.IO "
                "API.Example.COM .githubusercontent.com")
        self.assertEqual(sorted(gate.scan_hosts(text, "app.example")), sorted([
            "https://discord.com:443", "wss://gateway.discord.gg", "cdn.example.net", "android.com", "java.sun.com",
            "1.1.1.1", "0.0.0.0", "1.200.3.4", "10.0.0.1", ".githubusercontent.com",
        ]))


class DepsTest(Base):
    def test_parse(self):
        mods, files, des = gate.parse_deps(DEPS_LOG, "releaseRuntimeClasspath", ":app")
        self.assertEqual(mods, ["androidx.annotation:annotation", "androidx.compose:compose-bom", "androidx.core:core-ktx",
                                "androidx.room:room-runtime", "org.jetbrains.kotlinx:kotlinx-serialization-core",
                                "org.jetbrains.kotlinx:kotlinx-serialization-json"])
        self.assertEqual((files, des), ([], []))

    def test_failed_and_missing_markers(self):
        with self.assertRaises(gate.GateError):
            gate.parse_deps(DEPS_LOG.replace("core-ktx:1.16.0", "core-ktx:1.16.0 FAILED"), "releaseRuntimeClasspath", ":app")
        with self.assertRaises(gate.GateError):
            gate.parse_deps(DEPS_LOG.replace("done :app", "done :x"), "releaseRuntimeClasspath", ":app")
        with self.assertRaises(gate.GateError):
            gate.parse_deps(DEPS_LOG, "debugRuntimeClasspath", ":app")

    def test_file_and_desugaring_lines(self):
        log = DEPS_LOG + "gate-file-dependency: :app libs/x.jar\ngate-desugaring: :app com.android.tools:desugar_jdk_libs\n"
        _m, files, des = gate.parse_deps(log, "releaseRuntimeClasspath", ":app")
        self.assertEqual(files, ["libs/x.jar"])
        self.assertEqual(des, ["com.android.tools:desugar_jdk_libs"])


class CommandsTest(Base):
    def setUp(self):
        super().setUp()
        make_apk(self.p("a.apk"), manifest())
        self.deps = self.write("deps.log", DEPS_LOG)

    def generate(self):
        b = self.seed()
        code, _ = run(["generate", "--apk", self.p("a.apk"), "--deps", self.deps, "--baseline", b])
        self.assertEqual(code, 0)
        return b

    def test_seed_fails_then_generated_baseline_with_notes_passes(self):
        b = self.seed()
        code, out = run(["check", "--apk", self.p("a.apk"), "--deps", self.deps, "--baseline", b])
        self.assertEqual(code, 1)
        self.assertIn("GATE + permissions.requested: android.permission.INTERNET", out)
        self.generate()
        code, out = run(["check", "--apk", self.p("a.apk"), "--deps", self.deps, "--baseline", b])
        self.assertEqual(code, 1)
        self.assertIn("GATE ~ hosts: https://discord.com has no note", out)
        data = json.loads(read(b))
        for h in data["hosts"]:
            h["note"] = "the one endpoint"
        write(b, gate.dump(data))
        code, out = run(["check", "--apk", self.p("a.apk"), "--deps", self.deps, "--baseline", b])
        self.assertEqual(code, 0, out)
        self.assertIn("gate: OK", out)

    def test_require_pin_and_new_permission(self):
        b = self.generate()
        data = json.loads(read(b))
        for h in data["hosts"]:
            h["note"] = "n"
        write(b, gate.dump(data))
        code, out = run(["check", "--require-pin", "--apk", self.p("a.apk"), "--deps", self.deps, "--baseline", b])
        self.assertEqual(code, 1)
        self.assertIn("GATE ~ signing.cert_sha256", out)
        make_apk(self.p("a.apk"), manifest(perms=("android.permission.INTERNET", "android.permission.VIBRATE")))
        code, out = run(["check", "--apk", self.p("a.apk"), "--deps", self.deps, "--baseline", b, "--generated", self.p("g/gen.json")])
        self.assertEqual(code, 1)
        self.assertIn("GATE + permissions.requested: android.permission.VIBRATE", out)
        self.assertIn("android.permission.VIBRATE", read(self.p("g/gen.json")))

    def test_compare_to_warns_but_never_fails(self):
        b = self.generate()
        data = json.loads(read(b))
        for h in data["hosts"]:
            h["note"] = "n"
        write(b, gate.dump(data))
        os.environ["GITHUB_ACTIONS"] = "true"
        try:
            code, out = run(["check", "--apk", self.p("a.apk"), "--deps", self.deps, "--baseline", b, "--compare-to", self.p("none.json")])
            self.assertEqual(code, 0)
            self.assertIn("::warning title=Gate baseline changed in this PR::", out)
            base = self.write("base.json", json.dumps(SEED))
            code, out = run(["check", "--apk", self.p("a.apk"), "--deps", self.deps, "--baseline", b, "--compare-to", base])
            self.assertEqual(code, 0)
            self.assertIn("BASELINE + hosts: https://discord.com (note: n)", out)
        finally:
            del os.environ["GITHUB_ACTIONS"]

    def test_note_rules(self):
        b = self.seed(notes=[{"field": "hosts", "suffix": "discord.com", "note": "the endpoint"}])
        run(["generate", "--apk", self.p("a.apk"), "--deps", self.deps, "--baseline", b])
        self.assertEqual(json.loads(read(b))["hosts"], [{"value": "https://discord.com", "note": "(rule discord.com) the endpoint"}])
        for bad in ({"field": "hosts", "suffix": "com", "note": "x"}, {"field": "hosts", "suffix": "co.uk", "note": "x"},
                    {"field": "hosts", "suffix": "github.io", "note": "x"}, {"field": "network_api", "prefix": "Ljava/net/", "note": "x"},
                    {"field": "hosts", "match": ".*", "note": "x"}, {"field": "hosts", "suffix": "a.com", "note": ""}):
            code, _ = run(["generate", "--apk", self.p("a.apk"), "--deps", self.deps, "--baseline", self.seed(notes=[bad])])
            self.assertEqual(code, 2, bad)

    def test_missing_tools_with_require_tools_is_exit_2(self):
        code, out = run(["check", "--require-tools", "--apk", self.p("a.apk"), "--deps", self.deps, "--baseline", self.seed()])
        self.assertEqual(code, 2)
        self.assertIn("aapt2 not found", out)

    def test_inspect(self):
        code, out = run(["inspect", "--apk", self.p("a.apk")])
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out)["package"], "app.example")


class FingerprintTest(Base):
    def test_v2_block(self):
        def lp(b):
            return struct.pack("<I", len(b)) + b
        cert = b"0\x82fake-der-certificate"
        signed = lp(b"") + lp(lp(cert)) + lp(b"")
        value = lp(lp(lp(signed) + lp(b"") + lp(b"pubkey")))
        pair = struct.pack("<QI", 4 + len(value), gate.SIG_V2) + value
        size = len(pair) + 8 + 16
        block = struct.pack("<Q", size) + pair + struct.pack("<Q", size) + b"APK Sig Block 42"
        make_apk(self.p("u.apk"), manifest())
        data = read(self.p("u.apk"), "rb")
        eocd = data.rfind(b"PK\x05\x06")
        cd = struct.unpack_from("<I", data, eocd + 16)[0]
        signed_apk = bytearray(data[:cd] + block + data[cd:])
        struct.pack_into("<I", signed_apk, eocd + len(block) + 16, cd + len(block))
        write(self.p("s.apk"), bytes(signed_apk), "wb")
        code, out = run(["fingerprint", "--apk", self.p("s.apk")])
        self.assertEqual(code, 0)
        self.assertEqual(out.strip(), hashlib.sha256(cert).hexdigest())
        code, _ = run(["fingerprint", "--apk", self.p("u.apk")])
        self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()
