"""Unit tests for gate.py (python3 -m unittest discover -s gate). Standard library only."""

import hashlib
import io
import json
import os
import stat
import struct
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout, redirect_stderr
from unittest import mock

import gate

HERE = os.path.dirname(os.path.abspath(__file__))
REAL_MANIFEST = os.path.join(HERE, "testdata", "prikey-0.17.0-AndroidManifest.xml")
REAL_TABLE = os.path.join(HERE, "testdata", "prikey-0.17.0-resources.arsc")
NONE = 0xFFFFFFFF

DEPS = """
> Task :app:dependencies

releaseRuntimeClasspath - Resolved configuration for runtime for variant: release
+--- project :core:model
|    \\--- org.jetbrains.kotlin:kotlin-stdlib:2.2.21 (*)
+--- org.jetbrains.kotlin:kotlin-stdlib:{strictly 2.2.21} -> 2.2.21 (c)
+--- com.example:declared-only (n)
\\--- org.jetbrains.kotlin:kotlin-stdlib:2.2.0 -> 2.2.21

(c) - A dependency constraint, not a dependency.

> Task :gateFileDependencies
gate-file-dependencies: done :
> Task :app:gateFileDependencies
gate-file-dependency: app/libs/vendor.jar
gate-file-dependencies: done :app
"""


# ---- Builders ------------------------------------------------------------------------------------


def lp(data):
    return struct.pack("<I", len(data)) + data


def string_pool(strings):
    """A UTF-8 ResStringPool chunk."""
    data, offsets = b"", []
    for s in strings:
        offsets.append(len(data))
        raw = s.encode()
        data += bytes([len(s), len(raw)]) + raw + b"\0"
    while len(data) % 4:
        data += b"\0"
    header = 28
    body = b"".join(struct.pack("<I", o) for o in offsets) + data
    return struct.pack("<HHIIIIII", 0x0001, header, header + len(body), len(strings), 0, 0x100,
                       header + 4 * len(strings), 0) + body


def axml(tree, wrong_ids=None):
    """Compiled XML from (tag, [(android?, name, kind, value)], [children]), laid out like aapt2: Android
    attribute names first in the string pool, matched by the resource map."""
    android, other = [], []

    def collect(node):
        if node[0] == "#text":
            other.append(node[1])
            return
        tag, attrs, children = node
        other.append(tag)
        for is_android, name, kind, value in attrs:
            # is_android None: Android's resource ID without the Android namespace. A string: another namespace.
            if isinstance(is_android, str):
                other.append(is_android)
            (android if is_android is True or is_android is None else other).append(name)
            if kind in ("str", "typed"):
                other.append(value)
            elif kind == "rawtyped":
                other.extend(value)
            elif kind == "rawref":
                other.append(value[0])
        for child in children:
            collect(child)

    collect(tree)
    android = list(dict.fromkeys(android))
    strings = android + [s for s in dict.fromkeys([gate.ANDROID_NS] + other) if s not in android]
    idx = {s: i for i, s in enumerate(strings)}
    ids = [(wrong_ids or {}).get(n, gate.ATTR_IDS.get(n, 0x0101FFFF)) for n in android]
    rmap = struct.pack("<HHI", 0x0180, 8, 8 + 4 * len(ids)) + b"".join(struct.pack("<I", i) for i in ids)

    def attr(is_android, name, kind, value):
        ns = idx[is_android] if isinstance(is_android, str) else idx[gate.ANDROID_NS] if is_android else NONE
        if kind == "str":
            return struct.pack("<IIIHBBI", ns, idx[name], idx[value], 8, 0, 0x03, idx[value])
        if kind == "rawref":  # a reference that also carries a raw string: value is (raw, resource ID)
            return struct.pack("<IIIHBBI", ns, idx[name], idx[value[0]], 8, 0, 0x01, value[1])
        if kind == "rawtyped":  # raw string and typed value disagree: value is (raw, typed)
            return struct.pack("<IIIHBBI", ns, idx[name], idx[value[0]], 8, 0, 0x03, idx[value[1]])
        if kind == "typed":  # a string value with only its typed copy, no raw string
            return struct.pack("<IIIHBBI", ns, idx[name], NONE, 8, 0, 0x03, idx[value])
        dtype, data = {"int": (0x10, value), "bool": (0x12, NONE if value else 0), "ref": (0x01, value)}[kind]
        return struct.pack("<IIIHBBI", ns, idx[name], NONE, 8, 0, dtype, data)

    def element(node):
        if node[0] == "#text":
            return struct.pack("<HHIIIIHBBI", 0x0104, 16, 28, 1, NONE, idx[node[1]], 8, 0, 0x03, 0)
        tag, attrs, children = node
        body = struct.pack("<IIHHHHHH", NONE, idx[tag], 20, 20, len(attrs), 0, 0, 0) + b"".join(attr(*a) for a in attrs)
        out = struct.pack("<HHIII", 0x0102, 16, 16 + len(body), 1, NONE) + body
        for child in children:
            out += element(child)
        return out + struct.pack("<HHIIIII", 0x0103, 16, 24, 1, NONE, NONE, idx[tag])

    body = string_pool(strings) + rmap + element(tree)
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body


def a(name, kind, value):
    return (True, name, kind, value)


def manifest(permissions=(), app_attrs=(), components=(), queries=None, sdk=(31, 36), wrong_ids=None, features=()):
    uses_sdk = [a("minSdkVersion", "int", sdk[0])] + ([a("targetSdkVersion", "int", sdk[1])] if sdk[1] else [])
    children = [("uses-sdk", uses_sdk, [])]
    for p in permissions:
        children.append(("uses-permission", [a("name", "str", p[0])] + list(p[1:]), []))
    for f in features:
        children.append(("uses-feature", list(f), []))
    if queries:
        children.append(("queries", [], queries))
    children.append(("application", [a("allowBackup", "bool", False)] + list(app_attrs), list(components)))
    return axml(("manifest", [(False, "package", "str", "com.example.app")], children), wrong_ids)


def dex(strings=("hello",), types=("Lcom/example/A;",), version=b"035"):
    """A dex file with only the string and type tables filled in."""
    strings = sorted(set(strings) | set(types))
    header = bytearray(0x70)
    header[:8] = b"dex\n" + version + b"\0"
    data, offsets = b"", []
    base = 0x70 + 4 * len(strings) + 4 * len(types)
    for s in strings:
        offsets.append(base + len(data))
        data += bytes([len(s)]) + s.encode() + b"\0"
    struct.pack_into("<IIII", header, 0x38, len(strings), 0x70, len(types), 0x70 + 4 * len(strings))
    return (bytes(header) + b"".join(struct.pack("<I", o) for o in offsets)
            + b"".join(struct.pack("<I", strings.index(t)) for t in types) + data)


def zip_bytes(entries):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as z:
        for name, data in entries.items():
            z.writestr(name, data)
    return out.getvalue()


def resource_table(values):
    """A resources.arsc with package 0x7f. `values` maps a resource ID to one value or a list of them (one per
    configuration): ("str", text) (a file path is a string), ("int", n), ("bool", b) or ("ref", ID)."""
    pool_strings, types = [], {}
    for rid, per_config in values.items():
        for config, (kind, v) in enumerate(per_config if isinstance(per_config, list) else [per_config]):
            if kind == "str" and v not in pool_strings:
                pool_strings.append(v)
            types.setdefault((rid >> 16) & 0xFF, {}).setdefault(config, {})[rid & 0xFFFF] = (kind, v)
    chunks = b""
    for type_id, configs in sorted(types.items()):
        for _, entries in sorted(configs.items()):
            count = max(entries) + 1
            offsets, body = [], b""
            for i in range(count):
                if i not in entries:
                    offsets.append(NONE)
                    continue
                kind, v = entries[i]
                dtype, data = {"str": (0x03, pool_strings.index(v) if kind == "str" else 0), "int": (0x10, v),
                               "bool": (0x12, NONE if v else 0), "ref": (0x01, v)}[kind]
                offsets.append(len(body))
                body += struct.pack("<HHIHBBI", 8, 0, 0, 8, 0, dtype, data)
            header = struct.pack("<HHIBBHII", 0x0201, 24, 24 + 4 * count + len(body), type_id, 0, 0, count,
                                 24 + 4 * count) + struct.pack("<I", 4)
            chunks += header + b"".join(struct.pack("<I", o) for o in offsets) + body
    package = struct.pack("<HHII", 0x0200, 288, 288 + len(chunks), 0x7F) + b"\0" * 276 + chunks
    body = string_pool(pool_strings) + package
    return struct.pack("<HHII", 0x0002, 12, 12 + len(body), 1) + body


def write_apk(path, manifest_bytes=None, entries=None, **dex_args):
    files = {"AndroidManifest.xml": manifest_bytes or manifest(), "classes.dex": dex(**dex_args)}
    files.update(entries or {})
    with open(path, "wb") as f:
        f.write(zip_bytes(files))


def with_signing_block(data, cert, block_id=0x7109871A):
    """Inserts an APK Signing Block holding one v2 signer with the given certificate."""
    signed_data = lp(b"") + lp(lp(cert)) + lp(b"")
    signer = lp(signed_data) + lp(b"") + lp(b"")
    value = lp(lp(signer))
    pairs = struct.pack("<QI", len(value) + 4, block_id) + value
    size = len(pairs) + 24
    block = struct.pack("<Q", size) + pairs + struct.pack("<Q", size) + b"APK Sig Block 42"
    eocd = data.rfind(b"PK\x05\x06")
    cd = struct.unpack_from("<I", data, eocd + 16)[0]
    out = bytearray(data[:cd] + block + data[cd:])
    struct.pack_into("<I", out, eocd + len(block) + 16, cd + len(block))
    return bytes(out)


def read_json(path):
    with open(path) as f:
        return json.load(f)


def write_json(path, data):
    with open(path, "w") as f:
        json.dump(data, f)


class Case(unittest.TestCase):
    def setUp(self):
        # Synthetic APKs are not real builds: keep them away from any aapt2 or apksigner here. Tests that
        # need a tool install a fake one with self.tool().
        self.tools = {}
        patcher = mock.patch.object(gate, "sdk_tool", side_effect=lambda name: self.tools.get(name))
        patcher.start()
        self.addCleanup(patcher.stop)
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.deps = self.path("deps.txt")
        with open(self.deps, "w") as f:
            f.write(DEPS)
        self.baseline = self.path("baseline.json")
        write_json(self.baseline, {"schema": 2, "app": "test", "variant": "release",
                                   "dependencies": {"configuration": "releaseRuntimeClasspath"}})

    def path(self, name):
        return os.path.join(self.dir.name, name)

    def tool(self, name, script):
        path = self.path(name)
        with open(path, "w") as f:
            f.write("#!/bin/sh\n" + script)
        os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC)
        self.tools[name] = path

    def run_gate(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = gate.main(list(argv))
        return code, out.getvalue() + err.getvalue()

    def generate(self, apk_path):
        code, output = self.run_gate("generate", "--apk", apk_path, "--deps", self.deps, "--baseline", self.baseline)
        self.assertEqual(code, 0, output)

    def check(self, apk_path, *extra):
        return self.run_gate("check", "--apk", apk_path, "--deps", self.deps, "--baseline", self.baseline, *extra)

    def pin(self, certs):
        data = read_json(self.baseline)
        data["signing"] = {"cert_sha256": certs}
        write_json(self.baseline, data)


# ---- Tests ---------------------------------------------------------------------------------------


class DependenciesTest(unittest.TestCase):
    def test_reads_modules_and_skips_constraints_projects_and_unresolved_declarations(self):
        self.assertEqual(gate.dependency_modules(DEPS, "releaseRuntimeClasspath"), ["org.jetbrains.kotlin:kotlin-stdlib"])

    def test_missing_configuration_is_an_error(self):
        with self.assertRaises(gate.GateError):
            gate.dependency_modules(DEPS, "debugRuntimeClasspath")

    def test_failed_resolution_is_an_error(self):
        with self.assertRaises(gate.GateError):
            gate.dependency_modules("releaseRuntimeClasspath - x\n\\--- com.example:lib:1.0 FAILED\n", "releaseRuntimeClasspath")

    def test_unreadable_line_is_an_error_not_a_skip(self):
        with self.assertRaises(gate.GateError):
            gate.dependency_modules("releaseRuntimeClasspath - x\nsomething odd\n", "releaseRuntimeClasspath")

    def test_file_dependencies_and_their_missing_marker(self):
        self.assertEqual(gate.file_dependencies(DEPS, ":app"), (["app/libs/vendor.jar"], []))
        with self.assertRaises(gate.GateError):
            gate.file_dependencies("releaseRuntimeClasspath - x\n\\--- a:b:1\n", ":app")
        with self.assertRaises(gate.GateError):  # the root reported but the app module didn't
            gate.file_dependencies("gate-file-dependencies: done :\n", ":app")


class HostsTest(unittest.TestCase):
    def test_urls_are_reduced_to_scheme_and_authority(self):
        self.assertEqual(gate.hosts_in("see https://User@Example.COM:8443/path?q=1 now"), {"https://example.com:8443"})

    def test_bare_hosts_and_ipv4(self):
        self.assertEqual(gate.hosts_in("api.example.org or 10.111.0.1 or 1.1.1.1 or 1.200.3.4 or 0.0.0.0"),
                         {"api.example.org", "10.111.0.1", "1.1.1.1", "1.200.3.4", "0.0.0.0"})

    def test_real_hosts_under_package_words_are_kept(self):
        self.assertEqual(gate.hosts_in("android.com android.googleapis.com java.sun.com"),
                         {"android.com", "android.googleapis.com", "java.sun.com"})

    def test_package_names_identifiers_files_oids_and_namespaces_are_not_hosts(self):
        text = ("java.net kotlin.io android.app androidx.core.app kotlin.io.FilesKt Dispatchers.IO libfoo.so "
                "emoji.txt java.home java.vm.info user.home 1.2.3 2.5.4.3 1.3.6.1 API.Example.COM http://schemas.android.com")
        self.assertEqual(gate.hosts_in(text), set())

    def test_non_network_schemes_are_ignored(self):
        self.assertEqual(gate.hosts_in("content://com.example.provider file:///sdcard"), set())


class ChunkTest(unittest.TestCase):
    def test_nested_chunk_with_a_zero_header_is_an_error_not_a_hang(self):
        evil = struct.pack("<HHI", 0x0003, 8, 24) + struct.pack("<HHI", 0x0003, 0, 16) + b"\0" * 8
        with self.assertRaises(gate.GateError):
            gate.string_pools_in(evil)

    def pool_with_offsets(self, data, offsets):
        header = 28
        body = b"".join(struct.pack("<I", o) for o in offsets) + data
        return struct.pack("<HHIIIIII", 0x0001, header, header + len(body), len(offsets), 0, 0x100,
                           header + 4 * len(offsets), 0) + body

    def test_repeated_string_offsets_are_decoded_once(self):
        text = "x" * 1000
        pool = self.pool_with_offsets(bytes([0x83, 0xE8, 0x83, 0xE8]) + text.encode() + b"\0", [0] * 20000)
        strings = gate.string_pool(pool, 0)
        self.assertEqual(len(strings), 20000)
        self.assertEqual(strings[0], text)

    def test_overlapping_string_offsets_hit_the_budget(self):
        # Offsets into one long run of length bytes: each decodes to a long string.
        data = bytes([0x7F, 0x7F]) * 2000 + b"x" * 200
        pool = self.pool_with_offsets(data, list(range(0, 4000, 2)))
        with mock.patch.object(gate, "MAX_DECODED_STRING_BYTES", 100000):
            with self.assertRaises(gate.GateError):
                gate.string_pool(pool, 0)

    def test_dex_with_repeated_and_overlapping_strings(self):
        long_string = "y" * 120
        d = bytearray(dex(strings=[long_string], types=[]))
        count, offset = struct.unpack_from("<II", d, 0x38)
        first = struct.unpack_from("<I", d, offset)[0]
        repeated = bytes(d[:0x70]) + struct.pack("<I", first) * 5000
        repeated = bytearray(repeated + bytes(d[0x70 + 4 * count:]))
        shift = 4 * 5000 - 4 * count
        struct.pack_into("<IIII", repeated, 0x38, 5000, 0x70, 0, 0)
        for_offsets = struct.pack("<I", first + shift) * 5000
        repeated[0x70:0x70 + 4 * 5000] = for_offsets
        strings, _ = gate.dex_tables(bytes(repeated), "classes.dex")
        self.assertEqual(len(strings), 5000)
        self.assertEqual(strings[0], long_string)
        with mock.patch.object(gate, "MAX_DECODED_STRING_BYTES", 100):
            with self.assertRaises(gate.GateError):
                gate.dex_tables(dex(strings=[long_string], types=[]), "classes.dex")

    def test_real_resource_table_resolves_files(self):
        with open(REAL_TABLE, "rb") as f:
            table = f.read()
        self.assertEqual(len(gate.resource_files(table, 0x7F040000)), 1)  # Prikey's data extraction rules
        self.assertTrue(gate.resource_files(table, 0x7F040000)[0].startswith("res/"))
        self.assertEqual(gate.resource_files(table, 0x7F040099), [])


class NetworkSecurityConfigTest(unittest.TestCase):
    def test_trust_anchors_cleartext_domains_and_pins(self):
        xml = axml(("network-security-config", [], [
            ("base-config", [(False, "cleartextTrafficPermitted", "bool", False)], [
                ("trust-anchors", [], [("certificates", [(False, "src", "str", "system")], [])])]),
            ("domain-config", [(False, "cleartextTrafficPermitted", "bool", True)], [
                ("domain", [(False, "includeSubdomains", "bool", True)], [("#text", "lan.example")]),
                ("trust-anchors", [], [("certificates", [(False, "src", "str", "user")], []),
                                       ("certificates", [(False, "src", "ref", 0x7F0A0001)], [])]),
                ("pin-set", [(False, "expiration", "str", "2027-01-01")], [
                    ("pin", [(False, "digest", "str", "SHA-256")], [("#text", "AAAA")])])]),
            ("debug-overrides", [], [("trust-anchors", [], [("certificates", [(False, "src", "str", "user")], [])])]),
        ]))
        facts = gate.nsc_facts(xml, lambda rid: ["res/raw/lan_ca.crt"])
        by_kind = {c["kind"]: c for c in facts}
        self.assertEqual(by_kind["base-config"]["cleartext"], "false")
        self.assertEqual(by_kind["base-config"]["trust_anchors"], ["system"])
        self.assertEqual(by_kind["domain-config"]["cleartext"], "true")
        self.assertEqual(by_kind["domain-config"]["domains"], ["lan.example;includeSubdomains"])
        self.assertEqual(by_kind["domain-config"]["trust_anchors"], ["res/raw/lan_ca.crt", "user"])
        self.assertEqual(by_kind["domain-config"]["pins"], ["AAAA;expiration=2027-01-01"])
        self.assertEqual(by_kind["debug-overrides"]["trust_anchors"], ["user"])


class RealManifestTest(unittest.TestCase):
    def test_prikey_release_manifest(self):
        with open(REAL_MANIFEST, "rb") as f:
            facts = gate.manifest_facts(f.read())  # UTF-16 string pool, written by aapt2
        self.assertEqual(facts["package"], "io.github.stronghorse44.prikey")
        self.assertEqual(facts["sdk"], {"min": 31, "target": 37})
        self.assertFalse(facts["debuggable"])
        self.assertEqual(facts["backup"], {"allow_backup": False, "full_backup_content": "false",
                                           "data_extraction_rules": True, "backup_agent": None})
        self.assertEqual(facts["permissions"], {"requested": [], "declared": []})
        self.assertEqual([(c["type"], c["name"].rsplit(".", 1)[1], c["exported"]) for c in facts["components"]],
                         [("activity", "SettingsActivity", True), ("service", "KeyboardService", True)])
        self.assertEqual(facts["components"][1]["permission"], "android.permission.BIND_INPUT_METHOD")
        self.assertEqual(facts["components"][0]["categories"], ["android.intent.category.LAUNCHER"])


class ManifestTest(Case):
    def facts(self, **kwargs):
        return gate.manifest_facts(manifest(**kwargs))

    def test_components_filters_and_provider_capabilities(self):
        facts = self.facts(components=[
            ("receiver", [a("name", "str", ".Boot")], [("intent-filter", [], [
                ("action", [a("name", "str", "android.intent.action.BOOT_COMPLETED")], [])])]),
            ("activity", [a("name", "str", ".Link"), a("exported", "bool", True)], [("intent-filter", [], [
                ("action", [a("name", "str", "android.intent.action.VIEW")], []),
                ("category", [a("name", "str", "android.intent.category.BROWSABLE")], []),
                ("data", [a("scheme", "str", "https"), a("host", "str", "example.com")], [])])]),
            ("activity", [a("name", "str", ".Inner")], []),
            ("provider", [a("name", "str", ".Files"), a("exported", "bool", False), a("authorities", "str", "com.example.files"),
                          a("grantUriPermissions", "bool", True)], []),
            ("service", [a("name", "str", ".Track"), a("foregroundServiceType", "int", 0x8)], []),
        ])
        by_name = {c["name"].rsplit(".", 1)[1]: c for c in facts["components"]}
        self.assertNotIn("Inner", by_name)  # unexported activities are not recorded
        self.assertTrue(by_name["Boot"]["exported"])  # an intent filter exports by default
        self.assertEqual(by_name["Link"]["categories"], ["android.intent.category.BROWSABLE"])
        self.assertEqual(by_name["Link"]["data"], ["scheme=https;host=example.com"])
        self.assertEqual((by_name["Files"]["grant_uri_permissions"], by_name["Files"]["authorities"]), (True, ["com.example.files"]))
        self.assertEqual(by_name["Track"]["foreground_service_type"], "0x8")

    def test_queries_are_not_components(self):
        facts = self.facts(queries=[
            ("provider", [a("authorities", "str", "com.other.provider")], []),
            ("package", [a("name", "str", "com.other.app")], []),
            ("intent", [], [("action", [a("name", "str", "android.intent.action.SEND")], []),
                            ("data", [a("mimeType", "str", "text/plain")], [])]),
        ])
        self.assertEqual(facts["components"], [])
        self.assertEqual(facts["queries"], ["intent:action:android.intent.action.SEND,data:mimeType=text/plain",
                                            "package:com.other.app", "provider:com.other.provider"])

    def test_permission_flags_and_network_flags(self):
        facts = self.facts(permissions=[("android.permission.BLUETOOTH_SCAN", a("usesPermissionFlags", "int", 0x10000)),
                                        ("android.permission.READ_EXTERNAL_STORAGE", a("maxSdkVersion", "int", 32))],
                           app_attrs=[a("usesCleartextTraffic", "bool", False), a("networkSecurityConfig", "ref", 0x7F100000)])
        self.assertEqual(facts["permissions"]["requested"], [
            "android.permission.BLUETOOTH_SCAN;usesPermissionFlags=neverForLocation",
            "android.permission.READ_EXTERNAL_STORAGE;maxSdkVersion=32"])
        self.assertEqual(facts["network"]["uses_cleartext_traffic"], "false")
        self.assertEqual(facts["network"]["network_security_config"][:2], (0x01, 0x7F100000))  # apk_facts resolves it

    def test_missing_target_sdk_falls_back_to_min_sdk(self):
        facts = self.facts(sdk=(16, None), components=[("provider", [a("name", "str", ".Old"), a("authorities", "str", "x")], [])])
        self.assertTrue(facts["components"][0]["exported"])  # providers were exported by default before API 17

    def test_boolean_given_as_a_resource_reference_fails_closed(self):
        with self.assertRaises(gate.GateError):
            self.facts(components=[("activity", [a("name", "str", ".A"), a("exported", "ref", 0x7F050001)], [])])
        with self.assertRaises(gate.GateError):
            self.facts(app_attrs=[a("debuggable", "ref", 0x7F050002)])

    def test_attribute_with_the_wrong_resource_id_fails_closed(self):
        with self.assertRaises(gate.GateError):
            self.facts(wrong_ids={"exported": 0x01010999},
                       components=[("activity", [a("name", "str", ".A"), a("exported", "bool", True)], [])])

    def test_attribute_is_read_by_resource_id_whatever_its_namespace(self):
        facts = self.facts(components=[("service", [a("name", "str", ".S"), (None, "exported", "bool", True)], [])])
        self.assertTrue(facts["components"][0]["exported"])

    def test_component_without_a_name_fails_closed(self):
        with self.assertRaises(gate.GateError):
            self.facts(components=[("service", [a("exported", "bool", True)], [])])


class ApkTest(Case):
    def test_same_apk_passes_and_generate_is_deterministic(self):
        write_apk(self.path("a.apk"), strings=["https://b.example.com", "https://a.example.com"])
        data = read_json(self.baseline)
        data["notes"] = [{"field": "hosts", "suffix": "example.com", "note": "test hosts"}]
        write_json(self.baseline, data)
        self.generate(self.path("a.apk"))
        first = read_json(self.baseline)
        self.generate(self.path("a.apk"))
        self.assertEqual(read_json(self.baseline), first)
        self.assertEqual(first["dependencies"]["files"], ["app/libs/vendor.jar"])
        code, output = self.check(self.path("a.apk"))
        self.assertEqual(code, 0, output)

    def test_new_permission_fails_with_a_plus_line(self):
        write_apk(self.path("a.apk"))
        self.generate(self.path("a.apk"))
        write_apk(self.path("b.apk"), manifest(permissions=[("android.permission.INTERNET",)]))
        code, output = self.check(self.path("b.apk"))
        self.assertEqual(code, 1)
        self.assertIn("GATE + permissions.requested: android.permission.INTERNET", output)

    def test_losing_never_for_location_is_a_change(self):
        write_apk(self.path("a.apk"), manifest(permissions=[("android.permission.BLUETOOTH_SCAN", a("usesPermissionFlags", "int", 0x10000))]))
        self.generate(self.path("a.apk"))
        write_apk(self.path("b.apk"), manifest(permissions=[("android.permission.BLUETOOTH_SCAN",)]))
        code, output = self.check(self.path("b.apk"))
        self.assertEqual(code, 1)
        self.assertIn("GATE - permissions.requested: android.permission.BLUETOOTH_SCAN;usesPermissionFlags=neverForLocation", output)

    def test_native_code_extra_code_network_type_host_and_debuggable_fail(self):
        write_apk(self.path("a.apk"))
        self.generate(self.path("a.apk"))
        nested = zip_bytes({"classes.dex": dex(types=["Ljava/net/Socket;"]), "x86/tool": b"\x7fELF" + b"\0" * 12})
        write_apk(self.path("b.apk"), manifest(app_attrs=[a("debuggable", "bool", True)]),
                  entries={"lib/arm64-v8a/libx.so": b"\x7fELF", "assets/blob.bin": b"\x7fELF\0\0\0\0",
                           "assets/plugin.jar": nested},
                  strings=["wss://push.example.net/socket"], types=["Ljava/net/Socket;"])
        code, output = self.check(self.path("b.apk"))
        self.assertEqual(code, 1)
        for line in ("GATE + native_libs: lib/arm64-v8a/libx.so",
                     "GATE + native_libs: assets/blob.bin",
                     "GATE + native_libs: assets/plugin.jar!/x86/tool",
                     "GATE + extra_code: assets/plugin.jar",
                     "GATE + extra_code: assets/plugin.jar!/classes.dex",
                     "GATE + network_api: Ljava/net/Socket;",
                     "GATE + hosts: wss://push.example.net",
                     "GATE ~ debuggable: false -> true"):
            self.assertIn(line, output)

    def test_notes_survive_regeneration_and_rules_fill_new_ones(self):
        write_apk(self.path("a.apk"), strings=["https://youtrack.jetbrains.com/issue"])
        data = read_json(self.baseline)
        data["notes"] = [{"field": "hosts", "suffix": "jetbrains.com", "note": "Kotlin stdlib error text"}]
        write_json(self.baseline, data)
        self.generate(self.path("a.apk"))
        self.assertEqual(read_json(self.baseline)["hosts"],
                         [{"value": "https://youtrack.jetbrains.com", "note": "(rule jetbrains.com) Kotlin stdlib error text"}])
        data = read_json(self.baseline)
        data["notes"], data["hosts"][0]["note"] = [], "hand-written"
        write_json(self.baseline, data)
        self.generate(self.path("a.apk"))
        self.assertEqual(read_json(self.baseline)["hosts"][0]["note"], "hand-written")

    def test_catch_all_note_rules_are_refused(self):
        write_apk(self.path("a.apk"))
        for rule in ({"field": "hosts", "suffix": "com", "note": "x"},
                     {"field": "hosts", "suffix": "co.uk", "note": "x"},
                     {"field": "hosts", "suffix": "github.io", "note": "x"},
                     {"field": "network_api", "prefix": "Ljava/net/", "note": "x"},
                     {"field": "network_api", "prefix": "Landroid/net/http/", "note": "x"},
                     {"field": "hosts", "match": ".*", "note": "x"},
                     {"field": "network_api", "prefix": "Ljava/", "note": "x"},
                     {"field": "hosts", "suffix": "example.com", "note": ""}):
            data = read_json(self.baseline)
            data["notes"] = [rule]
            write_json(self.baseline, data)
            code, output = self.run_gate("generate", "--apk", self.path("a.apk"), "--deps", self.deps, "--baseline", self.baseline)
            self.assertEqual(code, 2, (rule, output))

    def test_an_entry_without_a_note_fails_the_check(self):
        write_apk(self.path("a.apk"), strings=["https://api.example.com/v1"])
        self.generate(self.path("a.apk"))
        code, output = self.check(self.path("a.apk"))
        self.assertEqual(code, 1)
        self.assertIn("GATE ~ hosts: https://api.example.com has no note", output)
        data = read_json(self.baseline)
        data["hosts"][0]["note"] = "the update check"
        write_json(self.baseline, data)
        self.assertEqual(self.check(self.path("a.apk"))[0], 0)

    def test_inflating_entry_over_the_cap_is_exit_2(self):
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("AndroidManifest.xml", manifest())
            z.writestr("classes.dex", dex())
            z.writestr("assets/bomb.dex", b"dex\n" + b"\0" * (2 * 1024 * 1024))
        with open(self.path("a.apk"), "wb") as f:
            f.write(out.getvalue())
        self.assertLess(len(out.getvalue()), 64 * 1024)
        with mock.patch.object(gate, "MAX_ENTRY_BYTES", 1024 * 1024):
            code, output = self.check(self.path("a.apk"))
        self.assertEqual(code, 2, output)
        self.assertIn("limit", output)

    def test_dex_container_is_exit_2(self):
        write_apk(self.path("a.apk"), version=b"041")
        code, output = self.check(self.path("a.apk"))
        self.assertEqual(code, 2, output)
        self.assertIn("dex container", output)

    def test_malformed_manifest_is_exit_2_not_a_traceback(self):
        write_apk(self.path("a.apk"), manifest()[:200])
        self.generate_code = None
        code, output = self.check(self.path("a.apk"))
        self.assertEqual(code, 2, output)
        self.assertIn("the gate could not run", output)

    def test_missing_apk_is_exit_2(self):
        code, _ = self.check(self.path("missing.apk"))
        self.assertEqual(code, 2)


class ToolsTest(Case):
    BADGING = ("package: name='com.example.app' versionCode='1' versionName='1.0'\\n"
               "sdkVersion:'31'\\ntargetSdkVersion:'36'\\n")

    def aapt2(self, badging, permissions):
        self.tool("aapt2", f'case "$2" in badging) printf "{badging}";; permissions) printf "{permissions}";; esac\n')

    def test_cross_check_reads_real_permission_lines(self):
        write_apk(self.path("a.apk"), manifest(permissions=[("android.permission.INTERNET",),
                                                            ("android.permission.BLUETOOTH_SCAN", a("usesPermissionFlags", "int", 0x10000))]))
        self.aapt2(self.BADGING, "package: com.example.app\\n"
                                 "uses-permission: name='android.permission.INTERNET'\\n"
                                 "uses-permission: name='android.permission.BLUETOOTH_SCAN' usesPermissionFlags='65536'\\n")
        code, output = self.run_gate("inspect", "--apk", self.path("a.apk"), "--require-tools")
        self.assertEqual(code, 0, output)

    def test_aapt2_disagreeing_is_exit_2(self):
        write_apk(self.path("a.apk"))
        self.aapt2(self.BADGING, "package: com.example.app\\nuses-permission: name='android.permission.CAMERA'\\n")
        code, output = self.run_gate("inspect", "--apk", self.path("a.apk"), "--require-tools")
        self.assertEqual(code, 2, output)
        self.assertIn("disagree", output)

    def test_missing_aapt2_with_require_tools_is_exit_2(self):
        write_apk(self.path("a.apk"))
        code, output = self.check(self.path("a.apk"), "--require-tools")
        self.assertEqual(code, 2, output)
        self.assertIn("aapt2 not found", output)

    def test_cert_match_mismatch_null_and_unsigned(self):
        write_apk(self.path("a.apk"))
        self.generate(self.path("a.apk"))
        self.tool("apksigner", 'echo "Signer #1 certificate SHA-256 digest: ' + "AB" * 32 + '"\n')
        cert = ("cert", "--apk", self.path("a.apk"), "--baseline", self.baseline, "--require-pin")
        self.assertEqual(self.run_gate(*cert)[0], 1)  # null pin
        self.pin(["ab" * 32])
        self.assertEqual(self.run_gate(*cert)[0], 0)  # match (case-insensitive)
        self.pin(["cd" * 32])
        code, output = self.run_gate(*cert)
        self.assertEqual(code, 1)  # mismatch
        self.assertIn("is not the pinned", output)
        self.tool("apksigner", 'echo "DOES NOT VERIFY" >&2; exit 1\n')
        code, output = self.run_gate(*cert)
        self.assertEqual(code, 1)  # unsigned
        self.assertIn("Never publish an unsigned APK", output)

    def test_require_pin_fails_check_on_a_null_pin(self):
        write_apk(self.path("a.apk"))
        self.generate(self.path("a.apk"))
        code, output = self.check(self.path("a.apk"), "--require-pin")
        self.assertEqual(code, 1, output)
        self.assertIn("GATE ~ signing.cert_sha256: signing.cert_sha256 is null or empty", output)
        self.pin(["ab" * 32])
        self.assertEqual(self.check(self.path("a.apk"), "--require-pin")[0], 0)

    def test_compare_to_lists_baseline_changes_including_the_pin_and_configuration(self):
        write_apk(self.path("a.apk"))
        self.generate(self.path("a.apk"))
        self.pin(["ab" * 32])
        base = self.path("base.json")
        write_json(base, read_json(self.baseline))
        self.pin(["cd" * 32])
        data = read_json(self.baseline)
        data["scan"]["extra_text_paths"] = ["assets/*.json"]
        write_json(self.baseline, data)
        code, output = self.check(self.path("a.apk"), "--compare-to", base)
        self.assertEqual(code, 0, output)
        self.assertIn("BASELINE + signing: " + "cd" * 32, output)
        self.assertIn("BASELINE - signing: " + "ab" * 32, output)
        self.assertIn("BASELINE + scan.extra_text_paths: assets/*.json", output)
        with mock.patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}):
            code, output = self.check(self.path("a.apk"), "--compare-to", self.path("absent.json"))
        # The message names the baseline file the gate was given, not a fixed path.
        self.assertIn(f"::warning title=Gate baseline changed in this PR::the base branch has no {self.baseline}, so", output)
        self.assertIn(f"(looked for {self.path('absent.json')})", output)
        release = self.path("gate/baseline.release.json")
        os.makedirs(os.path.dirname(release))
        write_json(release, read_json(self.baseline))
        code, output = self.run_gate("check", "--apk", self.path("a.apk"), "--deps", self.deps, "--baseline", release,
                                     "--compare-to", self.path("absent.json"))
        self.assertEqual(code, 0, output)
        self.assertIn(f"the base branch has no {release}, so", output)

    def test_fingerprint_reads_the_signing_block(self):
        cert = b"0\x82 a certificate in DER"
        with open(self.path("signed.apk"), "wb") as f:
            f.write(with_signing_block(zip_bytes({"AndroidManifest.xml": manifest()}), cert))
        code, output = self.run_gate("fingerprint", "--apk", self.path("signed.apk"))
        self.assertEqual(code, 0, output)
        self.assertIn(hashlib.sha256(cert).hexdigest(), output)
        with open(self.path("v3.apk"), "wb") as f:
            f.write(with_signing_block(zip_bytes({"AndroidManifest.xml": manifest()}), cert, 0xF05368C0))
        code, output = self.run_gate("fingerprint", "--apk", self.path("v3.apk"))
        self.assertIn("Scheme v3", output)
        self.assertIn(hashlib.sha256(cert).hexdigest(), output)
        write_apk(self.path("unsigned.apk"))
        self.assertEqual(self.run_gate("fingerprint", "--apk", self.path("unsigned.apk"))[0], 2)


# ---- Gate 2.1 ------------------------------------------------------------------------------------

FILE_PATHS = ("paths", [], [("files-path", [(False, "name", "str", "docs"), (False, "path", "str", "docs/")], []),
                            ("cache-path", [(False, "name", "str", "share"), (False, "path", "str", "share/")], [])])
PROVIDER = ("provider", [a("name", "str", "androidx.core.content.FileProvider"), a("exported", "bool", False),
                         a("authorities", "str", "com.example.app.files"), a("grantUriPermissions", "bool", True)],
            [("meta-data", [a("name", "str", "android.support.FILE_PATHS"), a("resource", "ref", 0x7F0A0001)], [])])


def jl(depth, tag, *pairs):
    """The canonical line of an element: compact JSON, as gate.canonical_xml writes it."""
    return json.dumps([depth, tag, [list(p) for p in pairs]], separators=(",", ":"))


def optional_sections(baseline):
    return {k: baseline[k] for k in ("uses_features", "meta_data", "provider_resources", "assets") if k in baseline}


class NetworkArrayTest(Case):
    def test_array_types_are_network_types_primitives_and_other_arrays_are_not(self):
        write_apk(self.path("a.apk"), types=["[Ljavax/net/ssl/TrustManager;", "[[Ljava/net/URL;", "Ljava/net/Socket;",
                                             "[I", "[Lcom/example/A;", "[Landroid/net/Uri;", "[Landroid/net/Network;"])
        self.assertEqual(gate.apk_facts(self.path("a.apk"))["network_api"],
                         ["Ljava/net/Socket;", "[Landroid/net/Network;", "[Ljavax/net/ssl/TrustManager;", "[[Ljava/net/URL;"])

    def test_a_new_array_type_fails_the_check_and_a_library_rule_covers_arrays(self):
        write_apk(self.path("a.apk"), types=["Lokhttp3/internal/Util;", "Ljava/net/Socket;"])
        data = read_json(self.baseline)
        data["notes"] = [{"field": "network_api", "prefix": "Lokhttp3/internal/", "note": "OkHttp internals, module :net"}]
        write_json(self.baseline, data)
        self.generate(self.path("a.apk"))
        data = read_json(self.baseline)
        for e in data["network_api"]:
            e["note"] = e["note"] or "the old socket reference"
        write_json(self.baseline, data)
        self.assertEqual(self.check(self.path("a.apk"))[0], 0)
        write_apk(self.path("b.apk"), types=["Lokhttp3/internal/Util;", "Ljava/net/Socket;", "[Ljava/net/Socket;",
                                             "[Lokhttp3/internal/Util;"])
        code, output = self.check(self.path("b.apk"))
        self.assertEqual(code, 1)
        self.assertIn("GATE + network_api: [Ljava/net/Socket;", output)
        self.assertIn("GATE + network_api: [Lokhttp3/internal/Util;", output)
        self.generate(self.path("b.apk"))
        noted = {e["value"]: e["note"] for e in read_json(self.baseline)["network_api"]}
        self.assertTrue(noted["[Lokhttp3/internal/Util;"].startswith("(rule Lokhttp3/internal/)"))  # the rule reaches arrays
        self.assertEqual(noted["[Ljava/net/Socket;"], "")  # a framework array still needs its own note


class SuffixHostTest(Case):
    def test_leading_dot_suffix_literals_are_recorded_with_their_dot(self):
        self.assertEqual(gate.hosts_in('endsWith(".githubusercontent.com") or "*.example.org" or ".co.uk"'),
                         {".githubusercontent.com", ".example.org", ".co.uk"})

    def test_suffix_pass_does_not_double_count_or_pick_up_identifiers(self):
        self.assertEqual(gate.hosts_in("api.example.com"), {"api.example.com"})  # not also ".example.com"
        self.assertEqual(gate.hosts_in("x .com a.b .c.d  \\.example\\.com .kotlin.io .android.app .java.net .Foo.IO .bar.Baz.com"), set())
        self.assertEqual(gate.hosts_in("https://.example.com/x"), {"https://.example.com"})  # a URL stays a URL
        self.assertEqual(gate.hosts_in("see .android.com"), {".android.com"})  # package words ending .com stay hosts

    def test_a_suffix_literal_is_a_host_entry_with_a_note_and_rules_reach_it(self):
        write_apk(self.path("a.apk"), strings=[".tracker.example.net", "api.example.net"])
        data = read_json(self.baseline)
        data["notes"] = [{"field": "hosts", "suffix": "example.net", "note": "tracker list"}]
        write_json(self.baseline, data)
        self.generate(self.path("a.apk"))
        self.assertEqual([e["value"] for e in read_json(self.baseline)["hosts"]], [".tracker.example.net", "api.example.net"])
        self.assertTrue(all(e["note"].startswith("(rule example.net)") for e in read_json(self.baseline)["hosts"]))
        write_apk(self.path("b.apk"), strings=[".tracker.example.net", ".other.example.net", "api.example.net"])
        code, output = self.check(self.path("b.apk"))
        self.assertEqual(code, 1)
        self.assertIn("GATE + hosts: .other.example.net", output)

    def test_own_package_name_is_not_a_suffix_host(self):
        write_apk(self.path("a.apk"), strings=[".com.example.app", ".com.example.app.files", "com.example.app"])
        self.assertEqual(gate.apk_facts(self.path("a.apk"))["hosts"], [])


class AssetDigestTest(Case):
    ENTRIES = {"assets/models/face.tflite": b"model v1", "assets/ocr/rec.bin": b"ocr data", "assets/other.bin": b"x",
               "assets/ocr/sub/keys.txt": b"abc", "lib/ignored.tflite.txt": b"no"}

    def assets(self, entries=None, **kw):
        write_apk(self.path("a.apk"), entries=entries or self.ENTRIES)
        return gate.apk_facts(self.path("a.apk"), **kw)["assets"]

    def test_default_patterns_pin_model_files_with_sha256_and_size(self):
        self.assertEqual(self.assets(), [{"path": "assets/models/face.tflite", "size": 8,
                                          "sha256": hashlib.sha256(b"model v1").hexdigest()}])

    def test_default_patterns_cover_onnx_ort_and_task(self):
        found = self.assets({"assets/a.onnx": b"1", "assets/b.ort": b"2", "assets/c.task": b"3", "assets/d.bin": b"4"})
        self.assertEqual([e["path"] for e in found], ["assets/a.onnx", "assets/b.ort", "assets/c.task"])

    def test_configured_patterns_replace_the_defaults_and_star_crosses_directories(self):
        found = self.assets(asset_digest_paths=("assets/ocr/*",))
        self.assertEqual([e["path"] for e in found], ["assets/ocr/rec.bin", "assets/ocr/sub/keys.txt"])
        self.assertEqual(self.assets(asset_digest_paths=()), [])

    def test_only_top_level_entries_are_digested_not_those_inside_nested_archives(self):
        found = self.assets({"assets/plugin.jar": zip_bytes({"model.tflite": b"inner"})})
        self.assertEqual(found, [])

    def test_a_swapped_model_is_a_baseline_change_and_a_new_one_is_an_addition(self):
        data = read_json(self.baseline)
        data["scan"] = {"extra_text_paths": [], "asset_digest_paths": ["assets/models/*", "assets/ocr/*"]}
        write_json(self.baseline, data)
        write_apk(self.path("a.apk"), entries=self.ENTRIES)
        self.generate(self.path("a.apk"))
        baseline = read_json(self.baseline)
        self.assertEqual(baseline["scan"]["asset_digest_paths"], ["assets/models/*", "assets/ocr/*"])  # config kept
        self.assertEqual([e["path"] for e in baseline["assets"]],
                         ["assets/models/face.tflite", "assets/ocr/rec.bin", "assets/ocr/sub/keys.txt"])
        self.assertEqual(self.check(self.path("a.apk"))[0], 0)
        write_apk(self.path("b.apk"), entries=dict(self.ENTRIES, **{"assets/models/face.tflite": b"model v2",
                                                                    "assets/models/new.tflite": b"n"}))
        code, output = self.check(self.path("b.apk"))
        self.assertEqual(code, 1)
        self.assertIn("GATE ~ assets: ", output)
        self.assertIn(hashlib.sha256(b"model v1").hexdigest(), output)
        self.assertIn(hashlib.sha256(b"model v2").hexdigest(), output)
        self.assertIn("GATE + assets: assets/models/new.tflite", output)
        write_apk(self.path("c.apk"), entries={"assets/ocr/rec.bin": b"ocr data", "assets/ocr/sub/keys.txt": b"abc"})
        code, output = self.check(self.path("c.apk"))
        self.assertIn("GATE - assets: assets/models/face.tflite", output)

    def test_baseline_diff_in_a_pull_request_shows_the_changed_digest(self):
        write_apk(self.path("a.apk"), entries=self.ENTRIES)
        self.generate(self.path("a.apk"))
        base = self.path("base.json")
        write_json(base, read_json(self.baseline))
        data = read_json(self.baseline)
        data["assets"][0]["sha256"] = "0" * 64
        write_json(self.baseline, data)
        code, output = self.check(self.path("a.apk"), "--compare-to", base)
        self.assertEqual(code, 1)  # the APK no longer matches the edited baseline
        self.assertIn("BASELINE ~ assets: ", output)

    def test_bad_configuration_duplicates_and_caps_are_exit_2(self):
        write_apk(self.path("a.apk"), entries=self.ENTRIES)
        for bad in ("assets/*", [""], [1]):
            data = read_json(self.baseline)
            data["scan"] = {"extra_text_paths": [], "asset_digest_paths": bad}
            write_json(self.baseline, data)
            self.assertEqual(self.check(self.path("a.apk"))[0], 2, bad)
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w") as z, self.assertWarns(UserWarning):
            z.writestr("AndroidManifest.xml", manifest())
            z.writestr("classes.dex", dex())
            z.writestr("assets/m.tflite", b"one")
            z.writestr("assets/m.tflite", b"two")
        with open(self.path("dup.apk"), "wb") as f:
            f.write(out.getvalue())
        with self.assertRaises(gate.GateError):
            gate.apk_facts(self.path("dup.apk"))
        write_apk(self.path("big.apk"), entries={"assets/m.tflite": b"x" * 5000})
        with mock.patch.object(gate, "MAX_ENTRY_BYTES", 1000):
            with self.assertRaises(gate.GateError):
                gate.apk_facts(self.path("big.apk"))
        with mock.patch.object(gate, "MAX_TOTAL_BYTES", 1000):
            with self.assertRaises(gate.GateError):
                gate.apk_facts(self.path("big.apk"))


class ManifestExtrasTest(Case):
    def facts(self, **kwargs):
        return gate.manifest_facts(manifest(**kwargs))

    def test_uses_feature_names_required_gl_version_and_feature_version(self):
        facts = self.facts(features=[
            [a("name", "str", "android.hardware.camera.any"), a("required", "bool", False)],
            [a("name", "str", "android.hardware.touchscreen")],
            [a("glEsVersion", "int", 0x30000)],
            [a("name", "str", "android.hardware.vulkan.version"), a("version", "int", 0x400003), a("required", "bool", True)],
        ])
        self.assertEqual(facts["uses_features"], [
            "android.hardware.camera.any;required=false",
            "android.hardware.touchscreen;required=true",
            "android.hardware.vulkan.version;version=0x400003;required=true",
            "glEsVersion=0x30000;required=true"])

    def test_uses_feature_fails_closed(self):
        with self.assertRaises(gate.GateError):  # neither a name nor a GL version
            self.facts(features=[[a("required", "bool", True)]])
        with self.assertRaises(gate.GateError):  # `required` can't be a resource reference
            self.facts(features=[[a("name", "str", "x"), a("required", "ref", 0x7F050001)]])

    def test_application_meta_data_literals_and_references(self):
        facts = self.facts(components=[
            ("meta-data", [a("name", "str", "k.string"), a("value", "str", "hello")], []),
            ("meta-data", [a("name", "str", "k.int"), a("value", "int", 3)], []),
            ("meta-data", [a("name", "str", "k.bool"), a("value", "bool", True)], []),
            ("meta-data", [a("name", "str", "k.bare")], []),
            ("meta-data", [a("name", "str", "k.ref"), a("value", "ref", 0x7F050001)], []),
            ("meta-data", [a("name", "str", "k.res"), a("resource", "ref", 0x7F0A0001)], []),
            ("activity", [a("name", "str", ".A"), a("exported", "bool", True)],
             [("meta-data", [a("name", "str", "k.component"), a("value", "str", "not application-level")], [])]),
        ])
        self.assertEqual(facts["meta_data"], ["k.bare", "k.bool;value=true", "k.int;value=3", "k.string;value=hello"])
        self.assertEqual(facts["meta_refs"], [("k.ref", "value", 0x7F050001), ("k.res", "resource", 0x7F0A0001)])
        self.assertEqual(facts["provider_meta"], [])

    def test_meta_data_without_a_name_fails_closed(self):
        with self.assertRaises(gate.GateError):
            self.facts(components=[("meta-data", [a("value", "str", "x")], [])])

    def test_provider_meta_data_resources_are_collected_for_apk_facts(self):
        facts = self.facts(components=[PROVIDER])
        self.assertEqual(facts["provider_meta"], [("androidx.core.content.FileProvider", "android.support.FILE_PATHS", 0x7F0A0001)])

    def test_resource_attribute_id_matches_a_real_manifest(self):
        # Prikey's real manifest (aapt2 output) has meta-data android:resource; its resource map must give the
        # ID the gate assumes. (value, glEsVersion and version were read the same way from other repos' APKs;
        # no fixture holds them.)
        with open(REAL_MANIFEST, "rb") as f:
            b = f.read()
        o, strings, rmap = gate.u16(b, 2), [], []
        while o + 8 <= len(b):
            ctype, size = gate.u16(b, o), gate.u32(b, o + 4)
            if ctype == 0x0001:
                strings = gate.string_pool(b, o)
            elif ctype == 0x0180:
                rmap = [gate.u32(b, o + 8 + 4 * i) for i in range((size - 8) // 4)]
            o += size
        self.assertEqual(rmap[strings.index("resource")], gate.ATTR_IDS["resource"])


class ResourceResolutionTest(Case):
    def apk(self, name, table, paths_xml=FILE_PATHS, extra=None, components=(PROVIDER,), **kw):
        entries = {"resources.arsc": table, "res/xml/file_paths.xml": axml(paths_xml)}
        entries.update(extra or {})
        write_apk(self.path(name), manifest(components=list(components), **kw), entries=entries)
        return gate.apk_facts(self.path(name))

    def test_canonical_xml_ignores_attribute_order_and_shows_each_element(self):
        one = axml(("paths", [], [("files-path", [(False, "name", "str", "d"), (False, "path", "str", "d/")], [])]))
        two = axml(("paths", [], [("files-path", [(False, "path", "str", "d/"), (False, "name", "str", "d")], [])]))
        self.assertEqual(gate.canonical_xml(one), [jl(0, "paths"), jl(1, "files-path", ("name", "d"), ("path", "d/"))])
        self.assertEqual(gate.canonical_xml(one), gate.canonical_xml(two))

    def test_provider_resource_is_recorded_by_content_not_by_file_name(self):
        table = resource_table({0x7F0A0001: ("str", "res/xml/file_paths.xml")})
        facts = self.apk("a.apk", table)
        (entry,) = facts["provider_resources"]
        self.assertEqual(entry["provider"], "androidx.core.content.FileProvider")
        self.assertEqual(entry["meta"], "android.support.FILE_PATHS")
        self.assertEqual(entry["elements"], [jl(0, "paths"), jl(1, "files-path", ("name", "docs"), ("path", "docs/")),
                                             jl(1, "cache-path", ("name", "share"), ("path", "share/"))])
        self.assertEqual(entry["sha256"], hashlib.sha256("\n".join(entry["elements"]).encode()).hexdigest())
        self.assertNotIn("resource", entry)
        # A release build may shorten the file name (res/8K.xml) and reorder attributes: same digest.
        renamed = resource_table({0x7F0A0001: ("str", "res/8K.xml")})
        reordered = ("paths", [], [("files-path", [(False, "path", "str", "docs/"), (False, "name", "str", "docs")], []),
                                   ("cache-path", [(False, "path", "str", "share/"), (False, "name", "str", "share")], [])])
        write_apk(self.path("b.apk"), manifest(components=[PROVIDER]),
                  entries={"resources.arsc": renamed, "res/8K.xml": axml(reordered)})
        self.assertEqual(gate.apk_facts(self.path("b.apk"))["provider_resources"], facts["provider_resources"])

    def test_widening_the_sharing_boundary_changes_the_baseline(self):
        table = resource_table({0x7F0A0001: ("str", "res/xml/file_paths.xml")})
        self.apk("a.apk", table)
        self.generate(self.path("a.apk"))
        self.assertEqual(len(read_json(self.baseline)["provider_resources"]), 1)
        self.assertEqual(self.check(self.path("a.apk"))[0], 0)
        widened = ("paths", [], FILE_PATHS[2] + [("root-path", [(False, "name", "str", "root"), (False, "path", "str", "")], [])])
        self.apk("b.apk", table, widened)
        code, output = self.check(self.path("b.apk"))
        self.assertEqual(code, 1)
        self.assertIn("GATE ~ provider_resources: ", output)
        self.assertIn("root-path", output)

    def test_configuration_variants_are_one_entry_and_identical_ones_count_once(self):
        both = resource_table({0x7F0A0001: [("str", "res/xml/file_paths.xml"), ("str", "res/xml-v26/file_paths.xml")]})
        same = self.apk("a.apk", both, extra={"res/xml-v26/file_paths.xml": axml(FILE_PATHS)})["provider_resources"]
        self.assertEqual(len(same), 1)
        self.assertNotIn("---", same[0]["elements"])
        other = ("paths", [], [("external-path", [(False, "name", "str", "e"), (False, "path", "str", "e/")], [])])
        differ = self.apk("b.apk", both, extra={"res/xml-v26/file_paths.xml": axml(other)})["provider_resources"]
        self.assertEqual(len(differ), 1)
        self.assertIn("---", differ[0]["elements"])

    def test_provider_resource_that_is_not_a_file_or_does_not_resolve_is_exit_2(self):
        for table in (resource_table({0x7F0A0001: ("int", 5)}), resource_table({0x7F0A0002: ("str", "x")})):
            with self.assertRaises(gate.GateError):
                self.apk("a.apk", table)

    def test_raw_resource_files_are_digested_as_bytes(self):
        table = resource_table({0x7F0A0001: ("str", "res/raw/paths.txt")})
        write_apk(self.path("a.apk"), manifest(components=[PROVIDER]),
                  entries={"resources.arsc": table, "res/raw/paths.txt": b"plain"})
        (entry,) = gate.apk_facts(self.path("a.apk"))["provider_resources"]
        self.assertEqual(entry["elements"], ["raw sha256 " + hashlib.sha256(b"plain").hexdigest()])

    def test_application_meta_data_references_resolve_to_a_content_digest_or_a_value(self):
        table = resource_table({0x7F0A0001: ("str", "res/xml/file_paths.xml"), 0x7F050001: ("str", "from strings"),
                                0x7F050002: ("ref", 0x7F050001), 0x7F060001: ("int", 7)})
        facts = self.apk("a.apk", table, components=[
            ("meta-data", [a("name", "str", "k.file"), a("resource", "ref", 0x7F0A0001)], []),
            ("meta-data", [a("name", "str", "k.alias"), a("value", "ref", 0x7F050002)], []),
            ("meta-data", [a("name", "str", "k.int"), a("value", "ref", 0x7F060001)], []),
            ("meta-data", [a("name", "str", "k.framework"), a("resource", "ref", 0x01040001)], []),
            ("meta-data", [a("name", "str", "k.literal"), a("value", "str", "v")], []),
        ])
        digest = gate.resource_content([axml(FILE_PATHS)])[0]
        self.assertEqual(facts["meta_data"], ["k.alias;value=from strings", "k.file;resource=sha256:" + digest,
                                              "k.framework;resource=android:0x01040001", "k.int;value=7", "k.literal;value=v"])

    def test_meta_data_reference_that_cannot_be_resolved_is_exit_2(self):
        with self.assertRaises(gate.GateError):  # not in the table
            self.apk("a.apk", resource_table({0x7F0A0001: ("str", "res/xml/file_paths.xml")}),
                     components=[("meta-data", [a("name", "str", "k"), a("value", "ref", 0x7F050009)], [])])
        write_apk(self.path("b.apk"), manifest(components=[("meta-data", [a("name", "str", "k"), a("value", "ref", 0x7F050001)], [])]))
        with self.assertRaises(gate.GateError):  # no resources.arsc at all
            gate.apk_facts(self.path("b.apk"))
        loop = resource_table({0x7F050001: ("ref", 0x7F050002), 0x7F050002: ("ref", 0x7F050001)})
        with self.assertRaises(gate.GateError):  # an alias loop is not followed forever
            self.apk("c.apk", loop, components=[("meta-data", [a("name", "str", "k"), a("value", "ref", 0x7F050001)], [])])

    def test_network_security_config_still_resolves_through_the_shared_walk(self):
        with open(REAL_TABLE, "rb") as f:
            table = f.read()
        self.assertEqual(len(gate.resource_files(table, 0x7F040000)), 1)
        self.assertEqual(gate.resolve_reference(table, 0x7F040000, set())[0], "value")  # not an APK path here
        self.assertEqual(gate.resolve_reference(table, 0x7F040000, set(gate.resource_files(table, 0x7F040000)))[0], "file")


class ForgeryTest(Case):
    """The canonical form must not let a different resource file produce the same lines or the same digest."""

    def paths(self, *attr_sets):
        return axml(("paths", [], [("cache-path", attrs, []) for attrs in attr_sets]))

    def honest(self):
        return self.paths([(False, "name", "str", "share"), (False, "path", "str", "share/")])

    def test_a_decoy_attribute_in_another_namespace_does_not_hide_the_plain_one(self):
        # FileProvider reads getAttributeValue(null, "path"): here "." (the whole cache directory). The decoy
        # urn:x:path carries Lumen's real value, and the old form keyed both by the bare name, last one winning.
        widened = self.paths([(False, "name", "str", "share"), (False, "path", "str", "."),
                              ("urn:x", "path", "str", "share/")])
        honest_lines, widened_lines = gate.canonical_xml(self.honest()), gate.canonical_xml(widened)
        self.assertEqual(widened_lines[1], jl(1, "cache-path", ("name", "share"), ("path", "."), ("{urn:x}path", "share/")))
        self.assertNotEqual(gate.resource_content([self.honest()])[0], gate.resource_content([widened])[0])
        self.assertNotEqual(honest_lines, widened_lines)

    def test_an_attribute_value_cannot_spell_out_another_attribute_or_element(self):
        spoof = self.paths([(False, "name", "str", "share path=share/")])
        self.assertEqual(gate.canonical_xml(spoof)[1], jl(1, "cache-path", ("name", "share path=share/")))
        self.assertNotEqual(gate.resource_content([self.honest()])[0], gate.resource_content([spoof])[0])
        newline = self.paths([(False, "name", "str", 'x"],[1,"cache-path",[["path","share/"]]]\n1 cache-path path=share/')])
        lines = gate.canonical_xml(newline)
        self.assertEqual(len(lines), 2)
        self.assertTrue(all("\n" not in line for line in lines))
        self.assertEqual(json.loads(lines[1])[2][0][1].count("\n"), 1)  # the newline is data, escaped in the line
        two_elements = self.paths([(False, "name", "str", "x")], [(False, "path", "str", "share/")])
        self.assertNotEqual(gate.resource_content([newline])[0], gate.resource_content([two_elements])[0])

    def test_text_nodes_and_references_are_unambiguous(self):
        xml = axml(("paths", [], [("a", [(False, "k", "str", "@ref"), (False, "r", "ref", 0x7F010001)], []), ("#text", "  hi  ")]))
        lines = gate.canonical_xml(xml)
        self.assertEqual(lines[1], jl(1, "a", ("k", "@ref"), ("r", None)))  # a string "@ref" is not a reference
        self.assertEqual(lines[2], jl(1, "#text", ("#text", "hi")))
        multi = gate.canonical_xml(axml(("paths", [], [("#text", "a\nb")])))
        self.assertEqual((len(multi), "\n" in multi[1]), (2, False))

    def test_two_attributes_that_read_as_one_key_are_refused(self):
        for attrs in ([(False, "path", "str", "a"), (False, "path", "str", "b")],
                      [(True, "path", "str", "a"), (None, "path", "str", "b")]):  # android:path twice, one without the namespace
            with self.assertRaises(gate.GateError, msg=attrs):
                gate.canonical_xml(self.paths(attrs))
        with self.assertRaises(gate.GateError):  # the manifest and network security config too
            gate.manifest_facts(manifest(components=[("service", [a("name", "str", ".S"), a("exported", "bool", True),
                                                                  a("exported", "bool", False)], [])]))
        nsc = axml(("network-security-config", [], [
            ("base-config", [(False, "cleartextTrafficPermitted", "bool", False),
                             (False, "cleartextTrafficPermitted", "bool", True)], [])]))
        with self.assertRaises(gate.GateError):
            gate.nsc_facts(nsc, lambda rid: [])

    def test_a_foreign_namespace_attribute_is_not_the_android_one(self):
        facts = gate.manifest_facts(manifest(components=[
            ("service", [a("name", "str", ".S"), ("urn:x", "exported", "bool", True)], [])]))
        self.assertFalse(facts["components"][0]["exported"])  # no intent filter, android:exported absent

    def test_a_forged_provider_resource_is_a_baseline_change_end_to_end(self):
        table = resource_table({0x7F0A0001: ("str", "res/xml/file_paths.xml")})
        entries = {"resources.arsc": table, "res/xml/file_paths.xml": self.honest()}
        write_apk(self.path("a.apk"), manifest(components=[PROVIDER]), entries=entries)
        self.generate(self.path("a.apk"))
        self.assertEqual(self.check(self.path("a.apk"))[0], 0)
        entries["res/xml/file_paths.xml"] = self.paths([(False, "name", "str", "share"), (False, "path", "str", "."),
                                                        ("urn:x", "path", "str", "share/")])
        write_apk(self.path("b.apk"), manifest(components=[PROVIDER]), entries=entries)
        code, output = self.check(self.path("b.apk"))
        self.assertEqual(code, 1, output)
        self.assertIn("GATE ~ provider_resources: ", output)
        entries["res/xml/file_paths.xml"] = self.paths([(False, "name", "str", "share"), (False, "path", "str", "a"),
                                                        (False, "path", "str", "share/")])
        write_apk(self.path("c.apk"), manifest(components=[PROVIDER]), entries=entries)
        self.assertEqual(self.check(self.path("c.apk"))[0], 2)  # refused, never a pass


class ReviewFixesTest(Case):
    def test_non_numeric_uses_feature_version_or_gl_version_raises(self):
        for attrs in ([a("name", "str", "f"), a("version", "str", "abc")], [a("glEsVersion", "str", "abc")]):
            with self.assertRaises(gate.GateError, msg=attrs):
                gate.manifest_facts(manifest(features=[attrs]))

    def test_file_in_one_configuration_and_value_in_another_is_exit_2(self):
        table = resource_table({0x7F0A0001: [("str", "res/xml/a.xml"), ("int", 5)]})
        with self.assertRaises(gate.GateError):
            gate.resolve_reference(table, 0x7F0A0001, {"res/xml/a.xml"})
        self.assertEqual(gate.resolve_reference(table, 0x7F0A0001, set()), ("value", ["5", "res/xml/a.xml"]))
        alias = resource_table({0x7F0A0001: [("ref", 0x7F0A0002), ("ref", 0x7F0A0003)],
                                0x7F0A0002: ("str", "res/xml/a.xml"), 0x7F0A0003: ("int", 5)})
        with self.assertRaises(gate.GateError):  # through an alias too
            gate.resolve_reference(alias, 0x7F0A0001, {"res/xml/a.xml"})

    def test_lists_that_differ_without_an_item_difference_still_differ(self):
        for old, new in ((["a", "b"], ["b", "a"]), (["a", "a"], ["a"])):
            diffs = gate.differences({"queries": old}, {"queries": new}, ("queries",))
            self.assertEqual(len(diffs), 1, (old, new))
            self.assertEqual(diffs[0][:2], ("~", "queries"))
        # Optional sections are sorted first, so their order alone is not a difference.
        self.assertEqual(gate.differences({"uses_features": ["b;required=true", "a;required=true"]},
                                          {"uses_features": ["a;required=true", "b;required=true"]}, ("uses_features",)), [])
        self.assertEqual(len(gate.differences({"uses_features": ["a", "a"]}, {"uses_features": ["a"]}, ("uses_features",))), 1)

    def test_string_attribute_without_a_raw_copy_or_with_a_different_one_is_refused(self):
        # FileProvider reads the raw string (none gives null: the whole root), PackageParser the typed value.
        paths = lambda attrs: axml(("paths", [], [("cache-path", attrs, [])]))
        for attrs in ([(False, "name", "str", "share"), (False, "path", "typed", "share/")],
                      [(False, "name", "str", "share"), (False, "path", "rawtyped", ("share/", "."))]):
            with self.assertRaises(gate.GateError, msg=attrs):
                gate.canonical_xml(paths(attrs))
        with self.assertRaises(gate.GateError):  # the manifest too: a name the platform reads differently
            gate.manifest_facts(manifest(components=[("meta-data", [a("name", "typed", "k"), a("value", "str", "v")], [])]))
        with self.assertRaises(gate.GateError):
            gate.manifest_facts(manifest(app_attrs=[a("name", "rawtyped", ("A", "B"))]))
        with self.assertRaises(gate.GateError):  # and the network security config
            gate.nsc_facts(axml(("network-security-config", [], [("domain-config", [], [
                ("domain", [(False, "includeSubdomains", "typed", "true")], [("#text", "x.example")])])])), lambda rid: [])
        # A reference with a raw string beside it: getAttributeValue returns the raw string (an empty path
        # would share the whole root), the gate would record null.
        with self.assertRaises(gate.GateError):
            gate.canonical_xml(paths([(False, "name", "str", "share"), (False, "path", "rawref", ("", 0x7F010001))]))
        with self.assertRaises(gate.GateError):
            gate.manifest_facts(manifest(app_attrs=[a("icon", "rawref", ("x", 0x7F010001))]))
        # An honest raw/typed pair, a plain reference, and non-string types with a raw copy still decode.
        self.assertEqual(gate.canonical_xml(paths([(False, "r", "ref", 0x7F010001)]))[1], jl(1, "cache-path", ("r", None)))
        self.assertEqual(gate.canonical_xml(paths([(False, "path", "str", "share/")]))[1],
                         jl(1, "cache-path", ("path", "share/")))

    def test_a_single_difference_does_not_print_equal_sibling_lists_as_changed(self):
        old = {"permissions": {"requested": ["a"], "declared": ["x"]},
               "dependencies": {"modules": ["m"], "files": [], "desugaring": []}}
        new = {"permissions": {"requested": ["a", "b"], "declared": ["x"]},
               "dependencies": {"modules": ["m", "n"], "files": [], "desugaring": []}}
        self.assertEqual(gate.differences(old, new, ("permissions", "dependencies")),
                         [("+", "permissions.requested", "b"), ("+", "dependencies.modules", "n")])
        # Same lists with only the order changed inside one sub-field still show, once.
        reordered = {"permissions": {"requested": ["b", "a"], "declared": ["x"]}}
        diffs = gate.differences({"permissions": new["permissions"]}, reordered, ("permissions",))
        self.assertEqual([d[:2] for d in diffs], [("~", "permissions.requested")])

    def test_asset_globs_ignore_case(self):
        write_apk(self.path("a.apk"), entries={"assets/Models/Face.TFLite": b"1", "assets/OCR/Rec.bin": b"2", "assets/x.bin": b"3"})
        self.assertEqual([e["path"] for e in gate.apk_facts(self.path("a.apk"))["assets"]], ["assets/Models/Face.TFLite"])
        found = gate.apk_facts(self.path("a.apk"), asset_digest_paths=("ASSETS/ocr/*",))["assets"]
        self.assertEqual([e["path"] for e in found], ["assets/OCR/Rec.bin"])

    def test_null_asset_digest_paths_is_an_error_not_the_defaults(self):
        write_apk(self.path("a.apk"))
        data = read_json(self.baseline)
        data["scan"] = {"extra_text_paths": [], "asset_digest_paths": None}
        write_json(self.baseline, data)
        self.assertEqual(self.check(self.path("a.apk"))[0], 2)
        self.assertEqual(self.run_gate("generate", "--apk", self.path("a.apk"), "--deps", self.deps, "--baseline", self.baseline)[0], 2)


class CompatibilityTest(Case):
    """Baselines written before gate 2.1 have none of the new sections and none of the new scan keys."""

    def test_old_baseline_passes_unchanged_and_regenerates_byte_for_byte(self):
        write_apk(self.path("a.apk"), strings=["https://api.example.com"])
        data = read_json(self.baseline)
        data["hosts"] = [{"value": "https://api.example.com", "note": "n"}]
        write_json(self.baseline, data)
        self.generate(self.path("a.apk"))
        old = read_json(self.baseline)
        self.assertEqual(optional_sections(old), {})  # empty sections are left out
        self.assertEqual(set(old["scan"]), {"extra_text_paths"})  # and the new config key is not added
        with open(self.baseline) as f:
            before = f.read()
        code, output = self.check(self.path("a.apk"))
        self.assertEqual(code, 0, output)
        self.generate(self.path("a.apk"))
        with open(self.baseline) as f:
            self.assertEqual(f.read(), before)

    def test_old_baseline_reports_new_sections_as_additions_only_when_the_apk_has_them(self):
        write_apk(self.path("a.apk"))
        self.generate(self.path("a.apk"))
        write_apk(self.path("b.apk"), manifest(features=[[a("name", "str", "android.hardware.camera"), a("required", "bool", False)]],
                                               components=[("meta-data", [a("name", "str", "k"), a("value", "str", "v")], [])]),
                  entries={"assets/m.onnx": b"m"})
        code, output = self.check(self.path("b.apk"))
        self.assertEqual(code, 1)
        for line in ("GATE + uses_features: android.hardware.camera;required=false", "GATE + meta_data: k;value=v",
                     "GATE + assets: assets/m.onnx"):
            self.assertIn(line, output)
        self.assertEqual(sum(1 for l in output.splitlines() if l.startswith("GATE ")), 3)  # nothing else differs
        self.generate(self.path("b.apk"))
        self.assertEqual(self.check(self.path("b.apk"))[0], 0)
        self.assertEqual(sorted(optional_sections(read_json(self.baseline))), ["assets", "meta_data", "uses_features"])
        # And the other way: a section that is in the baseline but gone from the APK is a removal.
        code, output = self.check(self.path("a.apk"))
        self.assertIn("GATE - uses_features: android.hardware.camera;required=false", output)

    def test_every_baseline_in_this_repo_loads_and_compares_with_itself(self):
        paths = [os.path.join(HERE, n) for n in sorted(os.listdir(HERE)) if n.startswith("baseline") and n.endswith(".json")]
        self.assertTrue(paths)
        for path in paths:
            baseline = gate.load_baseline(path)
            gate.check_rules(baseline.get("notes", []))
            self.assertEqual(gate.differences(baseline, dict(baseline)), [], path)
            self.assertEqual(gate.differences(baseline, dict(baseline), gate.COMPARED_BASELINES, against_apk=False), [], path)
            for key in gate.OPTIONAL_LISTS:
                self.assertIsInstance(gate.comparable(baseline, key, True), list)

    def test_baseline_diff_treats_a_missing_section_as_empty(self):
        old = {"schema": 2, "variant": "release"}
        new = dict(old, uses_features=["x;required=true"], assets=[{"path": "a", "sha256": "0" * 64, "size": 1}])
        self.assertEqual(gate.differences(old, new, gate.COMPARED_BASELINES, against_apk=False),
                         [("+", "uses_features", "x;required=true"), ("+", "assets", "a")])
        self.assertEqual(gate.differences(new, dict(new, uses_features=[]), gate.COMPARED_BASELINES, against_apk=False),
                         [("-", "uses_features", "x;required=true")])

    def test_schema_stays_2_and_the_version_is_reported(self):
        self.assertEqual(gate.SCHEMA, 2)
        out = io.StringIO()
        with self.assertRaises(SystemExit) as stop, redirect_stdout(out):
            gate.main(["--version"])
        self.assertEqual(stop.exception.code, 0)
        self.assertIn("2.1", out.getvalue())
        write_apk(self.path("a.apk"))
        self.generate(self.path("a.apk"))
        self.assertIn("gate: gate.py 2.1, baseline schema 2", self.check(self.path("a.apk"))[1])


if __name__ == "__main__":
    unittest.main()
