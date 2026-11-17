import os
import tempfile
import unittest

import test_shards

POM = """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <build><plugins><plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <executions>
      <execution>
        <id>default-test</id>
        <configuration><excludes>
          <exclude>**/config/SerialConfigTest.java</exclude>
          <exclude>**/perf/*BudgetTest.java</exclude>
        </excludes></configuration>
      </execution>
      <execution>
        <id>tests-that-replace-the-config-file</id>
        <configuration><includes><include>**/config/SerialConfigTest.java</include></includes></configuration>
      </execution>
      <execution>
        <id>tests-that-measure-allocation</id>
        <configuration><includes><include>**/perf/*BudgetTest.java</include></includes></configuration>
      </execution>
    </executions>
  </plugin></plugins></build>
</project>
"""

SOURCES = ["org/x/FooTest.java", "org/x/TestFoo.java", "org/x/FooTests.java", "org/x/FooTestCase.java",
           "org/x/FooSupport.java", "org/x/Foo$Inner.java", "org/x/config/SerialConfigTest.java",
           "org/x/perf/AllocationBudgetTest.java", "org/x/perf/deep/OtherBudgetTest.java"]

REPORT = '<testsuite name="{name}" time="{time}" tests="1"><testcase name="t" classname="{name}"/></testsuite>\n'


def read_lines(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read().split()


class FixtureTree:
    def __init__(self, sources=SOURCES, durations=None):
        self.temp = tempfile.TemporaryDirectory()
        self.root = self.temp.name
        with open(os.path.join(self.root, "pom.xml"), "w", encoding="utf-8") as handle:
            handle.write(POM)
        for source in sources:
            path = os.path.join(self.root, test_shards.TEST_SOURCES, source)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            open(path, "w", encoding="utf-8").close()
        if durations is not None:
            path = os.path.join(self.root, test_shards.DURATIONS_FILE)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as handle:
                handle.write("".join(f"{name}\t{seconds}\n" for name, seconds in durations.items()))

    def report(self, folder, name, seconds=1.0):
        directory = os.path.join(self.root, "reports", folder)
        os.makedirs(directory, exist_ok=True)
        with open(os.path.join(directory, f"TEST-{name}.xml"), "w", encoding="utf-8") as handle:
            handle.write(REPORT.format(name=name, time=seconds))
        return os.path.join(self.root, "reports")

    def close(self):
        self.temp.cleanup()


class DiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.tree = FixtureTree()

    def tearDown(self):
        self.tree.close()

    def test_discovery_mirrors_surefire_defaults(self):
        self.assertEqual(["org/x/FooTest.java", "org/x/FooTestCase.java", "org/x/FooTests.java",
                          "org/x/TestFoo.java", "org/x/perf/deep/OtherBudgetTest.java"],
                         test_shards.sharded_classes(self.tree.root))

    def test_pom_excludes_are_honoured_and_feed_the_serial_list(self):
        self.assertEqual(["org/x/config/SerialConfigTest.java", "org/x/perf/AllocationBudgetTest.java"],
                         test_shards.serial_classes(self.tree.root))

    def test_ant_patterns_keep_single_stars_inside_one_folder(self):
        self.assertTrue(test_shards.matches_any("a/b/perf/XBudgetTest.java", ["**/perf/*BudgetTest.java"]))
        self.assertFalse(test_shards.matches_any("a/perf/deep/XBudgetTest.java", ["**/perf/*BudgetTest.java"]))
        self.assertTrue(test_shards.matches_any("FooTest.java", ["**/*Test.java"]))


class PlanTest(unittest.TestCase):
    def test_every_class_lands_in_exactly_one_shard(self):
        classes = [f"c{index}" for index in range(23)]
        shards = test_shards.plan(classes, {}, 4)
        flattened = [member for shard in shards for member in shard]
        self.assertEqual(sorted(classes), sorted(flattened))
        self.assertEqual(len(flattened), len(set(flattened)))

    def test_the_heaviest_classes_spread_across_shards(self):
        weights = {"a": 10, "b": 9, "c": 8, "d": 1, "e": 1, "f": 1}
        loads = [sum(weights[name] for name in shard) for shard in test_shards.plan(list(weights), weights, 3)]
        self.assertLessEqual(max(loads) - min(loads), max(weights.values()))
        self.assertEqual(3, len([load for load in loads if load >= 8]))

    def test_unknown_classes_take_the_median_weight(self):
        shards = test_shards.plan(["known1", "known2", "known3", "new"], {"known1": 1, "known2": 2, "known3": 3}, 2)
        loads = sorted(sum({"known1": 1, "known2": 2, "known3": 3, "new": 2}[name] for name in shard)
                       for shard in shards)
        self.assertEqual([4, 4], loads)

    def test_the_plan_is_deterministic(self):
        classes = [f"c{index}" for index in range(40)]
        weights = {name: (index * 7) % 5 + 0.5 for index, name in enumerate(classes)}
        self.assertEqual(test_shards.plan(classes, weights, 4), test_shards.plan(list(reversed(classes)), weights, 4))

    def test_more_shards_than_classes_leaves_empty_shards_valid(self):
        shards = test_shards.plan(["only"], {}, 3)
        self.assertEqual([["only"], [], []], shards)

    def test_write_plan_writes_one_file_per_shard(self):
        tree = FixtureTree()
        try:
            out = os.path.join(tree.root, "plan")
            test_shards.write_plan(tree.root, 2, out)
            written = sorted(line for number in (1, 2) for line in read_lines(os.path.join(out, f"shard-{number}.txt")))
            self.assertEqual(test_shards.sharded_classes(tree.root), written)
        finally:
            tree.close()


class ReportsTest(unittest.TestCase):
    def test_record_sums_testsuite_times_per_class(self):
        tree = FixtureTree()
        try:
            tree.report("a", "org.x.FooTest", 1.5)
            reports = tree.report("a", "org.x.FooTest$Nested", 0.25)
            tree.report("b", "org.x.TestFoo", 2)
            totals = test_shards.record(reports, os.path.join(tree.root, "durations.tsv"))
            self.assertEqual({"org/x/FooTest.java": 1.75, "org/x/TestFoo.java": 2.0}, totals)
            self.assertEqual({"org/x/FooTest.java": 1.75, "org/x/TestFoo.java": 2.0},
                             test_shards.read_durations(os.path.join(tree.root, "durations.tsv")))
        finally:
            tree.close()

    def test_check_complete_reports_missing_and_duplicate_classes(self):
        tree = FixtureTree(durations={"org/x/FooTest.java": 1, "org/x/TestFoo.java": 1,
                                      "org/x/config/SerialConfigTest.java": 1, "org/x/Deleted.java": 1})
        try:
            tree.report("shard-1", "org.x.FooTest")
            tree.report("shard-2", "org.x.FooTest")
            reports = tree.report("serial", "org.x.config.SerialConfigTest")
            self.assertEqual(["did not run: org/x/TestFoo.java", "ran in 2 shards: org/x/FooTest.java"],
                             test_shards.check_complete(tree.root, reports))
        finally:
            tree.close()

    def test_check_complete_passes_when_every_recorded_class_ran_once(self):
        tree = FixtureTree(durations={"org/x/FooTest.java": 1})
        try:
            reports = tree.report("shard-1", "org.x.FooTest$Nested")
            self.assertEqual([], test_shards.check_complete(tree.root, reports))
            self.assertIn("org/x/TestFoo.java", test_shards.unrecorded(tree.root, reports))
        finally:
            tree.close()

    def test_main_returns_one_on_an_incomplete_run(self):
        tree = FixtureTree(durations={"org/x/FooTest.java": 1})
        try:
            reports = tree.report("shard-1", "org.x.TestFoo")
            self.assertEqual(1, test_shards.main(["--root", tree.root, "--check-complete", reports]))
        finally:
            tree.close()


if __name__ == "__main__":
    unittest.main()
