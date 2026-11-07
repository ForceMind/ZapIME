# SPDX-License-Identifier: GPL-3.0-only
"""Host-only guard/command tests; never run adb, remount or install a CA."""
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import subprocess

from android_sync_https_smoke import (Adb, CA_DIRECTORY, Rejected, TemporaryCA, allow_fixture,
                                     become_root, ci_identity, endpoint_port, guard_device,
                                     parse_baseline, restart_app, rule_plan, validate_database,
                                     verify_fixture_summary, safe_fixture_summary, verify_product_manifest)


ENVIRONMENT = {"GITHUB_ACTIONS": "true", "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2"}
COMMAND = ["adb", "-s", "emulator-5554"]


class FakeAdb:
    command = COMMAND

    def __init__(self, changes=None, fail_family=None):
        self.values = {"ro.kernel.qemu": "1", "ro.build.version.sdk": "28", "ro.build.type": "userdebug",
                       "ro.debuggable": "1", "ro.product.cpu.abi": "x86_64"}
        self.values.update(changes or {})
        self.calls, self.rules, self.chains = [], set(), set()
        self.fail_family = fail_family
        self.root_uid, self.selinux, self.avd = "0", "Enforcing", "zapime-sync-https-123-2"
        self.output_override = None

    def run(self, *args, check=True, timeout=30):
        self.calls.append(args)
        code, output = 0, ""
        if args == ("emu", "avd", "name"):
            output = self.avd + "\nOK\n"
        if args[:2] in (("shell", "iptables"), ("shell", "ip6tables")):
            family, operation, rest = args[1], args[4], tuple(args[5:])
            key = (family, rest)
            if operation == "-N":
                if family == self.fail_family:
                    code = 1
                elif key in self.chains:
                    code = 1
                else:
                    self.chains.add(key)
            elif operation == "-S":
                if rest == ("OUTPUT",):
                    output = self.output_override or "-P OUTPUT ACCEPT\n" + "\n".join("-A " + " ".join(rule) for f, rule in self.rules if f == family and rule[0] == "OUTPUT") + "\n"
                else:
                    code = 0 if key in self.chains else 1
                if code == 0 and rest != ("OUTPUT",):
                    output = "-N " + rest[0] + "\n" + "\n".join("-A " + " ".join(rule) for f, rule in self.rules if f == family and rule[0] == rest[0]) + "\n"
            elif operation in ("-I", "-A"):
                self.rules.add(key)
            elif operation == "-C":
                code = 0 if key in self.rules else 1
            elif operation == "-D":
                if key in self.rules:
                    self.rules.remove(key)
                else:
                    code = 1
            elif operation == "-X":
                if key in self.chains and not any(f == family and r[0] == rest[0] for f, r in self.rules):
                    self.chains.remove(key)
                else:
                    code = 1
        if check and code:
            raise Rejected("adb_command_failed")
        return SimpleNamespace(returncode=code, stdout=output, stderr="")

    def shell(self, *args, **options):
        self.calls.append(("shell", *args))
        if args[0] == "getprop":
            return self.values[args[1]]
        if args == ("getenforce",):
            return self.selinux
        if args == ("id", "-u"):
            return self.root_uid
        if args[:4] == ("pm", "list", "packages", "-U"):
            return "package:org.zapime.android uid:10123\n"
        raise AssertionError("Unexpected read-only fake shell command")


class HTTPSGuards(unittest.TestCase):
    def test_numeric_ci_and_only_emulator_serial(self):
        self.assertEqual(ci_identity(COMMAND, ENVIRONMENT), "123-2")
        for environment, command in [({}, COMMAND), ({**ENVIRONMENT, "GITHUB_ACTIONS": "false"}, COMMAND),
                                     ({**ENVIRONMENT, "GITHUB_RUN_ID": "１２３"}, COMMAND),
                                     ({**ENVIRONMENT, "GITHUB_RUN_ATTEMPT": "1;touch"}, COMMAND),
                                     (ENVIRONMENT, ["adb", "-s", "phone"]), (ENVIRONMENT, ["adb"]),
                                     (ENVIRONMENT, ["adb", "-s", "emulator-5554;id"])]:
            with self.subTest(environment=environment, command=command), self.assertRaises(Rejected):
                ci_identity(command, environment)

    def test_unsafe_environment_never_runs_adb(self):
        for environment in ({}, {**ENVIRONMENT, "GITHUB_RUN_ID": "bad"}):
            adb = FakeAdb()
            with patch.dict("os.environ", environment, clear=True), self.assertRaises(Rejected):
                become_root(adb, "123-2")
            self.assertEqual(adb.calls, [])

    def test_read_only_checks_precede_root(self):
        for property_name, bad in [("ro.kernel.qemu", "0"), ("ro.build.version.sdk", "35"),
                                   ("ro.build.type", "user"), ("ro.debuggable", "0"),
                                   ("ro.product.cpu.abi", "arm64-v8a")]:
            adb = FakeAdb({property_name: bad})
            with self.subTest(property_name=property_name), patch.dict("os.environ", ENVIRONMENT, clear=True), self.assertRaises(Rejected):
                become_root(adb, "123-2")
            self.assertNotIn(("root",), adb.calls)

    def test_selinux_and_avd_ownership_precede_root(self):
        for field, invalid in [("selinux", "Permissive"), ("avd", "my-personal-emulator")]:
            adb = FakeAdb()
            setattr(adb, field, invalid)
            with patch.dict("os.environ", ENVIRONMENT, clear=True), self.assertRaises(Rejected):
                become_root(adb, "123-2")
            self.assertNotIn(("root",), adb.calls)

    def test_real_root_is_required_after_transition(self):
        adb = FakeAdb()
        adb.root_uid = "2000"
        with patch.dict("os.environ", ENVIRONMENT, clear=True), self.assertRaises(Rejected):
            become_root(adb, "123-2")
        self.assertIn(("root",), adb.calls)
        self.assertFalse(any("remount" in call for call in adb.calls))

    def test_allowed_guard_and_restart_only_owned_packages(self):
        adb = FakeAdb()
        with patch.dict("os.environ", ENVIRONMENT, clear=True):
            become_root(adb, "123-2")
            guard_device(adb, "123-2", root=True)
            restart_app(adb, "123-2")
        mutations = [call for call in adb.calls if call[:3] == ("shell", "am", "force-stop")]
        self.assertEqual(mutations, [("shell", "am", "force-stop", "org.zapime.android"),
                                     ("shell", "am", "force-stop", "org.zapime.android.voicetest")])
        self.assertFalse(any("reboot" in call or "disable-verity" in call or "setenforce" in call for call in adb.calls))

    def test_origin_exact_host_alias_and_no_caller_route(self):
        self.assertEqual(endpoint_port("https://10.0.2.2:12345"), 12345)
        for origin in ["https://127.0.0.1:12345", "http://10.0.2.2:12345", "https://10.0.2.2:443",
                       "https://10.0.2.2:12345/", "https://10.0.2.2:12345/x", "https://user@10.0.2.2:12345",
                       "https://10.0.2.2:12345?a=b", "https://10.0.2.2:12345#x", "https://10.0.2.2:65536",
                       "https://10.0.2.2:012345", "https://10.0.2.2", "https://10.0.2.2:bad"]:
            with self.subTest(origin=origin), self.assertRaises(Rejected):
                endpoint_port(origin)

    def test_baseline_exact_public_members_and_duplicates(self):
        valid = "\n".join("a" * 64 + "  " + CA_DIRECTORY + "/%08x.0" % n for n in range(20))
        self.assertEqual(len(parse_baseline(valid)), 20)
        for invalid in ["", valid + "\n" + valid.splitlines()[0], valid.replace("00000000.0", "../secret"),
                        valid.replace("a" * 64, "A" * 64), valid.replace("  " + CA_DIRECTORY, "  /elsewhere")]:
            with self.assertRaises(Rejected):
                parse_baseline(invalid)

    def test_ca_collision_refuses_before_any_adb(self):
        adb = FakeAdb()
        fixture = SimpleNamespace(ca_subject_hash_old="aabbccdd")
        with self.assertRaisesRegex(Rejected, "collision"):
            TemporaryCA(adb, "123-2", fixture, {"aabbccdd.0": "a" * 64}, {})
        self.assertEqual(adb.calls, [])

    def test_database_restricted_to_fresh_loopback_ci_database(self):
        validate_database("postgres://postgres:ci-only@127.0.0.1:5432/zapime_https_test?sslmode=disable")
        for invalid in ["postgres://postgres:secret@production:5432/zapime_https_test?sslmode=disable",
                        "postgres://postgres:secret@127.0.0.1:5432/existing?sslmode=disable",
                        "postgres://postgres:secret@127.0.0.1:5432/zapime_https_test?sslmode=disable&x=y",
                        "postgres://postgres@127.0.0.1:5432/zapime_https_test?sslmode=disable",
                        "postgres://postgres:secret@127.0.0.1:5433/zapime_https_test?sslmode=disable"]:
            with self.assertRaises(Rejected):
                validate_database(invalid)

    def test_subprocess_failures_do_not_expose_arguments_or_output(self):
        sensitive = "synthetic-secret-bootstrap-route"
        adb = Adb(COMMAND)
        with patch("subprocess.run", return_value=SimpleNamespace(returncode=1, stdout=sensitive, stderr=sensitive)):
            with self.assertRaises(Rejected) as caught:
                adb.run("shell", sensitive)
            self.assertEqual(str(caught.exception), "adb_command_failed")
        with patch("subprocess.run", side_effect=subprocess.TimeoutExpired([sensitive], 30)):
            with self.assertRaises(Rejected) as caught:
                adb.run("shell", sensitive)
            self.assertEqual(str(caught.exception), "adb_timeout")


class CACleanup(unittest.TestCase):
    def make(self):
        adb = FakeAdb()
        fixture = SimpleNamespace(ca_subject_hash_old="aabbccdd", ca_sha256_pem="a" * 64, ca_sha256_der="b" * 64)
        proof = {}
        ca = TemporaryCA(adb, "123-2", fixture, {"00000000.0": "c" * 64}, proof)
        ca.created = ca.staged = True
        files = {ca.destination: "a" * 64, ca.staging: "a" * 64}
        calls = []

        def run(*args, **options):
            calls.append(args)
            if args[:2] == ("shell", "rm"):
                del files[args[2]]
            elif args[:2] == ("shell", "test"):
                if args[-1] in files:
                    raise Rejected("adb_command_failed")
            else:
                self.fail("Unexpected mutating fake CA command")
            return SimpleNamespace(returncode=0, stdout="", stderr="")

        def shell(*args, **options):
            self.assertEqual(args[0], "sha256sum")
            return files[args[1]] + "  " + args[1]

        adb.run, adb.shell = run, shell
        return ca, proof, files, calls

    def test_removes_only_same_fingerprint_ca_and_owned_staging(self):
        ca, proof, files, calls = self.make()
        with patch("android_sync_https_smoke.guard_device"), patch("android_sync_https_smoke.baseline", return_value=ca.initial):
            ca.remove()
        self.assertEqual(files, {})
        self.assertTrue(proof["removed"] and proof["staging_removed"] and proof["baseline_restored"])
        self.assertEqual([args[2] for args in calls if args[:2] == ("shell", "rm")], [ca.destination, ca.staging])

    def test_changed_ca_is_retained_but_independent_staging_cleanup_runs(self):
        ca, proof, files, calls = self.make()
        files[ca.destination] = "d" * 64
        with patch("android_sync_https_smoke.guard_device"), patch("android_sync_https_smoke.baseline", return_value={**ca.initial, "aabbccdd.0": "d" * 64}), self.assertRaises(Rejected):
            ca.remove()
        self.assertIn(ca.destination, files)
        self.assertNotIn(ca.staging, files)
        self.assertFalse(proof["removed"] or proof["baseline_restored"])
        self.assertTrue(proof["staging_removed"])

    def test_changed_staging_is_not_deleted_or_claimed_clean(self):
        ca, proof, files, calls = self.make()
        files[ca.staging] = "d" * 64
        with patch("android_sync_https_smoke.guard_device"), patch("android_sync_https_smoke.baseline", return_value=ca.initial), self.assertRaises(Rejected):
            ca.remove()
        self.assertNotIn(ca.destination, files)
        self.assertIn(ca.staging, files)
        self.assertTrue(proof["removed"])
        self.assertFalse(proof["staging_removed"])

    def test_wrong_host_ca_fingerprint_refuses_before_remount(self):
        with tempfile.TemporaryDirectory() as temporary:
            file = Path(temporary) / "public-test-ca.pem"
            file.write_bytes(b"public synthetic certificate")
            ca, proof, files, calls = self.make()
            ca.fixture.ca_file = file
            with patch("android_sync_https_smoke.guard_device"), self.assertRaises(Rejected):
                ca.install()
            self.assertEqual(calls, [])


class HTTPSRules(unittest.TestCase):
    def test_plan_limits_two_ports_and_exact_product_owner(self):
        plan = rule_plan(10123, [12345, 54321], "ZAPHTTPSabcdef123456")
        self.assertEqual(len(plan), 2)
        self.assertEqual(plan[0][1], ["OUTPUT", "-m", "owner", "--uid-owner", "10123", "-j", "ZAPHTTPSabcdef123456"])
        self.assertEqual(plan[1][2], [["ZAPHTTPSabcdef123456", "-j", "REJECT"]])
        self.assertEqual([rule[-1] for rule in plan[0][2]], ["ACCEPT", "ACCEPT", "REJECT"])
        for rule in plan[0][2][:-1]:
            self.assertIn("10.0.2.2/32", rule)
            self.assertIn("tcp", rule)

    def test_bad_rule_arguments_rejected_without_commands(self):
        for uid, ports, chain in [(0, [12345, 54321], "ZAPHTTPSabcdef123456"),
                                  (True, [12345, 54321], "ZAPHTTPSabcdef123456"),
                                  (10123, [12345, 12345], "ZAPHTTPSabcdef123456"),
                                  (10123, [80, 54321], "ZAPHTTPSabcdef123456"),
                                  (10123, [12345, 54321], "OUTPUT"),
                                  (10123, [12345, 54321], "ZAPHTTPSabcdef123456;id")]:
            with self.assertRaises(Rejected):
                rule_plan(uid, ports, chain)

    def test_both_families_removed_on_body_failure_without_flush(self):
        adb, proof = FakeAdb(), {}
        with patch.dict("os.environ", ENVIRONMENT, clear=True), self.assertRaisesRegex(RuntimeError, "synthetic_failure"):
            with allow_fixture(adb, "123-2", [12345, 54321], proof) as verify:
                self.assertTrue(proof["ipv4_fixture_only"] and proof["ipv6_reject"])
                verify()
                raise RuntimeError("synthetic_failure")
        self.assertFalse(adb.rules or adb.chains)
        self.assertTrue(proof["removed"])
        self.assertEqual(proof["checks"], 2)
        self.assertFalse(any("-F" in call or "flush" in call for call in adb.calls))

    def test_partial_ipv6_failure_still_removes_all_owned_ipv4(self):
        adb, proof = FakeAdb(fail_family="ip6tables"), {}
        with patch.dict("os.environ", ENVIRONMENT, clear=True), self.assertRaises(Rejected):
            with allow_fixture(adb, "123-2", [12345, 54321], proof):
                self.fail("Partial firewall admitted")
        self.assertFalse(adb.rules or adb.chains)
        self.assertTrue(proof["removed"] and proof["ipv4_fixture_only"])
        self.assertFalse(proof["ipv6_reject"])

    def test_mutated_owned_chain_fails_recheck_and_never_flushes_foreign_rule(self):
        adb, proof = FakeAdb(), {}
        with patch.dict("os.environ", ENVIRONMENT, clear=True), self.assertRaises(Rejected):
            with allow_fixture(adb, "123-2", [12345, 54321], proof) as verify:
                family, own = next(iter(adb.chains))
                adb.rules.add((family, (own[0], "-j", "ACCEPT")))
                verify()
        self.assertFalse(proof["removed"])
        self.assertEqual(len(adb.rules), 1)
        self.assertFalse(any("-F" in call for call in adb.calls))

    def test_accept_inserted_before_owned_output_jump_rejects_recheck(self):
        adb, proof = FakeAdb(), {}
        with patch.dict("os.environ", ENVIRONMENT, clear=True), self.assertRaisesRegex(Rejected, "not_first"):
            with allow_fixture(adb, "123-2", [12345, 54321], proof) as verify:
                adb.output_override = "-P OUTPUT ACCEPT\n-A OUTPUT -j ACCEPT\n"
                verify()
        self.assertFalse(adb.rules or adb.chains)
        self.assertTrue(proof["removed"])


class CompiledAPKAndServerEvidence(unittest.TestCase):
    @staticmethod
    def manifest():
        lines = ["E: manifest (line=1)", "  E: application (line=2)",
                 "    A: android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0"]
        for name in ("SyncPreparationActivity", "SyncKeyRegistrationActivity", "SyncTransferActivity"):
            lines += ["    E: activity (line=3)",
                      '      A: android:name(0x01010003)=".' + name + '" (Raw: ".' + name + '")',
                      "      A: android:exported(0x01010010)=(type 0x12)0x0"]
        return "\n".join(lines)

    def test_compiled_manifest_has_default_trust_and_private_pages(self):
        verify_product_manifest(self.manifest())
        verify_product_manifest(self.manifest().replace("android:", "http://schemas.android.com/apk/res/android:").replace("(type 0x12)0x0", "false"))
        verify_product_manifest(self.manifest().replace("    A: android:usesCleartextTraffic", "    A: android:debuggable(0x0101000f)=(type 0x12)0x0\n    A: android:usesCleartextTraffic"))

    def test_manifest_rejects_custom_trust_debug_cleartext_and_export(self):
        for raw in [self.manifest().replace("    A: android:usesCleartextTraffic", '    A: android:networkSecurityConfig(0x01010527)=@0x7f010000\n    A: android:usesCleartextTraffic'),
                    self.manifest().replace("    A: android:usesCleartextTraffic", "    A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff\n    A: android:usesCleartextTraffic"),
                    self.manifest().replace("android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0", "android:usesCleartextTraffic(0x010104ec)=(type 0x12)0xffffffff"),
                    self.manifest().replace("android:exported(0x01010010)=(type 0x12)0x0", "android:exported(0x01010010)=(type 0x12)0xffffffff"),
                    self.manifest().replace("SyncTransferActivity", "OtherActivity")]:
            with self.assertRaises(Rejected):
                verify_product_manifest(raw)

    @staticmethod
    def summary():
        return {"schema_version": 1, "scope": "Real isolated Go/PostgreSQL via temporary host TLS; no Android claim",
                "requests": [{"method": method, "path": path, "count": count} for method, path, count in [
                    ("GET", "/ci/bootstrap/<redacted>", 1), ("POST", "/v1/changes", 2),
                    ("PUT", "/v1/key-descriptor", 1), ("GET", "/v1/session", 2),
                    ("GET", "/v1/changes", 3), ("GET", "/v1/key-descriptor", 3)]],
                "bad_hostname_http_requests": 0, "service_revision": 2,
                "owner_session_valid": True, "member_session_valid": True, "bootstrap_response_bytes": 512,
                "request_limit_bytes": 100000, "response_limit_bytes": 10485760,
                "cleanup_completed": True, "go_process_stopped": True, "tls_threads_stopped": True,
                "private_temp_removed": True}

    def test_actual_server_summary_is_json_serializable_and_independently_gated(self):
        import json
        fixture = SimpleNamespace(safe_summary=lambda: self.summary())
        report = safe_fixture_summary(fixture.safe_summary())
        self.assertEqual(json.loads(json.dumps(report)), report)
        verify_fixture_summary(report)
        for field, invalid in [("service_revision", 1), ("bad_hostname_http_requests", 1),
                               ("owner_session_valid", False), ("member_session_valid", False),
                               ("private_temp_removed", False), ("observation_failed", True)]:
            report = self.summary()
            report[field] = invalid
            with self.assertRaises(Rejected):
                verify_fixture_summary(report)

    def test_summary_refuses_unredacted_paths_unknown_fields_or_forged_counts(self):
        for change in ("path", "unknown", "count", "duplicate"):
            report = self.summary()
            if change == "path":
                report["requests"][0]["path"] = "/ci/bootstrap/synthetic-secret"
            elif change == "unknown":
                report["token"] = "synthetic-secret"
            elif change == "count":
                report["requests"][1]["count"] = 1
            else:
                report["requests"].append(report["requests"][0])
            with self.assertRaises(Rejected):
                verify_fixture_summary(report)


if __name__ == "__main__":
    unittest.main()
