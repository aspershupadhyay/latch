# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
"""Tests for latch_client. The live tests need a gateway with at least two
connected phones (scripts/interop.sh starts one with two fake phones):

    LATCH_URL=http://127.0.0.1:8799 LATCH_MCP_TOKEN=... python3 -m unittest -v test_latch_client
"""

import os
import threading
import unittest

from latch_client import Latch, LatchError, Result

URL = os.environ.get("LATCH_URL")
TOKEN = os.environ.get("LATCH_MCP_TOKEN")


class ResultTest(unittest.TestCase):
    TEXT = (
        "observation_id: o_1 · device d_1 · app com.android.settings\n"
        '[n1] Button "Send" @(0,0,10,10) [tap]\n'
        "observation_id: o_2 · device d_1 · app com.android.settings\n"
        '[n4] ImageButton desc="Voice search" @(0,0,10,10) [tap]\n'
        '[n7] Button "Send" @(0,20,10,30) [tap]\n'
    )

    def test_newest_observation_and_element(self):
        r = Result(self.TEXT, False)
        self.assertEqual(r.observation_id, "o_2")
        self.assertEqual(r.element("Send"), "n7")
        self.assertEqual(r.element("Voice search"), "n4")
        with self.assertRaises(KeyError):
            r.element("Pay")
        self.assertIsNone(Result("Done.", False).observation_id)


@unittest.skipUnless(URL and TOKEN, "set LATCH_URL and LATCH_MCP_TOKEN to a gateway with two connected phones")
class LiveTest(unittest.TestCase):
    def setUp(self):
        self.latch = Latch(URL, TOKEN)
        self.phones = self.latch.device_ids()
        self.assertGreaterEqual(len(self.phones), 2, "needs two connected phones")

    def test_the_tool_list_has_no_way_around_the_rules(self):
        names = self.latch.tools()
        self.assertIn("observe", names)
        self.assertIn("run_steps", names)
        for forbidden in ("shell", "exec", "install"):
            self.assertFalse(any(forbidden in n for n in names), names)

    def test_two_phones_in_parallel(self):
        """Each phone gets its own client and thread; each turns its own Wi-Fi on."""
        results, errors = {}, []

        def run(device_id):
            try:
                phone = Latch(URL, TOKEN, device_id=device_id)
                s = phone.call("launch_app", package="com.android.settings")
                s = phone.call("tap", observation_id=s.observation_id, element_id=s.element("Network & internet"))
                if '"Wi-Fi"' in s.text and "[tap,unchecked]" in next(l for l in s.text.splitlines() if '"Wi-Fi"' in l):
                    s = phone.call("tap", observation_id=s.observation_id, element_id=s.element("Wi-Fi"))
                results[device_id] = s
            except Exception as e:  # reported below with the phone's id
                errors.append(f"{device_id}: {e!r}")

        threads = [threading.Thread(target=run, args=(d,)) for d in self.phones[:2]]
        for t in threads:
            t.start()
        for t in threads:
            t.join(60)
        self.assertEqual(errors, [])
        for device_id in self.phones[:2]:
            s = results[device_id]
            self.assertFalse(s.is_error, s.text)
            self.assertIn(f"device {device_id}", s.text)
            self.assertRegex(s.text, r'"Wi-Fi".*\[tap,checked\]')

    def test_the_phone_still_refuses_password_fields(self):
        phone = Latch(URL, TOKEN, device_id=self.phones[0])
        s = phone.call("launch_app", package="org.latch.demo.login")
        field = next(l for l in s.text.splitlines() if "<sensitive" in l)
        typed = phone.call("type_text", observation_id=s.observation_id,
                           element_id=field[field.index("[") + 1:field.index("]")], text="hunter2")
        self.assertTrue(typed.is_error)
        self.assertIn("sensitive", typed.text)

    def test_a_stale_observation_is_refused(self):
        r = self.latch.call("tap", device_id=self.phones[0], observation_id="o_does_not_exist", element_id="n2")
        self.assertTrue(r.is_error)
        self.assertIn("stale_observation", r.text)

    def test_a_wrong_token_is_refused(self):
        with self.assertRaises(LatchError):
            Latch(URL, "not-the-token")


if __name__ == "__main__":
    unittest.main()
