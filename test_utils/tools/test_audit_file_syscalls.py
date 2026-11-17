import unittest

from audit_file_syscalls import parse_trace, percentile

TRACE = """101 openat(AT_FDCWD, "/app/db/audit/docs/docs-pk.idx", O_RDWR) = 41</app/db/audit/docs/docs-pk.idx> <0.000020>
101 fdatasync(42</app/db/audit/docs/docs.wal>) = 0 <0.004000>
102 fdatasync(43</app/db/audit/docs/docs-1.dat> <unfinished ...>
103 fdatasync(44</app/db/audit/docs/docs.wal> <unfinished ...>
103 <... fdatasync resumed>) = 0 <0.002000>
102 <... fdatasync resumed>) = 0 <0.010000>
104 renameat(AT_FDCWD, "/app/db/audit/docs/docs.wal.repair", AT_FDCWD, "/app/db/audit/docs/docs.wal") = 0 <0.000050>
104 renameat(AT_FDCWD, "/app/db/audit/docs/docs-1.vacuum", AT_FDCWD, "/app/db/audit/docs/docs-1.dat") = 0 <0.000050>
105 pwrite64(45</app/db/audit/docs/docs-1.dat>, "{}", 2, 0 <unfinished ...>
105 <... pwrite64 resumed>) = 2 <0.000010>
106 fsync(46</app/logs/server.log>) = 0 <0.001000>
"""


class ParseTraceTest(unittest.TestCase):
    def test_counts_calls_on_db_files_only(self):
        trace = parse_trace(TRACE)
        self.assertEqual(2, trace.counts[("fdatasync", ".wal")])
        self.assertEqual(1, trace.counts[("fdatasync", ".dat")])
        self.assertEqual(1, trace.counts[("pwrite64", ".dat")])
        self.assertNotIn(("fsync", "folder server.log"), trace.counts)

    def test_sync_durations_include_resumed_calls(self):
        trace = parse_trace(TRACE)
        self.assertEqual([0.004, 0.002], trace.syncs[".wal"])
        self.assertEqual([0.010], trace.syncs[".dat"])

    def test_only_log_renames_count_as_checkpoints(self):
        self.assertEqual(1, parse_trace(TRACE).wal_checkpoints())

    def test_percentile_is_the_nearest_rank(self):
        values = [float(i) for i in range(1, 101)]
        self.assertEqual(50.0, percentile(values, 0.5))
        self.assertEqual(99.0, percentile(values, 0.99))
        self.assertEqual(7.0, percentile([7.0], 0.99))


if __name__ == "__main__":
    unittest.main()
