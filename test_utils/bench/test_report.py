import unittest

import report


def engine_report(engine: str, container: dict) -> dict:
    return {"engine": engine, "phases": [{"phase": "insert", "errors": 0, "invalid": 0}], "final_count": 1,
            "container": container}


class FooterTest(unittest.TestCase):
    def test_g1_pause_rows_render_count_p99_max_and_full_gcs(self):
        lwnrdb = engine_report("lwnrdb", {"memory_peak": None, "data_bytes": None,
                                          "gc_pauses": {"count": 41, "p99_ms": 3.125, "max_ms": 12.5,
                                                        "full_gcs": 1}})
        mysql = engine_report("mysql", {"memory_peak": None, "data_bytes": None, "gc_pauses": None})
        lines = report._footer([lwnrdb, mysql])
        self.assertIn("| G1 pauses: count / p99 ms / max ms | 41 / 3.12 / 12.50 | — |", lines)
        self.assertIn("| G1 full GCs | 1 | — |", lines)

    def test_without_a_gc_log_there_are_no_pause_rows(self):
        lines = report._footer([engine_report("mysql", {"gc_pauses": None})])
        self.assertFalse(any("G1" in line for line in lines))
        self.assertFalse(any("ZGC" in line for line in lines))


if __name__ == "__main__":
    unittest.main()
