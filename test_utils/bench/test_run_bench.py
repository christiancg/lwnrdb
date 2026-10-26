import unittest

from run_bench import parse_g1_pauses

LOG = """[0.012s][info][gc] Using G1
[1.204s][info][gc,start] GC(0) Pause Young (Normal) (G1 Evacuation Pause)
[1.209s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->4M(256M) 4.512ms
[2.001s][info][gc] GC(1) Pause Young (Concurrent Start) (G1 Humongous Allocation) 60M->40M(256M) 2.250ms
[2.002s][info][gc] GC(2) Concurrent Mark Cycle
[2.050s][info][gc] GC(2) Pause Remark 45M->45M(256M) 1.100ms
[2.060s][info][gc] GC(2) Pause Cleanup 45M->45M(256M) 0.080ms
[2.070s][info][gc] GC(2) Concurrent Mark Cycle 68.003ms
[3.500s][info][gc,phases] GC(3)   Pre Evacuate Collection Set: 0.1ms
[4.000s][info][gc] GC(4) Pause Full (G1 Compaction Pause) 250M->120M(256M) 85.300ms
"""


class ParseG1PausesTest(unittest.TestCase):
    def test_counts_every_pause_kind_and_only_pauses(self):
        pauses = parse_g1_pauses(LOG)
        self.assertEqual(5, pauses["count"])
        self.assertEqual(1, pauses["full_gcs"])
        self.assertEqual(85.3, pauses["max_ms"])

    def test_p99_is_the_nearest_rank(self):
        lines = [f"[1.0s][info][gc] GC({i}) Pause Young (Normal) (G1 Evacuation Pause) 9M->1M(64M) {i + 1}.000ms"
                 for i in range(200)]
        pauses = parse_g1_pauses("\n".join(lines))
        self.assertEqual(200, pauses["count"])
        self.assertEqual(198.0, pauses["p99_ms"])
        self.assertEqual(200.0, pauses["max_ms"])
        self.assertEqual(0, pauses["full_gcs"])

    def test_a_single_pause_is_its_own_p99(self):
        pauses = parse_g1_pauses("[1s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 1M->1M(8M) 0.5ms")
        self.assertEqual(0.5, pauses["p99_ms"])

    def test_a_log_without_pauses_reports_zero(self):
        self.assertEqual({"count": 0, "p99_ms": 0.0, "max_ms": 0.0, "full_gcs": 0},
                         parse_g1_pauses("[0.012s][info][gc] Using G1\n"))

    def test_a_missing_log_is_none(self):
        self.assertIsNone(parse_g1_pauses(None))

    def test_zgc_lines_are_not_g1_pauses(self):
        zgc = ("[1.0s][info][gc] GC(0) Minor Collection (Allocation Rate) 20M(8%)->6M(2%) 0.012s\n"
               "[1.0s][info][gc,alloc] GC(0) Allocation Stalls: 0 0 0 0\n")
        self.assertEqual(0, parse_g1_pauses(zgc)["count"])


if __name__ == "__main__":
    unittest.main()
