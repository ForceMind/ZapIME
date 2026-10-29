# SPDX-License-Identifier: GPL-3.0-only
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from android_offline_uid import offline_product


class OfflineUIDGuards(unittest.TestCase):
    env = {"GITHUB_ACTIONS": "true", "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1"}
    command = ["adb", "-s", "emulator-5554"]

    def test_non_ci_or_physical_device_never_runs_adb(self):
        for env, command in [({}, self.command), (self.env, ["adb", "-s", "phone"]), ({**self.env, "GITHUB_RUN_ID": "bad"}, self.command)]:
            with self.subTest(command=command), patch.dict("os.environ", env, clear=True), patch("subprocess.run") as run:
                with self.assertRaises(SystemExit), tempfile.TemporaryDirectory() as folder:
                    with offline_product(command, Path(folder)):
                        self.fail("unsafe device admitted")
                run.assert_not_called()

    def simulate(self, root="0", package="package:org.zapime.android uid:10123\n", existing=False, fail_ipv6=False):
        rules = set()
        if existing:
            rules.add("iptables")
        calls = []
        def run(command, **options):
            calls.append(command)
            stdout = root+"\n" if command[-2:] == ["id", "-u"] else package if "packages" in command else ""
            status = 0
            if "iptables" in command or "ip6tables" in command:
                binary = "ip6tables" if "ip6tables" in command else "iptables"
                if "-C" in command:
                    status = 0 if binary in rules else 1
                elif "-I" in command:
                    status = 1 if fail_ipv6 and binary == "ip6tables" else 0
                    if status == 0:
                        rules.add(binary)
                elif "-D" in command:
                    self.assertIn(binary, rules)
                    rules.remove(binary)
                else:
                    self.fail("unexpected firewall operation")
                self.assertNotIn("-F", command)
                self.assertIn("10123", command)
            if status and options.get("check"):
                raise subprocess.CalledProcessError(status, command)
            return subprocess.CompletedProcess(command, status, stdout, "")
        return run, rules, calls

    def test_root_or_exact_package_failure_precedes_firewall_mutation(self):
        for root, package in [("2000", ""), ("0", "package:org.zapime.android.test uid:10123\n")]:
            run, _, calls = self.simulate(root=root, package=package)
            with patch.dict("os.environ", self.env, clear=True), patch("subprocess.run", side_effect=run), tempfile.TemporaryDirectory() as folder:
                with self.assertRaises(SystemExit):
                    with offline_product(self.command, Path(folder)):
                        self.fail("invalid UID admitted")
            self.assertFalse(any("-I" in c or "-D" in c for c in calls))

    def test_preexisting_rule_is_not_taken_over_or_deleted(self):
        run, rules, calls = self.simulate(existing=True)
        with patch.dict("os.environ", self.env, clear=True), patch("subprocess.run", side_effect=run), tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(SystemExit):
                with offline_product(self.command, Path(folder)):
                    self.fail("existing rule admitted")
        self.assertEqual(rules, {"iptables"})
        self.assertFalse(any("-I" in c or "-D" in c for c in calls))

    def test_body_failure_removes_only_both_owned_rules(self):
        run, rules, _ = self.simulate()
        with patch.dict("os.environ", self.env, clear=True), patch("subprocess.run", side_effect=run), tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(RuntimeError, "synthetic"):
                with offline_product(self.command, Path(folder)):
                    self.assertEqual(rules, {"iptables", "ip6tables"})
                    raise RuntimeError("synthetic")
            proof = json.loads((Path(folder)/"offline-uid.json").read_text())
            self.assertTrue(proof["ipv4_reject"] and proof["ipv6_reject"] and proof["removed"])
        self.assertFalse(rules)

    def test_partial_ipv6_failure_cleans_ipv4_without_claiming_isolation(self):
        run, rules, _ = self.simulate(fail_ipv6=True)
        with patch.dict("os.environ", self.env, clear=True), patch("subprocess.run", side_effect=run), tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(subprocess.CalledProcessError):
                with offline_product(self.command, Path(folder)):
                    self.fail("partial isolation admitted")
            proof = json.loads((Path(folder)/"offline-uid.json").read_text())
            self.assertTrue(proof["ipv4_reject"] and proof["removed"])
            self.assertFalse(proof["ipv6_reject"])
        self.assertFalse(rules)


if __name__ == "__main__":
    unittest.main()
