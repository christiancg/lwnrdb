import io
import unittest
from contextlib import redirect_stdout
from unittest import mock

import lazyfs_crosscheck as crosscheck
from durability.interrupts import FAULT_HALT_STATUS, FSYNC_FAILURE, POINTED, RANDOM, SEEDED_MODES
from durability.regression_seeds import REGRESSION_SEEDS
from durability.workload import Divergence


class FakeContainer:
    def __init__(self, alive=True, exits=True, smoke=(True, "")):
        self.alive_now = alive
        self.exits = exits
        self.smoke_result = smoke
        self.killed = False
        self.removed = False

    def alive(self):
        return self.alive_now

    def kill_server(self):
        self.killed = True
        self.alive_now = False

    def wait_exit(self, _timeout):
        return self.exits

    def up(self):
        pass

    def mount(self):
        pass

    def smoke(self):
        return self.smoke_result

    def remove(self):
        self.removed = True


class LabelTest(unittest.TestCase):

    def test_a_seed_the_shadow_always_passes_is_labelled_lazyfs_only(self):
        self.assertEqual(crosscheck.LAZYFS_ONLY, crosscheck.label_of([True, True, True]))

    def test_a_seed_the_shadow_fails_once_is_labelled_both(self):
        self.assertEqual(crosscheck.BOTH, crosscheck.label_of([True, False, True]))

    def test_no_shadow_replay_is_never_labelled_lazyfs_only(self):
        self.assertEqual(crosscheck.BOTH, crosscheck.label_of([]))


class PlanTest(unittest.TestCase):

    def test_regression_planning_reads_the_pinned_seeds_in_their_modes(self):
        planned = crosscheck.planned_runs("regression", None, crosscheck.ALL, None)

        self.assertEqual([(seed, mode) for seed, mode, _ in REGRESSION_SEEDS], planned)

    def test_regression_planning_keeps_only_the_asked_mode(self):
        planned = crosscheck.planned_runs("regression", None, POINTED, None)

        self.assertTrue(planned)
        self.assertTrue(all(mode == POINTED for _, mode in planned))

    def test_a_numbered_plan_cycles_the_modes_from_its_start(self):
        with redirect_stdout(io.StringIO()):
            planned = crosscheck.planned_runs("4", 10, crosscheck.ALL, None)

        self.assertEqual([(10, RANDOM), (11, POINTED), (12, FSYNC_FAILURE), (13, RANDOM)], planned)

    def test_a_replay_runs_one_seed_in_every_asked_mode(self):
        self.assertEqual([(7, mode) for mode in SEEDED_MODES], crosscheck.planned_runs("regression", None,
                                                                                     crosscheck.ALL, 7))
        self.assertEqual([(7, FSYNC_FAILURE)], crosscheck.planned_runs("regression", None, FSYNC_FAILURE, 7))


class ContainerProcessTest(unittest.TestCase):

    def test_container_process_reports_alive_as_none_and_dead_as_the_halt_status(self):
        container = FakeContainer()
        process = crosscheck.ContainerProcess(container)

        self.assertIsNone(process.poll())
        process.kill()

        self.assertTrue(container.killed)
        self.assertEqual(FAULT_HALT_STATUS, process.poll())
        self.assertEqual(FAULT_HALT_STATUS, process.wait(1))

    def test_container_process_wait_raises_when_the_server_outlives_the_timeout(self):
        process = crosscheck.ContainerProcess(FakeContainer(exits=False))

        with self.assertRaises(TimeoutError):
            process.wait(1)


class SmokeTest(unittest.TestCase):

    def test_a_failed_smoke_refuses_to_run_the_seed(self):
        failing = FakeContainer(smoke=(False, "forced write kept ''"))
        with mock.patch.object(crosscheck, "LazyFsContainer", return_value=failing), \
                redirect_stdout(io.StringIO()):
            with self.assertRaises(crosscheck.SmokeFailed):
                crosscheck.run_seed(3, RANDOM, keep=False)

        self.assertTrue(failing.removed, "the container is removed even when the smoke fails")

    def test_a_failed_smoke_alone_exits_with_its_status(self):
        failing = FakeContainer(smoke=(False, "unforced write left 'smoke-unforced'"))
        with mock.patch.object(crosscheck, "LazyFsContainer", return_value=failing), \
                redirect_stdout(io.StringIO()):
            self.assertEqual(crosscheck.SMOKE_FAILED_STATUS, crosscheck.run_smoke_only())

        self.assertTrue(failing.removed)


class ReportTest(unittest.TestCase):

    def test_the_report_matches_the_power_loss_report_shape(self):
        outcome = crosscheck.Outcome(5, RANDOM, notes=["history {}"],
                                     divergences=[Divergence("O1", 5, "lost an acknowledged write")],
                                     label=crosscheck.LAZYFS_ONLY)
        report = []
        with redirect_stdout(io.StringIO()):
            crosscheck.report_outcome(outcome, report)

        self.assertEqual({"seed", "mode", "rebuild", "notes", "divergences", "label", "inconclusive"}, set(report[0]))
        self.assertEqual(crosscheck.REBUILD, report[0]["rebuild"])
        self.assertEqual("O1", report[0]["divergences"][0]["oracle"])
        self.assertEqual(crosscheck.LAZYFS_ONLY, report[0]["label"])


class InconclusiveTest(unittest.TestCase):

    def test_a_lazyfs_death_makes_the_seed_inconclusive_not_failed(self):
        died = crosscheck.LazyFsDied("LazyFS is no longer running: >> FTRUNCATE")
        with mock.patch.object(crosscheck, "run_seed", side_effect=died):
            outcome = crosscheck.run_seed_safely(9, FSYNC_FAILURE, keep=False)

        self.assertIn("no longer running", outcome.inconclusive)
        self.assertEqual([], outcome.divergences)

    def test_an_inconclusive_seed_is_reported_as_such(self):
        report = []
        with redirect_stdout(io.StringIO()):
            crosscheck.report_outcome(crosscheck.Outcome(9, FSYNC_FAILURE, inconclusive="LazyFS stopped"), report)

        self.assertEqual("LazyFS stopped", report[0]["inconclusive"])
        self.assertEqual([], report[0]["divergences"])

    def test_a_failed_smoke_still_stops_the_whole_run(self):
        with mock.patch.object(crosscheck, "run_seed", side_effect=crosscheck.SmokeFailed("write-through")):
            with self.assertRaises(crosscheck.SmokeFailed):
                crosscheck.run_seed_safely(9, RANDOM, keep=False)


if __name__ == "__main__":
    unittest.main()
