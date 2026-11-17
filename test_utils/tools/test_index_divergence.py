import json
import os
import tempfile
import unittest

import index_divergence as divergence

SEP = "\x1f"


class ClassifierTest(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lwnrdb-divergence-test-")
        self.folder = os.path.join(self.root, "db", "durability_db", "pl1")
        os.makedirs(self.folder)
        self.page_bytes = b""

    def document(self, doc_id: str, n) -> None:
        line = (json.dumps({"_id": doc_id, "n": n}) + "\n").encode()
        position = len(self.page_bytes)
        self.page_bytes += line
        with open(os.path.join(self.folder, "pl1-0.dat"), "wb") as fp:
            fp.write(self.page_bytes)
        with open(os.path.join(self.folder, "pl1-pk.idx"), "a") as fp:
            fp.write(SEP.join([doc_id, str(position), str(len(line) - 1), "0", "1"]) + "\n")

    def index(self, name: str, lines: list) -> None:
        with open(os.path.join(self.folder, name), "w") as fp:
            fp.write("".join(line + "\n" for line in lines))

    def sight(self, doc_id: str):
        return divergence.sight(os.path.join(self.root, "db"), "durability_db", "pl1", "n", doc_id)

    def test_an_id_on_its_page_but_in_no_index_line_is_index_lost(self):
        self.document("d0", 1)
        self.index("pl1-n-Number.idx", [SEP.join(["1", "d5"])])

        self.assertEqual(divergence.INDEX_LOST, self.sight("d0").verdict)

    def test_an_id_indexed_under_its_page_value_is_consistent(self):
        self.document("d0", 1)
        self.index("pl1-n-Number.idx", [SEP.join(["1", "d0", "d5"])])

        self.assertEqual(divergence.CONSISTENT, self.sight("d0").verdict)

    def test_an_id_indexed_under_another_value_is_a_stale_index(self):
        self.document("d0", 1)
        self.index("pl1-n-Number.idx", [SEP.join(["7", "d0"])])

        self.assertEqual(divergence.STALE_INDEX, self.sight("d0").verdict)

    def test_an_idxlog_minus_line_cancels_a_log_plus_line(self):
        self.document("d0", 1)
        self.index("pl1-n-Number.idxlog", [SEP.join(["+", "1", "d0"]), SEP.join(["-", "1", "d0"])])

        self.assertEqual(divergence.INDEX_LOST, self.sight("d0").verdict)

    def test_an_id_named_in_the_wal_is_reported_in_wal(self):
        self.document("d0", 1)
        with open(os.path.join(self.folder, "pl1.wal"), "wb") as fp:
            fp.write(b"WP\x00\x00d0\x00")

        self.assertTrue(self.sight("d0").in_wal)

    def test_an_id_inside_a_longer_id_is_not_in_wal(self):
        self.document("d2", 1)
        with open(os.path.join(self.folder, "pl1.wal"), "wb") as fp:
            fp.write(b'{"_id":"d20"}\x1fd21\x1f')

        self.assertFalse(self.sight("d2").in_wal)

    def test_a_log_line_missing_its_head_is_reported_malformed(self):
        self.document("d2", 1)
        self.index("pl1-n-Number.idxlog", [SEP.join(["-", "7", "d5"]), SEP.join([")", "d2"])])

        sighting = self.sight("d2")

        self.assertEqual(divergence.INDEX_LOST, sighting.verdict)
        self.assertEqual(["pl1-n-Number.idxlog:)|d2"], sighting.malformed_log_lines)

    def test_an_empty_line_inside_a_log_is_reported_malformed(self):
        self.document("d2", 1)
        self.index("pl1-n-Number.idxlog", [SEP.join(["-", "7", "d5"]), "", SEP.join(["+", "1", "d9"])])

        self.assertEqual(["pl1-n-Number.idxlog:"], self.sight("d2").malformed_log_lines)

    def test_a_deleted_id_has_no_live_copy(self):
        self.document("d0", 1)
        with open(os.path.join(self.folder, "pl1-pk.idx"), "a") as fp:
            fp.write(SEP.join(["d0", "-"]) + "\n")

        self.assertEqual(divergence.NOT_ON_PAGE, self.sight("d0").verdict)

    def test_classify_reports_every_point(self):
        self.document("d0", 1)
        db = os.path.join(self.root, "db")

        rows = divergence.classify({"at_power_loss": db, "after_recovery": db}, "durability_db", "pl1", "n", ["d0"])

        self.assertEqual({"id", "at_power_loss", "after_recovery"}, set(rows[0]))
        self.assertEqual(divergence.INDEX_LOST, rows[0]["after_recovery"]["verdict"])


if __name__ == "__main__":
    unittest.main()
