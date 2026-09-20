"""Assertions for WorkManager cancellation retention, independent of adb."""


def verify_cancelled_reopen(before, after, root, tail):
    assert set(before) == {root, tail}, before
    assert all(row["state"] == "CANCELLED" for row in before.values()), before
    assert root in after and set(after) <= {root, tail}, after
    assert after[root] == before[root], after
    if tail in after:
        assert after[tail] == before[tail], after
    # A finished, never-enqueued dependent may be pruned on DB reopen. The parent
    # was enqueued recently and must still prove durable cancellation.
    return tail not in after
