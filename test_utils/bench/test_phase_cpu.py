import unittest

import phase_cpu
from phase_cpu import PhaseCpu, parse_usage_usec


def stat(usec: int) -> str:
    return f"usage_usec {usec}\nuser_usec {usec // 2}\nsystem_usec {usec // 2}\nnr_periods 0\n"


class Reader:
    def __init__(self, readings):
        self.readings = list(readings)

    def __call__(self):
        return self.readings.pop(0) if self.readings else ""


def run(meter: PhaseCpu, lines) -> None:
    for line in lines:
        meter.on_line(line + "\n")


class ParseUsageTest(unittest.TestCase):
    def test_reads_usage_usec(self):
        self.assertEqual(1234567, parse_usage_usec(stat(1234567)))

    def test_ignores_the_user_and_system_lines(self):
        self.assertEqual(10, parse_usage_usec("user_usec 99\nusage_usec 10\nsystem_usec 5\n"))

    def test_missing_counter_is_none(self):
        self.assertIsNone(parse_usage_usec("user_usec 1\n"))
        self.assertIsNone(parse_usage_usec(""))


class PhaseCpuTest(unittest.TestCase):
    def test_a_phase_is_measured_to_the_next_phase_start(self):
        meter = PhaseCpu(Reader([stat(1_000_000), stat(3_000_000), stat(4_000_000), stat(9_000_000)]))
        run(meter, ["PHASE_START insert", "PHASE_END insert 100", "PHASE_START update", "PHASE_END update 50"])

        self.assertEqual({"insert": 3.0, "update": 5.0}, meter.cpu_seconds())

    def test_attach_divides_by_the_operations_with_warmup(self):
        meter = PhaseCpu(Reader([stat(0), stat(2_000_000)]))
        run(meter, ["PHASE_START insert", "PHASE_END insert 400"])
        report = {"phases": [{"phase": "insert", "ops": 360, "ops_with_warmup": 400}]}

        meter.attach(report)

        self.assertEqual(2.0, report["phases"][0]["cpu_seconds"])
        self.assertEqual(5.0, report["phases"][0]["cpu_ms_per_op"])

    def test_attach_falls_back_to_the_marker_operation_count(self):
        meter = PhaseCpu(Reader([stat(0), stat(1_000_000)]))
        run(meter, ["PHASE_START insert", "PHASE_END insert 200"])
        report = {"phases": [{"phase": "insert"}]}

        meter.attach(report)

        self.assertEqual(5.0, report["phases"][0]["cpu_ms_per_op"])

    def test_a_counter_that_goes_backwards_is_not_reported(self):
        meter = PhaseCpu(Reader([stat(5_000_000), stat(1_000_000)]))
        run(meter, ["PHASE_START insert", "PHASE_END insert 100"])
        report = {"phases": [{"phase": "insert", "ops_with_warmup": 100}]}

        meter.attach(report)

        self.assertIsNone(meter.cpu_seconds()["insert"])
        self.assertNotIn("cpu_ms_per_op", report["phases"][0])

    def test_an_unreadable_counter_is_not_reported(self):
        meter = PhaseCpu(Reader(['', stat(1_000_000)]))
        run(meter, ["PHASE_START insert", "PHASE_END insert 100"])
        report = {"phases": [{"phase": "insert", "ops_with_warmup": 100}]}

        meter.attach(report)

        self.assertNotIn("cpu_seconds", report["phases"][0])

    def test_a_phase_with_no_operations_is_not_reported(self):
        meter = PhaseCpu(Reader([stat(0), stat(1_000_000)]))
        run(meter, ["PHASE_START insert", "PHASE_END insert 0"])
        report = {"phases": [{"phase": "insert", "ops_with_warmup": 0}]}

        meter.attach(report)

        self.assertNotIn("cpu_ms_per_op", report["phases"][0])

    def test_lines_that_are_not_markers_never_read_the_counter(self):
        reader = Reader([stat(0)])
        meter = PhaseCpu(reader)

        run(meter, ["[lwnrdb] insert   4,000.0 ops/s", "", "PHASE_END orphan 5", "PHASE_END insert x"])

        self.assertEqual(1, len(reader.readings))
        self.assertEqual({}, meter.cpu_seconds())

    def test_a_phase_that_never_ended_has_no_cpu(self):
        meter = PhaseCpu(Reader([stat(0)]))
        run(meter, ["PHASE_START insert"])

        self.assertIsNone(meter.cpu_seconds()["insert"])

    def test_phases_missing_from_the_report_are_ignored(self):
        meter = PhaseCpu(Reader([stat(0), stat(1_000_000)]))
        run(meter, ["PHASE_START insert", "PHASE_END insert 10"])
        report = {"phases": [{"phase": "delete", "ops_with_warmup": 10}]}

        meter.attach(report)

        self.assertNotIn("cpu_seconds", report["phases"][0])


class FollowTest(unittest.TestCase):
    def test_streams_every_line_and_returns_the_exit_status(self):
        seen = []

        status = phase_cpu.follow(["sh", "-c", "echo one; echo two; exit 3"], {}, seen.append)

        self.assertEqual(["one\n", "two\n"], seen)
        self.assertEqual(3, status)


if __name__ == "__main__":
    unittest.main()
