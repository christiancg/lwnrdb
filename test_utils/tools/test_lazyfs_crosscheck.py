import io
import os
import shutil
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest import mock

import lazyfs_crosscheck as crosscheck
from durability.interrupts import FAULT_HALT_STATUS, FSYNC_FAILURE, POINTED, RANDOM, SEEDED_MODES
from durability.regression_seeds import REGRESSION_SEEDS
from durability.workload import Divergence


class FakeRun:
    def __init__(self, scratch):
        self.scratch = scratch


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

        self.assertEqual([(entry[0], entry[1]) for entry in REGRESSION_SEEDS], planned)

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

        self.assertEqual({"seed", "mode", "rebuild", "notes", "divergences", "label", "inconclusive",
                          "evidence"}, set(report[0]))
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

    def test_an_inconclusive_seed_keeps_its_divergences_in_the_report(self):
        report = []
        outcome = crosscheck.Outcome(9, RANDOM, divergences=[Divergence("O3", 9, "index lost d2")],
                                     inconclusive=crosscheck.APPEND_DEFECT)
        with redirect_stdout(io.StringIO()):
            crosscheck.report_outcome(outcome, report)

        self.assertEqual("O3", report[0]["divergences"][0]["oracle"])
        self.assertEqual(crosscheck.APPEND_DEFECT, report[0]["inconclusive"])

    def test_a_failed_smoke_still_stops_the_whole_run(self):
        with mock.patch.object(crosscheck, "run_seed", side_effect=crosscheck.SmokeFailed("write-through")):
            with self.assertRaises(crosscheck.SmokeFailed):
                crosscheck.run_seed_safely(9, RANDOM, keep=False)


class AppendDefectTest(unittest.TestCase):

    def setUp(self):
        self.scratch = tempfile.mkdtemp(prefix="lwnrdb-crosscheck-test-")
        self.folder = os.path.join(self.scratch, crosscheck.AT_POWER_LOSS, "db", "durability_db", "pl2")
        os.makedirs(self.folder)

    def tearDown(self):
        shutil.rmtree(self.scratch, ignore_errors=True)

    def log(self, lines: list) -> None:
        with open(os.path.join(self.folder, "pl2-v-JsonGeo.idxlog"), "w") as fp:
            fp.write("".join(line + "\n" for line in lines))

    def test_a_log_whose_head_was_overwritten_names_the_defect(self):
        self.log(["-\x1f#geo(1,2)\x1fd245", ")\x1fd2", "+\x1f#geo(0.1,179.95)\x1fd44"])

        defect = crosscheck.append_defect_of(FakeRun(self.scratch))

        self.assertTrue(defect.startswith(crosscheck.APPEND_DEFECT))
        self.assertIn("pl2-v-JsonGeo.idxlog:)|d2", defect)

    def test_well_formed_logs_name_no_defect(self):
        self.log(["+\x1f#geo(1,2)\x1fd245", "-\x1f#geo(0,0)\x1fd2"])

        self.assertEqual("", crosscheck.append_defect_of(FakeRun(self.scratch)))

    def test_no_snapshot_names_no_defect(self):
        shutil.rmtree(os.path.join(self.scratch, crosscheck.AT_POWER_LOSS))

        self.assertEqual("", crosscheck.append_defect_of(FakeRun(self.scratch)))


if __name__ == "__main__":
    unittest.main()
