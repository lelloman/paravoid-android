import unittest
from stress_assertions import verify_cancelled_reopen


class CancellationRetentionTest(unittest.TestCase):
    def setUp(self):
        self.before = {"root": {"state": "CANCELLED", "attempt": 1},
                       "tail": {"state": "CANCELLED", "attempt": 0}}

    def test_retained_cancelled_dependent(self):
        self.assertFalse(verify_cancelled_reopen(self.before, self.before, "root", "tail"))

    def test_pruned_dependent_requires_durable_parent(self):
        self.assertTrue(verify_cancelled_reopen(self.before, {"root": self.before["root"]}, "root", "tail"))

    def test_missing_parent_or_unexpected_work_rejected(self):
        for after in ({}, {"tail": self.before["tail"]}, dict(self.before, extra={"state": "CANCELLED"})):
            with self.subTest(after=after), self.assertRaises(AssertionError):
                verify_cancelled_reopen(self.before, after, "root", "tail")

    def test_changed_parent_or_dependent_rejected(self):
        for key in ("root", "tail"):
            after = dict(self.before)
            after[key] = dict(after[key], state="SUCCEEDED")
            with self.subTest(key=key), self.assertRaises(AssertionError):
                verify_cancelled_reopen(self.before, after, "root", "tail")

    def test_pruning_cannot_hide_missing_predeath_cancellation(self):
        before = dict(self.before, tail={"state": "BLOCKED", "attempt": 0})
        with self.assertRaises(AssertionError):
            verify_cancelled_reopen(before, {"root": before["root"]}, "root", "tail")


if __name__ == "__main__":
    unittest.main()
